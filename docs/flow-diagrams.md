# CCE Emitter Adaptor — Flow Diagrams

All diagrams use Mermaid notation.

## 1. End-to-End Event Flow

```mermaid
flowchart LR
    subgraph Sources
        EBZ[eBUZIMA EMR<br/>FHIR Resource]
    end

    subgraph OpenHIM
        OHC[OpenHIM Core<br/>Channel Router]
    end

    subgraph "CCE Emitter Adaptor (Spring Boot)"
        CTRL[InboundEvent<br/>Controller]
        SA[SourceAdaptor<br/>Service]
        PARSE[FHIR Resource<br/>Parser]
        NORM[CloudEvent<br/>Builder]
        RED["Clinical Data<br/>Redactor"]
        FWD["Collector<br/>Forwarding<br/>@Retryable"]
        WRAP[OpenHIM<br/>ResponseWrapper]
    end

    subgraph CCE
        COL[CCE Collector]
        KAFKA[Kafka<br/>cce.events.inbound]
    end

    EBZ -->|FHIR Resource| OHC

    OHC -->|"Secondary route<br/>(not primary path)"| CTRL
    CTRL --> SA
    SA --> PARSE
    PARSE --> NORM
    NORM --> RED
    RED -->|"clinical findings removed"| FWD
    FWD -->|POST /v1/events| COL
    COL --> KAFKA

    FWD --> WRAP
    WRAP -->|application/json+openhim| OHC
```

## 2. Request Processing Sequence

```mermaid
sequenceDiagram
    participant OHC as OpenHIM Core
    participant Ctrl as InboundEventController
    participant EvtSvc as InboundEventService
    participant Svc as SourceAdaptorService
    participant CE as CloudEventEnvelopeBuilder
    participant Red as ClinicalDataRedactor
    participant Fwd as CollectorForwardingService
    participant Col as CCE Collector
    participant Wrap as OpenHimResponseWrapper

    OHC->>Ctrl: POST /inbound (FHIR Resource)
    activate Ctrl

    Ctrl->>Ctrl: InboundRequest.from(body, headers, path)

    Ctrl->>EvtSvc: process(inboundRequest)
    activate EvtSvc

    Note over EvtSvc: Counter: cce.emitter.events.received

    EvtSvc->>Svc: adapt(inboundRequest)
    activate Svc
    Svc->>Svc: resolveSource(request) → sourceKey
    Svc->>Svc: Parse FHIR resource (ignore if Bundle)
    Svc->>Svc: buildSourceMetadata (resolve facilityId: header → FHIR fallback)
    Svc->>Svc: FacilityFilter.enforceFilter(facilityId, sourceKey)
    Note over Svc: 403 FACILITY_FILTER_REJECTED if denied
    Svc->>CE: build(fhirResource, patientUpid, type, metadata)
    activate CE
    CE->>Red: redact(data)
    activate Red
    Note over Red: Strip value[x], component, narrative,<br/>notes, contained, subject.display
    Note over Red: Counter: cce.emitter.events.redacted.total
    Red-->>CE: minimised data
    deactivate Red
    Note over CE: Data-minimisation boundary —<br/>clinical findings do not exist downstream
    CE-->>Svc: CloudEventDto
    deactivate CE
    Svc-->>EvtSvc: List<CloudEventDto>
    deactivate Svc

    loop Each CloudEvent
        Note over EvtSvc: MDC: correlationId, source, eventType, subject
        EvtSvc->>Fwd: forward(cloudEvent)
        activate Fwd
        Note over Fwd: Timer: cce.emitter.collector.latency
        Note over Fwd: CollectorTokenService: get Bearer token<br/>(OAuth2 Keycloak or static fallback)
        Fwd->>Col: POST /v1/events
        Col-->>Fwd: 202 Accepted
        Fwd-->>EvtSvc: CollectorResponse
        deactivate Fwd
        Note over EvtSvc: Counter: cce.emitter.events.forwarded
        Note over EvtSvc: MDC.clear()
    end

    EvtSvc->>EvtSvc: TransformationResult + Orchestration
    EvtSvc->>Wrap: wrap(result, 202, orchestrations)
    Wrap-->>EvtSvc: OpenHimResponse

    EvtSvc-->>Ctrl: OpenHimResponse
    deactivate EvtSvc

    Ctrl-->>OHC: 202 (application/json+openhim)
    deactivate Ctrl
```

## 3. Adaptor Selection Flow

```mermaid
flowchart TD
    A[POST /inbound] --> B{X-OpenHIM-ClientID<br/>matches configured<br/>source?}

    B -->|Yes| C[Resolved source key]
    B -->|No / not set| G{X-Source-System<br/>header?}

    G -->|Matches known source| C
    G -->|No match| J[Log debug + silently ignore<br/>→ 200 OK]

    style C fill:#e1f5fe
    style J fill:#fff3e0
```

## 4. Collector Forwarding with Retry

```mermaid
sequenceDiagram
    participant Fwd as CollectorForwardingService
    participant Col as CCE Collector
    participant Log as Logger

    Fwd->>Col: POST /v1/events (attempt 1)

    alt 202 Accepted
        Col-->>Fwd: 202 + { status: accepted }
        Fwd->>Log: Event forwarded successfully
    else 200 Duplicate
        Col-->>Fwd: 200 + { status: duplicate }
        Fwd->>Log: Event is duplicate (idempotent)
    else 400 Client Error
        Col-->>Fwd: 400 + { error: ... }
        Fwd->>Log: Client error — no retry
        Note over Fwd: Counter: cce.emitter.events.rejected
        Fwd->>Fwd: throw CollectorClientException
    else 5xx / Timeout
        Col-->>Fwd: 503 Service Unavailable
        Fwd->>Log: Retry 1 of 3 (backoff 1s)

        Note over Fwd: @Retryable backoff: 1s

        Fwd->>Col: POST /v1/events (attempt 2)

        alt Success
            Col-->>Fwd: 202 Accepted
        else Still failing
            Col-->>Fwd: 503
            Fwd->>Log: Retry 2 of 3 (backoff 2s)

            Note over Fwd: Exponential backoff: 2s

            Fwd->>Col: POST /v1/events (attempt 3)

            alt Success
                Col-->>Fwd: 202 Accepted
            else Exhausted
                Col-->>Fwd: 503
                Note over Fwd: Counter: cce.emitter.collector.retries
                Fwd->>Fwd: @Recover → throw CollectorForwardingException
            end
        end
    else SocketTimeoutException (read timeout)
        Note over Fwd: Caught via catch-all with instanceof check
        Fwd->>Log: Collector read timed out (will retry)
        Note over Fwd: Wraps as CollectorForwardingException → triggers @Retryable
    end
```

## 5. OpenHIM Registration & Heartbeat Lifecycle

```mermaid
sequenceDiagram
    participant App as Spring Boot App
    participant Reg as MediatorRegistrar
    participant HB as HeartbeatScheduler
    participant Core as OpenHIM Core API

    Note over App: ApplicationReadyEvent

    App->>Reg: @EventListener → register()
    Reg->>Core: POST /mediators (descriptor JSON, defaultChannelConfig=[])
    Note right of Core: No channel auto-provisioned —<br/>admin adds secondary route<br/>on existing eBUZIMA channel

    alt Registration OK
        Core-->>Reg: 201 Created
        Reg->>Reg: Log success
    else Registration fails
        Core-->>Reg: Error
        Reg->>Reg: Log warning (non-fatal)
    end

    Note over HB: @Scheduled (every 10s)

    loop Heartbeat cycle
        HB->>Core: POST /mediators/{urn}/heartbeat
        Core-->>HB: 200 OK
        HB->>HB: Log debug (uptime)
    end
```

## 6. Error Handling Flow

```mermaid
flowchart TD
    A[Inbound Request] --> AA{POST method?}

    AA -->|No| AB[Return 200 OK<br/>Non-POST request ignored]
    AA -->|Yes| B{Parse OK?}

    B -->|No| C[GlobalExceptionHandler<br/>400/500]
    B -->|Yes| D{Adaptor found?}

    D -->|No| E[Log debug + silently ignore<br/>→ 200 OK]
    D -->|Yes| F{FHIR resource valid?}

    F -->|No| G[FhirMappingException<br/>→ 422 FHIR_MAPPING_ERROR]
    F -->|Yes| H{Patient ID found?}

    H -->|No| I[PatientIdNotFoundException<br/>→ 400 PATIENT_ID_NOT_FOUND]
    H -->|Yes| FF{Facility filter pass?}

    FF -->|No| FE[FacilityFilterRejectedException<br/>→ 403 FACILITY_FILTER_REJECTED]
    FF -->|Yes| J{Collector accepts?}

    J -->|202 OK| K[Success → 202 with orchestrations]
    J -->|200 Dup| L[Duplicate → still 202]
    J -->|400 Client| M[CollectorClientException<br/>→ include in batch result]
    J -->|5xx × 3| N[CollectorForwardingException<br/>→ 502 COLLECTOR_FORWARDING_ERROR]
    J -->|Unexpected| UE[RuntimeException<br/>→ 500 INTERNAL_ERROR]

    AB --> P
    C --> O[OpenHimResponseWrapper<br/>wraps error in mediator envelope]
    E --> P
    FE --> O
    G --> O
    I --> O
    K --> O
    L --> O
    M --> O
    N --> O
    UE --> O

    O --> P[Return to OpenHIM Core]

    style K fill:#e8f5e9
    style L fill:#fff3e0
    style AB fill:#e3f2fd
    style E fill:#ffebee
    style FE fill:#ffebee
    style G fill:#ffebee
    style I fill:#ffebee
    style N fill:#ffebee
    style UE fill:#ffebee
```

## 7. Component Dependency Graph

```mermaid
flowchart TD
    CTRL[InboundEventController]
    EVTSVC[InboundEventService]
    REG[SourceAdaptorService]
    FWD[CollectorForwardingService]
    WRAP[OpenHimResponseWrapper]

    SA_ABS["(internal FHIR→CloudEvent logic)"]

    CE[CloudEventEnvelopeBuilder]
    RED[ClinicalDataRedactor]
    PIE[PatientIdExtractor]
    FRP[FhirResourceParser]
    IDG[EventIdGenerator]

    REG_HB[MediatorRegistrar]
    HB[HeartbeatScheduler]

    FC["FhirConfig<br/>@Bean FhirContext"]
    RC["RestClientConfig<br/>@Bean RestClient"]

    CTRL --> EVTSVC

    EVTSVC --> REG
    EVTSVC --> FWD
    EVTSVC --> WRAP

    REG --> SA_ABS

    SA_ABS --> CE
    SA_ABS --> PIE

    CE --> IDG
    CE --> RED

    FWD --> RC
    REG_HB --> RC
    HB --> RC

    SA_ABS --> FC
    FRP --> FC

    style CTRL fill:#bbdefb
    style WRAP fill:#bbdefb
    style FWD fill:#c8e6c9
    style RED fill:#ffe0b2
    style REG fill:#fff9c4
    style REG_HB fill:#e1bee7
    style HB fill:#e1bee7
```

## 8. Deployment Topology

```mermaid
flowchart LR
    subgraph "External Sources"
        A2[eBUZIMA EMR<br/>FHIR Resource]
    end

    subgraph "OpenHIM"
        OHC[OpenHIM Core<br/>:5000/:5001]
        OHC_API[Core API<br/>:8080]
        OHC_CON[Console<br/>:9000]
    end

    subgraph "CCE Emitter Adaptor"
        EA[Spring Boot<br/>:8082]
    end

    subgraph "CCE Platform"
        GW[CCE Gateway]
        COL[CCE Collector]
        KAFKA[Kafka]
    end

    A2 --> OHC

    OHC -->|"Secondary route<br/>(not primary path)"| EA
    EA -->|"Register + heartbeat"| OHC_API
    EA -->|"POST /v1/events"| COL
    COL --> KAFKA
```
