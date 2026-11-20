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

## 9. Sygnal Flows (Proposal)

> **Status:** Built on branch `sygnal-actions`, on by default (`beckn.enabled=true`; set `OPENHIM_EMITTER_BECKN_ENABLED=false` to turn it off). Full design: [Beckn Integration](beckn-integration.md). The flows in §1–§8 are unchanged: with `beckn.enabled=false` and every source on `output: collector`, events go from OpenHIM to the CCE Collector as today.

### 9.1 Sygnal Inbound → Channel → Pipeline → Sygnal Output

Both hops use the action `sygnal/event/report`. There are no callbacks: every answer comes back in the same HTTP call, and the HMIS gets its answer only after CCE has answered.

```mermaid
sequenceDiagram
    participant HM as OpenMRS emitter<br/>→ onix-openmrs caller
    participant OR as onix-openhim<br/>receiver
    participant BW as SygnalEventReportController<br/>+ BecknInboundService
    participant OHC as OpenHIM Core<br/>(channel /cce-beckn)
    participant IN as InboundEventController<br/>+ InboundEventService
    participant SA as SourceAdaptorService<br/>+ CloudEventEnvelopeBuilder
    participant BF as BecknForwardingService
    participant OC as onix-openhim<br/>caller
    participant CCE as onix-cce receiver<br/>→ cce-beckn-service :8075<br/>→ Collector

    HM->>OR: sygnal/event/report (signed by the HMIS)
    OR->>BW: POST /sygnal/event/report
    activate BW
    Note over BW: Counter: cce.emitter.beckn.received
    BW->>BW: action, senderId in subscribers, receiverId = beckn.self.id,<br/>messageId, exactly one event, id a UUID, CceClinicalEvent box
    BW->>BW: take events[0].eventAttributes.resource
    BW->>OHC: POST channel (Basic auth)<br/>X-Source-System, X-Source-Event-Id, X-Sygnal-Event-Id,<br/>X-Sygnal-Occurred-At, X-Correlation-Id
    activate OHC
    Note over BW: Timer: cce.emitter.beckn.channel.latency
    OHC->>IN: POST /inbound (primary route)<br/>+ X-OpenHIM-ClientID, X-OpenHIM-TransactionID
    activate IN
    IN->>SA: adapt(request)
    activate SA
    SA->>SA: resolveSource: X-OpenHIM-ClientID, then X-Source-System
    SA->>SA: parse FHIR, patient id, facility (from resource), filter
    SA->>SA: redact, build CloudEvent<br/>id = X-Sygnal-Event-Id, time = X-Sygnal-Occurred-At
    SA-->>IN: CloudEventDto
    deactivate SA
    IN->>IN: EventForwardingRouter: source output = beckn
    IN->>BF: forward(cloudEvent)
    activate BF
    BF->>BF: CceClinicalEventMapper: CloudEvent → box<br/>events[0].id = CloudEvent id (the HMIS's id)
    BF->>OC: POST /sygnal/caller/sygnal/event/report
    activate OC
    Note over BF: Timer: cce.emitter.beckn.onix.latency
    OC->>CCE: sygnal/event/report (signed by the HIE)<br/>to context.receiverUri + /sygnal/event/report
    CCE-->>OC: 202 ACK (up to ~17 s; new or duplicate)
    OC-->>BF: 202 ACK
    deactivate OC
    Note over BF: Counter: cce.emitter.beckn.cce.report.sent, result ack
    BF-->>IN: CollectorResponse (accepted)
    deactivate BF
    Note over IN: Counter: cce.emitter.events.forwarded
    IN-->>OHC: 202 (application/json+openhim)
    deactivate IN
    OHC-->>BW: 202 + ProcessedEventsResponse
    deactivate OHC
    Note over BW: Counter: cce.emitter.beckn.answered, result ack
    BW-->>OR: 202 ACK (request's messageId)
    deactivate BW
    OR-->>HM: 202 ACK
```

### 9.2 Sygnal Endpoint: Answer Mapping

```mermaid
flowchart TD
    A[POST /sygnal/event/report] --> J{JSON body?}
    J -->|No| N7[Nack 400<br/>SCH_INVALID_JSON]
    J -->|Yes| B{action = sygnal/event/report?}
    B -->|No| N0[Nack 400<br/>CTX_ACTION_MISMATCH]
    B -->|Yes| C{senderId a subscriber,<br/>receiverId = beckn.self.id,<br/>messageId present?}
    C -->|No| N1[Nack 400<br/>CTX_MISSING_FIELD or<br/>CTX_INVALID_FIELD]
    C -->|Yes| D{one event, id a UUID,<br/>CceClinicalEvent box,<br/>resource with resourceType?}
    D -->|No| N2[Nack 400<br/>SCH_REQUIRED_FIELD_MISSING,<br/>SCH_SCHEMA_VALIDATION_FAILED or<br/>SCH_TYPE_NOT_SUPPORTED]
    D -->|Yes| E[POST FHIR to OpenHIM channel]
    E --> F{Channel answer}
    F -->|2xx processed or 202| ACK[Ack 202]
    F -->|2xx status ignored| N3[Nack 400 BIZ_GENERIC_ERROR<br/>Counter: beckn.channel.ignored]
    F -->|2xx status skipped| N4[Nack 400 BIZ_GENERIC_ERROR<br/>Counter: beckn.channel.ignored]
    F -->|4xx, CCE BIZ_GENERIC_ERROR or SCH_*,<br/>pipeline refusal| N5[Nack 400 BIZ_GENERIC_ERROR<br/>text from body]
    F -->|4xx, CCE CTX_*,<br/>OpenHIM 401/403/404, other 2xx| N6[Nack 500 NET_INTERNAL_ERROR]
    F -->|408, 429, 5xx, timeout| S[Nack 500<br/>NET_DOWNSTREAM_UNAVAILABLE]

    style ACK fill:#c8e6c9
    style N3 fill:#ffe0b2
    style N4 fill:#ffe0b2
    style S fill:#ffcdd2
```

### 9.3 Sygnal Output: Answer Mapping

The Sygnal output returns the same results to `InboundEventService` as the collector path.

```mermaid
flowchart TD
    A[EventForwardingRouter] --> B{source output}
    B -->|collector| C[CollectorForwardingService<br/>unchanged]
    B -->|beckn| D[BecknForwardingService<br/>POST onix-openhim /sygnal/caller/sygnal/event/report]
    D --> E{CCE answer via onix-openhim}
    E -->|2xx, not NACK<br/>new or duplicate| OK[like collector 202 accepted<br/>/inbound → 202]
    E -->|4xx NACK BIZ_GENERIC_ERROR,<br/>CTX_*, SCH_*, other 2xx NACK| CL[CollectorClientException<br/>/inbound → 400 COLLECTOR_CLIENT_ERROR<br/>message: CCE code + text<br/>no retry]
    E -->|NACK NET_DOWNSTREAM_UNAVAILABLE,<br/>NET_INTERNAL_ERROR, 408, 429, 5xx, timeout| FW[CollectorForwardingException<br/>/inbound → 502 COLLECTOR_FORWARDING_ERROR<br/>no retry inside the route, the HMIS resends]

    style C fill:#e1f5fe
    style OK fill:#c8e6c9
    style CL fill:#ffe0b2
    style FW fill:#ffcdd2
```

### 9.4 No Callbacks

There is no `on_update` (or any other callback) on either hop. CCE's synchronous answer is passed back through `/inbound` and the OpenHIM channel to the HMIS in the same call. If the HIE times out first (`beckn.openhim-channel.timeout`, 25 s), the HMIS gets 500 `NET_DOWNSTREAM_UNAVAILABLE` and sends the event again. The resend carries the same event id, so CCE answers `Ack` for the duplicate.
