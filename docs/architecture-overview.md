# CCE OpenHIM Emitter Adaptor — Architecture Overview

## 1. Purpose

The Emitter Adaptor is a generic **OpenHIM mediator** built as a standalone **Spring Boot 3.x** application. It is configurable for different source systems — currently configured for **eBUZIMA EMR**. It is responsible for:

1. **Receiving** FHIR R4 resource payloads routed via OpenHIM Core (secondary route — not on the primary path)
2. **Parsing** the FHIR resource using HAPI FHIR (individual resources only — Bundle processing is out of scope for v1.0)
3. **Constructing** CloudEvents v1.0 envelopes with CCE-required fields and extensions
4. **Forwarding** the CloudEvents to the CCE Collector Service via `RestClient`

## 2. System Context

```
┌─────────────────────────────────────────────────────────────┐
│                    eBUZIMA EMR                                │
│            (Sends FHIR R4 resource payloads)                 │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    OpenHIM Core (RHIE)                        │
│   Channel routing, transaction logging, access control       │
│   ┌───────────────────────────────────────────────────────┐  │
│   │ Existing eBUZIMA Channel                              │  │
│   │   Primary route  → SHR / other mediators              │  │
│   │   Secondary route → CCE Emitter Adaptor ★             │  │
│   └───────────────────────────────────────────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │  HTTP (secondary route copy)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│           ★ CCE Emitter Adaptor (this service) ★             │
│   Spring Boot 3.4.x + HAPI FHIR 7.4.0                       │
│                                                              │
│  1. @RestController receives FHIR resource via POST /inbound │
│  2. Parse FHIR resource (ignore if Bundle)                    │
│  3. Build CloudEvents v1.0 envelope per resource             │
│  4. Forward via RestClient to CCE Collector                  │
│  5. Wrap response in OpenHIM mediator format                 │
└──────────────────────────┬──────────────────────────────────┘
                           │  HTTP POST (CloudEvents JSON)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              CCE Gateway (OAuth + routing)                    │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              CCE Collector Service                            │
│    Validate (type only) → Deduplicate → Publish to Kafka     │
└──────────────────────────┬──────────────────────────────────┘
                           │  Kafka: cce.events.inbound
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              CCE Compliance Service                           │
│    Match → Enroll → Complete Steps → Detect Deviations       │
└─────────────────────────────────────────────────────────────┘
```

## 3. Architecture Principles

| Principle | Application |
|-----------|-------------|
| **Spring Boot Standard** | Standard Spring Boot application — embedded Tomcat, DI, `@ConfigurationProperties`, Actuator health/metrics |
| **Stateless** | No local database; no session state; all context derived from inbound request |
| **Single Responsibility** | Generic adaptor configurable per source via `cce.emitter.sources` config — currently eBUZIMA |
| **Open/Closed** | Additional sources added by configuration changes to `cce.emitter.sources` — no code changes needed |
| **Idempotent Output** | Same source event always produces the same CloudEvents `id` — Collector handles dedup |
| **Fail-Fast** | Invalid payloads rejected immediately with descriptive errors |
| **Retry with Backoff** | Collector forwarding uses Spring Retry with exponential backoff on 5xx/timeout |

## 4. Technology Stack

| Concern | Technology | Version |
|---------|------------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.x |
| Build tool | Gradle (Kotlin DSL) | 8.x |
| HTTP server | Embedded Tomcat | (via Spring Boot) |
| REST endpoints | Spring Web (`@RestController`) | |
| HTTP client | Spring `RestClient` | (Spring 6.1+) |
| FHIR library | HAPI FHIR | 7.4.0 |
| JSON | Jackson | (via Spring Boot) |
| Retry | Spring Retry | |
| Health & metrics | Spring Boot Actuator + Micrometer + Prometheus | |
| Configuration | `application.yml` + `@ConfigurationProperties` | |
| Testing | JUnit 5, Spring Boot Test, WireMock | |

## 5. OpenHIM Mediator Integration

The OpenHIM mediator contract is implemented with plain Spring Boot components — no third-party mediator library.

### Custom Components

```
┌──────────────────────────────────────────────────────────┐
│           Spring Boot Application                         │
│                                                           │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ Embedded Tomcat   │  │ @RestController (InboundCtrl)│   │
│  │ port: 8082        │──│  POST /inbound               │   │
│  └──────────────────┘  └─────────────────────────────┘   │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ MediatorRegistrar │  │ HeartbeatScheduler           │   │
│  │ (startup via      │  │ (@Scheduled, periodic POST   │   │
│  │  @PostConstruct)  │  │  to /mediators/{urn}/hb)     │   │
│  └──────────────────┘  └─────────────────────────────┘   │
│  ┌──────────────────┐  ┌─────────────────────────────┐   │
│  │ RestClient        │  │ OpenHimResponseWrapper       │   │
│  │ (Collector +      │  │ (wraps response in openhim   │   │
│  │  Core API calls)  │  │  mediator format)            │   │
│  └──────────────────┘  └─────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────┐    │
│  │ Spring Boot Actuator                              │    │
│  │  /actuator/health, /actuator/prometheus            │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

### OpenHIM Lifecycle

| Phase | Mechanism | Description |
|-------|-----------|-------------|
| **Registration** | `MediatorRegistrar` (`@PostConstruct` or `ApplicationReadyEvent`) | POST mediator descriptor to OpenHIM Core `/mediators` (`defaultChannelConfig` = `[]` — no channel auto-provisioning) |
| **Heartbeat** | `HeartbeatScheduler` (`@Scheduled`) | Periodic POST to `/mediators/{urn}/heartbeat` for liveness |
| **Response Wrapping** | `OpenHimResponseWrapper` | Wraps `@RestController` responses in `application/json+openhim` envelope |

## 6. Package Structure

```
org.openphc.cce.emitter/
├── CceEmitterAdaptorApplication.java             # @SpringBootApplication entry point
│
├── config/                                        # Spring configuration
│   ├── FhirConfig.java                            #   @Bean FhirContext.forR4() singleton
│   ├── RestClientConfig.java                      #   @Bean RestClient for Collector + OpenHIM Core
│   ├── RetryConfig.java                           #   Spring Retry configuration
│   ├── OpenHimProperties.java                     #   @ConfigurationProperties for openhim.* (core + heartbeat)
│   ├── MediatorProperties.java                    #   @ConfigurationProperties for mediator.* (identity + endpoint)
│   ├── CollectorProperties.java                   #   @ConfigurationProperties for cce.collector.*
│   └── EmitterProperties.java                     #   @ConfigurationProperties for cce.emitter.* (source routing)
│
├── controller/                                    # Spring MVC controllers
│   └── InboundEventController.java                #   @RestController: POST /inbound
│
├── openhim/                                       # OpenHIM mediator integration
│   ├── MediatorRegistrar.java                     #   Registers mediator with OpenHIM Core on startup
│   ├── HeartbeatScheduler.java                    #   Periodic heartbeat to OpenHIM Core
│   ├── OpenHimResponseWrapper.java                #   Wraps responses in application/json+openhim
│   └── model/
│       ├── MediatorDescriptor.java                #   Registration JSON model
│       ├── HeartbeatRequest.java                  #   Heartbeat request model
│       └── OpenHimResponse.java                   #   Mediator response envelope model
│
├── adaptor/                                       # Source system adaptors
│   └── SourceAdaptorService.java                  #   Resolves source from config, FHIR parsing + CloudEvents building
│
├── cloudevents/                                   # CloudEvents envelope construction
│   ├── CloudEventEnvelopeBuilder.java             #   Builds CloudEvents v1.0 JSON
│   └── EventIdGenerator.java                      #   Deterministic ID from source + sourceEventId
│
├── fhir/                                          # FHIR utilities
│   ├── FhirResourceParser.java                    #   HAPI FHIR parse (uses FhirContext.forR4())
│   ├── FacilityIdExtractor.java                   #   Extract facility ID from any FHIR resource location field (Encounter, ServiceRequest, Procedure, Immunization, etc.)
│   └── PatientIdExtractor.java                    #   Extract patient UPID from FHIR resources
│
├── filter/                                        # Facility filter
│   ├── FacilityFilter.java                        #   Spring bean: enforceFilter() — throws on deny, increments counter
│   └── FacilityFilterProperties.java              #   @ConfigurationProperties("cce.emitter.facility-filter")
│
├── redaction/                                     # Clinical data minimisation
│   ├── ClinicalDataRedactor.java                  #   Strips clinical findings + patient name from the outbound payload
│   │                                              #   "*" rule + the type's own; entries are fields or paths ([] = array)
│   └── ClinicalDataRedactionProperties.java       #   @ConfigurationProperties("cce.emitter.redaction") — "*" rule + per-resourceType additions
│
├── service/                                       # Business logic
│   ├── InboundEventService.java                   #   Orchestrates pipeline: adapt → forward → wrap (+ metrics + MDC)
│   ├── CollectorForwardingService.java            #   @Retryable: POST to Collector via RestClient (+ latency timer)
│   └── CollectorTokenService.java                 #   OAuth2 client_credentials token management (Keycloak)
│
├── model/                                         # DTOs
│   ├── CloudEventDto.java                         #   CloudEvents v1.0 output DTO
│   ├── InboundRequest.java                        #   Wraps incoming HTTP body + headers
│   ├── SourceMetadata.java                        #   sourceIdentifier, facilityId, sourceEventId
│   ├── TransformationResult.java                  #   Per-event success/failure detail
│   ├── ProcessedEventsResponse.java               #   Typed success response body model
│   └── CollectorResponse.java                     #   Response DTO from Collector
│
├── exception/                                     # Custom exceptions
│   ├── FhirMappingException.java                  #   FHIR parsing failures → 422
│   ├── PatientIdNotFoundException.java            #   Patient UPID not extractable → 400
│   ├── FacilityFilterRejectedException.java       #   Facility filter denial → 403
│   ├── CollectorForwardingException.java          #   Retryable Collector errors (5xx/timeout) → 502
│   ├── CollectorClientException.java              #   Non-retryable Collector errors (4xx)
│   └── GlobalExceptionHandler.java                #   @ControllerAdvice for consistent error responses
```

**~31 source files** across 8 packages.

### Resources

```
src/main/resources/
├── application.yml                                # Base config (all profiles inherit)
├── application-dev.yml                            # Dev profile (heartbeat disabled)
├── application-prod.yml                           # Prod profile (env-var driven)
└── logback-spring.xml                             # Structured logging: dev (human-readable + MDC) / prod (JSON)
```

### Test Resources

```
src/test/
├── java/org/openphc/cce/emitter/
│   ├── integration/                               # Integration tests (@ActiveProfiles("integration"))
│   │   ├── FullPipelineIntegrationTest.java       #   End-to-end FHIR → CloudEvent → Collector WireMock
│   │   ├── RetryIntegrationTest.java              #   Retry behavior (503, 422, 400, eventual success)
│   │   └── ActuatorMetricsIntegrationTest.java    #   Health probes, Prometheus, custom metrics
│   └── ...                                        # Unit test packages mirror main structure
└── resources/
    ├── application-integration.yml                # Integration test profile (random port, WireMock, fast retry)
    ├── fhir/                                      # FHIR test fixtures
    │   ├── encounter-visit.json
    │   └── observation-lab.json
    └── ebuzima/                                   # eBUZIMA-specific test fixtures
        ├── fhir-bundle.json
        ├── fhir-encounter.json
        └── fhir-observation.json
```

## 7. Request Processing Pipeline

### 7.1 SourceAdaptorService

Single `@Component` that reads `cce.emitter.sources` config (sourceKey → clientId mapping), resolves the source system from request headers, and transforms FHIR R4 resources into CloudEvents.

### 7.2 Processing Steps

| Step | Component | Description |
|------|-----------|-------------|
| 1 | `InboundEventController` | Receives HTTP POST, creates `InboundRequest`, delegates to `InboundEventService`, serializes returned `OpenHimResponse` as `application/json+openhim` |
| 2 | `InboundEventService.process()` | Orchestrates the full pipeline (steps 3–6), returns `OpenHimResponse` |
| 3 | `SourceAdaptorService.resolveSource()` | Matches `X-OpenHIM-ClientID` / `X-Source-System` headers against configured sources |
| 4 | `SourceAdaptorService.adapt()` | Parses FHIR resource, extracts patient UPID + facility ID + clinical time from the **complete** resource, applies facility filter (throws 403 on denial), builds `List<CloudEventDto>` |
| 4a | `ClinicalDataRedactor.redact()` | Inside `CloudEventEnvelopeBuilder.build()`: strips clinical findings and the patient name from the payload that becomes CloudEvent `data`. Runs **after** all extraction in step 4, so it cannot affect routing, facility attribution or SLA timing |
| 5 | `CollectorForwardingService.forward()` | POSTs each CloudEvent to Collector via `RestClient`; `@Retryable` on 5xx |
| 6 | `OpenHimResponseWrapper.wrap()` | Wraps response + orchestration log in `application/json+openhim` format |

**Data minimisation boundary.** Step 4a is the point past which clinical findings no longer exist in the system. Everything downstream — the Collector's `inbound_event_log`, the Kafka topic, `compliance_event_log`, and the ClickHouse analytics mirror — stores only the minimised payload. CCE retains *that* a clinical step occurred, for whom, where and when; it no longer retains *what the finding was*. See §3.6 of the [Data Dictionary](data-dictionary.md) for the exact field lists and the rationale for what is kept.

## 8. External Interfaces

### 8.1 Inbound (from OpenHIM Core)

| Direction | Protocol | Endpoint | Content |
|-----------|----------|----------|---------|
| **IN** | HTTP POST | `/inbound` | FHIR R4 resource (source identified via headers) |

### 8.2 Outbound (to CCE Collector)

| Direction | Protocol | Endpoint | Content |
|-----------|----------|----------|---------|
| **OUT** | HTTP POST | `{collector-url}/v1/events` | CloudEvents v1.0 JSON with FHIR R4 payload |

### 8.3 Outbound (to OpenHIM Core)

| Direction | Protocol | Endpoint | Content |
|-----------|----------|----------|---------|
| **OUT** | HTTP POST | `{core-url}/mediators` | Registration on startup |
| **OUT** | HTTP POST | `{core-url}/mediators/{urn}/heartbeat` | Periodic heartbeat |

## 9. Configuration

```
┌─────────────────────────────────────┐
│ Highest Priority                     │
│                                      │
│  1. Environment Variables            │  ← SPRING_APPLICATION_JSON, --server.port
│  2. Profile-specific YAML            │  ← application-prod.yml
│  3. application.yml                  │  ← default config
│                                      │
│ Lowest Priority                      │
└─────────────────────────────────────┘
```

## 10. Error Handling Strategy

Errors are handled by `GlobalExceptionHandler` (`@ControllerAdvice`):

| Scenario | Action | HTTP Status |
|----------|--------|-------------|
| Unknown source system | Log debug + silently ignore | 200 OK (no processing) |
| Facility filter denied (facility not in configured list) | Log + reject | 403 with `FACILITY_FILTER_REJECTED` |
| FHIR resource unparseable | Log + reject | 422 with `FHIR_MAPPING_ERROR` |
| Patient UPID not extractable | Log + reject | 400 with `PATIENT_ID_NOT_FOUND` |
| Collector returns 400 | Log + return error | 400 (non-retryable) |
| Collector returns 422 | Log + return error | 422 (non-retryable) |
| Collector returns 200 (duplicate) | Log + return success | 200 (idempotent) |
| Collector returns 500 | Retry with backoff (max 3) | 500 if all retries exhausted |
| Collector unreachable | Retry with backoff (max 3) | 502 if all retries exhausted |

## 11. Security

| Concern | Mechanism |
|---------|-----------|
| **OpenHIM ↔ Mediator** | OpenHIM Core routes requests via existing eBUZIMA channel (secondary route); mediator trusts OpenHIM channel auth |
| **Mediator → OpenHIM Core API** | Basic auth (`root@openhim.org` / password) for registration + heartbeat |
| **Mediator → CCE Collector** | OAuth2 client credentials via Keycloak (`CollectorTokenService`). Fetches and caches access tokens automatically. Falls back to static Bearer token (`cce.collector.auth.token`) when Keycloak is not configured. Emitter authenticates independently with the CCE Gateway (separate trust boundary from inbound OpenHIM auth). |
| **TLS** | HTTPS connections configurable via Spring Boot `server.ssl.*` properties |
| **Clinical data minimisation** | `ClinicalDataRedactor` strips clinical findings and the patient's name (`subject.display` / `patient.display`) from every payload before it is forwarded. Rules are **per resource type**, because the same element differs in sensitivity: `code` is the diagnosis on `Condition`, the allergen on `AllergyIntolerance` and the test ordered on `ServiceRequest` (all removed), but the observation *type* on `Observation` (kept — protocol triggers match on it). All 10 resource types seen in production have an explicit rule. CCE never persists what the clinical finding was — only that the step occurred, for which patient (UPID), at which facility, and when. Controlled by `cce.emitter.redaction.*`; see §3.6 of the [Data Dictionary](data-dictionary.md). Redaction log lines record field **names** only, never their values. | Content nested inside structures that must be kept (e.g. `Encounter.hospitalization.dischargeDisposition`) is removed by a dotted path on that type's rule.

## 12. Deployment

| Aspect | Value |
|--------|-------|
| **Artifact** | `cce-emitter-adaptor-1.0.0-SNAPSHOT.jar` (Spring Boot fat JAR) |
| **Port** | 8082 |
| **Liveness** | `/actuator/health/liveness` |
| **Readiness** | `/actuator/health/readiness` |
| **Metrics** | `/actuator/prometheus` |
| **Key env vars** | `OPENHIM_CORE_HOST`, `CCE_COLLECTOR_URL`, `KEYCLOAK_HOST`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, `FACILITY_FILTER_IDS`, `REDACTION_ENABLED`, `SPRING_PROFILES_ACTIVE` |

### Docker

| Stage | Base Image | Purpose |
|-------|-----------|--------|
| `build` | `eclipse-temurin:21-jdk-jammy` | Compile + `bootJar` (tests skipped) |
| `runtime` | `eclipse-temurin:21-jre-jammy` | Run the fat JAR |

Security: runs as non-root `appuser`. Includes `HEALTHCHECK` via `/actuator/health/liveness`.

### Docker Compose (Local Development)

| Service | Image | Port(s) | Purpose |
|---------|-------|---------|--------|
| `mongo` | `mongo:7.0` | 27017 | OpenHIM backend datastore |
| `openhim-core` | `jembi/openhim-core:v8.4.3` | 5000, 5001, 8080 | OpenHIM Core (HTTP, HTTPS, API) |
| `openhim-console` | `jembi/openhim-console:v1.18.4` | 9000 | OpenHIM web admin UI |
| `collector-stub` | `wiremock/wiremock:3.9.0` | 5055 | WireMock stub for CCE Collector |

WireMock mappings in `wiremock/mappings/`. Dockerfile is sufficient for production — `docker-compose.yml` is a local development convenience.

## 13. Observability

> **See also:** [Data Dictionary — §7 Metrics Reference](data-dictionary.md#7-metrics-reference) and [§8 MDC Context Fields](data-dictionary.md#8-mdc-context-fields) for authoritative field-level definitions. [Monitoring & Alerting Guide](monitoring-alerting.md) for Prometheus alert rules, Grafana dashboards, and log-based monitoring.

### Custom Metrics (Micrometer)

Registered in `InboundEventService` and `CollectorForwardingService` via constructor-injected `MeterRegistry`.

| Metric | Type | Tags | Registered In | Description |
|--------|------|------|---------------|-------------|
| `cce.emitter.events.received` | Counter | `source`, `path` | `InboundEventService` | Total inbound events received |
| `cce.emitter.events.forwarded` | Counter | `source` | `InboundEventService` | Events successfully forwarded to Collector |
| `cce.emitter.events.duplicate` | Counter | — | `InboundEventService` | Duplicate events (Collector returned 200) |
| `cce.emitter.events.rejected` | Counter | — | `CollectorForwardingService` | Events rejected by Collector (4xx) |
| `cce.emitter.collector.latency` | Timer | — | `CollectorForwardingService` | Collector forwarding round-trip latency |
| `cce.emitter.collector.retries` | Counter | — | `CollectorForwardingService` | Retry attempts exhausted |
| `cce.emitter.events.filtered` | Counter | `source`, `facility`, `reason` | `FacilityFilter` | Events denied by facility filter (`reason`: `NOT_IN_ALLOWLIST`). Events with no facility ID pass through and are not counted. |
| `cce.emitter.events.redacted.total` | Counter | `resource_type` | `ClinicalDataRedactor` | Events from which at least one clinical field was removed (once per event, not per field) |
| `cce.emitter.redaction.path.mismatch.total` | Counter | `resource_type`, `path` | `ClinicalDataRedactor` | A configured nested redaction path matched nothing — that path is redacting nothing |

### Structured Logging (MDC)

`InboundEventService` populates SLF4J MDC per-event with `try/finally` to ensure cleanup:

| MDC Key | Source | Description |
|---------|--------|-------------|
| `correlationId` | `X-OpenHIM-TransactionID` or `X-Correlation-Id` header, or generated | Trace correlation ID |
| `source` | Resolved source key | Source system identifier (e.g., `"ebuzima"`) |
| `eventType` | FHIR `resourceType` | CloudEvents `type` field |
| `subject` | Patient UPID | Patient identifier for the event |

### Logback Configuration (`logback-spring.xml`)

| Profile | Format | Description |
|---------|--------|-------------|
| `!prod` (default/dev) | Human-readable with MDC | `%d [%thread] %-5level %logger [correlationId] [source] [eventType] [subject] - %msg` |
| `prod` | Pattern-based JSON | `{"timestamp":...,"level":...,"logger":...,"correlationId":...,"source":...,"eventType":...,"subject":...,"message":...}` |

> Production JSON logging uses pattern-based layout — no extra dependencies (e.g., logback-contrib) required.

### Prometheus

Spring Boot 3.4.x requires explicit Prometheus enablement in `application.yml`:

```yaml
management:
  endpoint:
    prometheus:
      enabled: true
  prometheus:
    metrics:
      export:
        enabled: true
```

These settings are in the base `application.yml` and carry through to all profiles via Spring Boot's additive YAML merging.

## 14. Non-Functional Requirements

| Requirement | Target |
|-------------|--------|
| **Availability** | 99.9% uptime |
| **Latency** | < 500ms end-to-end (receive → forward) |
| **Throughput** | 100 events/sec sustained |
| **Stateless** | No local database; all state in OpenHIM and CCE platform |
| **Retry** | Max 3 retries with exponential backoff for Collector calls |
| **Max payload** | 1 MB (matching Collector limit) |
