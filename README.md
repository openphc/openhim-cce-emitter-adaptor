# CCE Emitter Adaptor

## Overview

The **CCE Emitter Adaptor** is a generic [OpenHIM mediator](https://openhim.org/) built as a standard **Spring Boot 3.x** application. It is configurable for different source systems — currently configured for **eBUZIMA EMR**. It receives FHIR R4 resource payloads via OpenHIM Core (secondary route), wraps them in CloudEvents v1.0 envelopes, and forwards them to the CCE Collector. The input is any valid individual FHIR resource (e.g., Encounter, Observation). Bundle resources are silently ignored (out of scope for v1.0).

Clinical findings are **removed before the event is forwarded**: CCE records *that* a clinical step happened, for which patient, at which facility and when — not *what the finding was*. See [Data Dictionary §3.6](docs/data-dictionary.md) for the exact fields and rationale.

**No third-party mediator library is used** — the OpenHIM mediator contract (registration, heartbeat, response envelope) is implemented via custom Spring components.

## Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| Java | 21 LTS | Runtime |
| Spring Boot | 3.4.x | Application framework |
| Gradle | 8.x (Kotlin DSL) | Build tool |
| HAPI FHIR | 7.4.0 | FHIR R4 parsing & construction |
| Spring Retry | — | Retry with exponential backoff |
| Micrometer + Prometheus | — | Metrics & monitoring |
| WireMock | 3.9.x | Integration test stubs |

## Quick Start

```bash
# Prerequisites: Java 21, Docker

# 1. Start dependencies
docker compose up -d

# 2. Build
./gradlew clean build

# 3. Run
./gradlew bootRun --args='--spring.profiles.active=dev'

# 4. Test
curl -s http://localhost:8082/actuator/health | jq
# → { "status": "UP" }

# 5. Send a test event (FHIR Encounter)
curl -X POST http://localhost:8082/inbound \
  -H "Content-Type: application/json" \
  -H "X-OpenHIM-ClientID: ebuzima-emr-client" \
  -d '{"resourceType":"Encounter","id":"enc-001","status":"finished","subject":{"reference":"Patient/260225-0002-5501"},"period":{"start":"2026-02-25T08:00:00Z"}}'
# → 202 Accepted
```

## Architecture

```
eBUZIMA EMR → OpenHIM Core → Emitter Adaptor → CCE Collector → Kafka
                                    │
                                    ├── Source Adaptor (header-based routing)
                                    ├── FHIR resource → parse (ignore if Bundle)
                                    ├── CloudEvent v1.0 envelope
                                    ├── Forward to Collector (@Retryable)
                                    └── OpenHIM response wrapping
```

### Key Components

| Component | Description |
|-----------|-------------|
| `InboundEventController` | `@RestController` — receives POSTs from OpenHIM, delegates to `InboundEventService` |
| `InboundEventService` | Orchestrates pipeline: adapt → forward → wrap (+ metrics + MDC) |
| `SourceAdaptorService` | Config-driven source resolution; parses FHIR resources, builds CloudEvents |
| `CollectorForwardingService` | `@Retryable` — POSTs CloudEvents to Collector via `RestClient` (+ latency timer) |
| `ClinicalDataRedactor` | Strips clinical findings and the patient name from the payload before forwarding |
| `MediatorRegistrar` | Registers with OpenHIM Core on startup |
| `HeartbeatScheduler` | `@Scheduled` — periodic heartbeat for liveness |
| `OpenHimResponseWrapper` | Wraps responses in `application/json+openhim` format |


## Project Structure

```
src/main/java/org/openphc/cce/emitter/
├── config/            # FhirConfig, RestClientConfig, properties
├── controller/        # InboundEventController
├── openhim/           # MediatorRegistrar, HeartbeatScheduler, ResponseWrapper
├── adaptor/           # SourceAdaptorService (config-driven source routing)
├── cloudevents/       # CloudEventEnvelopeBuilder, EventIdGenerator
├── fhir/              # FhirResourceParser, PatientIdExtractor
├── redaction/         # ClinicalDataRedactor (clinical data minimisation)
├── service/           # InboundEventService (pipeline orchestration), CollectorForwardingService
├── model/             # DTOs (CloudEventDto, InboundRequest, ProcessedEventsResponse, etc.)
└── exception/         # Custom exceptions + GlobalExceptionHandler
```

## Testing

### Unit Tests

```bash
./gradlew test
```

### Integration Tests

Integration tests boot the full Spring context with a WireMock-stubbed Collector:

| Test Class | Scope |
|-----------|-------|
| `FullPipelineIntegrationTest` | End-to-end: FHIR resource → CloudEvent → Collector |
| `RetryIntegrationTest` | Retry on 5xx, no retry on 4xx, retry-then-succeed |
| `ActuatorMetricsIntegrationTest` | Health probes, Prometheus, custom metrics |

All integration tests use `@ActiveProfiles("integration")` with `application-integration.yml`, which configures a random server port, fast retry backoff, and disabled heartbeat.

```bash
# Run integration tests only
./gradlew test --tests "org.openphc.cce.emitter.integration.*"

# Run all tests
./gradlew test
```

## Docker

### Build Image

```bash
docker build -t cce-emitter-adaptor .
```

### Local Development Stack

```bash
# Start OpenHIM Core + Console, MongoDB, WireMock Collector stub
docker compose up -d

# Run the adaptor locally against the stack
./gradlew bootRun --args='--spring.profiles.active=dev'
```

| Service | Port | URL |
|---------|------|-----|
| OpenHIM Console | 9000 | http://localhost:9000 |
| OpenHIM Core API | 8080 | https://localhost:8080 |
| OpenHIM HTTP Channel | 5000 | http://localhost:5000 |
| WireMock Collector | 5055 | http://localhost:5055 |
| MongoDB | 27017 | — |

## Documentation

| Document | Description |
|----------|-------------|
| [Adaptor Development Guide](cce-adaptor-guide/adaptor-development-guide.md) | How to build a new CCE emitter adaptor (any stack): CloudEvents contract, Gateway auth, Collector API, retries, testing. Standalone folder — zip `cce-adaptor-guide/` to share |
| [Architecture Overview](docs/architecture-overview.md) | System context, tech stack, package structure, processing pipeline, security, deployment, observability |
| [Developer Setup](docs/developer-setup.md) | Build, run, Docker, integration tests |
| [Data Dictionary](docs/data-dictionary.md) | Field definitions, configuration properties, metrics, MDC context |
| [API Reference](docs/api-reference.md) | All endpoints, request/response examples, Prometheus metrics |
| [Flow Diagrams](docs/flow-diagrams.md) | Mermaid sequence & flow diagrams (with metrics/MDC annotations) |
| [OpenHIM Channel Setup](docs/openhim-channel-setup.md) | Step-by-step guide to add the adaptor as a secondary route on an existing OpenHIM channel |
| [Monitoring & Alerting](docs/monitoring-alerting.md) | Prometheus alert rules, Grafana dashboard panels, log-based monitoring, threshold recommendations |
| [Release Notes v1.0.0](docs/release-notes-v1.0.0.md) | Production deployment guide, env vars, OpenHIM setup, smoke tests, rollback |

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/inbound` | POST | Inbound FHIR resource (adaptor auto-selected via headers) |
| `/actuator/health` | GET | Health status |
| `/actuator/prometheus` | GET | Prometheus metrics |

## License

Internal — OpenPHC / CCE Project