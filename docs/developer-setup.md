# CCE Emitter Adaptor — Developer Setup Guide

## 1. Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| **Java** | 21 LTS (Eclipse Temurin recommended) | `java --version` → `21.x` |
| **Gradle** | 8.x (via wrapper) | `./gradlew --version` → `8.x` |
| **Docker** | 24.x+ | `docker --version` |
| **Docker Compose** | 2.x (plugin) | `docker compose version` |
| **Git** | 2.x+ | `git --version` |
| **IDE** | IntelliJ IDEA / VS Code with Java Extension Pack | — |

> **Note:** Gradle Wrapper (`gradlew`) is included in the project — no global Gradle install is required.

## 2. Clone & Build

```bash
# Clone the repository
git clone <repo-url>
cd cce-compliance-sub_system/emitter-adaptor

# Build (skipping tests for first-time setup)
./gradlew clean build -x test

# Build with tests
./gradlew clean build

# Run tests only
./gradlew test
```

## 3. Project Structure

```
emitter-adaptor/
├── build.gradle.kts              # Gradle build (Kotlin DSL)
├── settings.gradle.kts           # Project settings
├── gradlew                       # Gradle wrapper (Unix)
├── gradlew.bat                   # Gradle wrapper (Windows)
├── Dockerfile                    # Multi-stage build (JDK build → JRE runtime)
├── .dockerignore                 # Docker build exclusions
├── docker-compose.yml            # Local dev stack (OpenHIM, Mongo, WireMock)
├── gradle/
│   └── wrapper/                  # Wrapper JAR + properties
├── wiremock/
│   └── mappings/                 # WireMock stub mappings for Docker Compose
│       └── collector-events-accepted.json
├── src/
│   ├── main/
│   │   ├── java/org/openphc/cce/emitter/
│   │   │   ├── CceEmitterAdaptorApplication.java
│   │   │   ├── config/
│   │   │   ├── controller/
│   │   │   ├── openhim/
│   │   │   ├── adaptor/
│   │   │   ├── cloudevents/
│   │   │   ├── fhir/
│   │   │   ├── service/
│   │   │   ├── model/
│   │   │   └── exception/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       └── logback-spring.xml
│   └── test/
│       ├── java/org/openphc/cce/emitter/
│       │   ├── integration/            # Integration tests
│       │   └── ...                     # Unit test packages
│       └── resources/
│           ├── application-integration.yml
│           ├── fhir/                   # FHIR test fixtures
│           └── ebuzima/                # eBUZIMA test fixtures
├── docs/
├── .github/
│   └── copilot-instructions.md
└── artifacts/
```

## 4. Gradle Build Configuration

### build.gradle.kts

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.openphc.cce"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val hapiFhirVersion = "7.4.0"

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring Retry
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework:spring-aspects")

    // HAPI FHIR
    implementation("ca.uhn.hapi.fhir:hapi-fhir-base:$hapiFhirVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:$hapiFhirVersion")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Lombok (optional but recommended)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

### settings.gradle.kts

```kotlin
rootProject.name = "cce-emitter-adaptor"
```

## 5. Application Configuration

### application.yml

```yaml
server:
  port: 8082
  servlet:
    context-path: /

spring:
  application:
    name: cce-emitter-adaptor
  profiles:
    active: dev

# Mediator Identity & Endpoint
mediator:
  urn: "urn:mediator:cce-emitter-adaptor"
  version: "1.0.0"
  name: "CCE Emitter Adaptor"
  endpoint:
    host: localhost
    path: /inbound
    type: http

# OpenHIM Core Configuration
openhim:
  core:
    host: localhost
    api-port: 8080
    username: root@openhim.org
    password: openhim-password
  heartbeat:
    enabled: true
    interval-seconds: 10

# CCE Collector Configuration
cce:
  collector:
    url: http://localhost:5001
    events-path: /v1/events
    timeout: 15000
    retry:
      max-attempts: 3
      backoff-ms: 1000
    auth:
      token: local-dev-token
      keycloak-host:
      realm:
      client-id:
      client-secret:
  emitter:
    patient-identifier-system: "http://openphc.org/identifier/upid"
    facility-filter:
      ids: ${FACILITY_FILTER_IDS:}  # Set via env var: FACILITY_FILTER_IDS=0234,0030 (comma-separated, empty = all pass)
    sources:
      ebuzima:
        client-id: ebuzima-emr-client  # OpenHIM client ID for eBUZIMA EMR

# Actuator & Metrics
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true
    prometheus:
      enabled: true
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
  prometheus:
    metrics:
      export:
        enabled: true
  metrics:
    tags:
      application: cce-emitter-adaptor

# Logging
logging:
  level:
    org.openphc.cce: DEBUG
    org.springframework.web: INFO
  pattern:
    console: "%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n"
```

> **Note:** `logback-spring.xml` overrides the console pattern with MDC fields for dev (human-readable) and prod (JSON) profiles. The `logging.pattern.console` in YAML is a fallback.

### application-dev.yml

```yaml
mediator:
  endpoint:
    host: localhost
    path: /inbound
    type: http

openhim:
  core:
    host: localhost
    api-port: 8080
    username: root@openhim.org
    password: openhim-password
  heartbeat:
    enabled: false

cce:
  collector:
    url: http://localhost:5001
    auth:
      token: dev-token
  emitter:
    patient-identifier-system: "http://openphc.org/identifier/upid"
    sources:
      ebuzima:
        client-id: ebuzima-emr-client

logging:
  level:
    org.openphc.cce: DEBUG
```

### application-prod.yml

```yaml
server:
  port: ${SERVER_PORT:8082}

mediator:
  urn: ${MEDIATOR_URN:urn:mediator:cce-emitter-adaptor}
  version: ${MEDIATOR_VERSION:1.0.0}
  name: ${MEDIATOR_NAME:CCE Emitter Adaptor}
  endpoint:
    host: ${MEDIATOR_ENDPOINT_HOST:emitter-adaptor}
    path: ${MEDIATOR_ENDPOINT_PATH:/inbound}
    type: ${MEDIATOR_ENDPOINT_TYPE:http}

openhim:
  core:
    host: ${OPENHIM_CORE_HOST}
    api-port: ${OPENHIM_CORE_API_PORT:8080}
    username: ${OPENHIM_USERNAME}
    password: ${OPENHIM_PASSWORD}
  heartbeat:
    enabled: true
    interval-seconds: ${OPENHIM_HEARTBEAT_INTERVAL:10}

cce:
  collector:
    url: ${CCE_COLLECTOR_URL}
    events-path: ${CCE_COLLECTOR_EVENTS_PATH:/v1/events}
    timeout: ${CCE_COLLECTOR_TIMEOUT:15000}
    retry:
      max-attempts: ${CCE_COLLECTOR_RETRY_MAX_ATTEMPTS:3}
      backoff-ms: ${CCE_COLLECTOR_RETRY_BACKOFF_MS:1000}
    auth:
      token: ${CCE_COLLECTOR_AUTH_TOKEN:}
      keycloak-host: ${KEYCLOAK_HOST:}
      realm: ${KEYCLOAK_REALM:cce}
      client-id: ${KEYCLOAK_CLIENT_ID:}
      client-secret: ${KEYCLOAK_CLIENT_SECRET:}
  emitter:
    sources:
      ebuzima:
        client-id: ${EBUZIMA_CLIENT_ID}

logging:
  level:
    org.openphc.cce: INFO
  pattern:
    console: '{"timestamp":"%d{ISO8601}","level":"%level","logger":"%logger","message":"%msg"}%n'

management:
  endpoint:
    health:
      show-details: never
```

## 6. Docker Compose (Local Development)

### docker-compose.yml

```yaml
version: "3.9"

services:
  # OpenHIM Core
  openhim-core:
    image: jembi/openhim-core:v8.4.3
    container_name: openhim-core
    ports:
      - "5000:5000"   # HTTP channel
      - "5001:5001"   # HTTPS channel
      - "8080:8080"   # Core API (HTTPS)
    environment:
      - mongo_url=mongodb://mongo:27017/openhim
      - mongo_atnaUrl=mongodb://mongo:27017/openhim
      - api_authenticationTypes=["local"]
    depends_on:
      - mongo

  # OpenHIM Console
  openhim-console:
    image: jembi/openhim-console:v1.18.4
    container_name: openhim-console
    ports:
      - "9000:80"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost"]
      interval: 30s
      timeout: 10s
      retries: 3

  # MongoDB (for OpenHIM)
  mongo:
    image: mongo:7.0
    container_name: openhim-mongo
    ports:
      - "27017:27017"
    volumes:
      - mongo-data:/data/db

  # CCE Collector Stub (WireMock)
  collector-stub:
    image: wiremock/wiremock:3.9.2
    container_name: cce-collector-stub
    ports:
      - "5055:8080"
    volumes:
      - ./wiremock:/home/wiremock
    command: --verbose

  # CCE Emitter Adaptor
  openhim-cce-emitter-adaptor:
    build: .
    container_name: openhim-cce-emitter-adaptor
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - SERVER_PORT=8081
      - OPENHIM_CORE_HOST=openhim-core
      - CCE_COLLECTOR_URL=http://host.docker.internal:8082
      - FACILITY_FILTER_IDS=0234,0030   # comma-separated; empty or omit = filter inactive

volumes:
  mongo-data:
```

> **Facility filter tip:** `FACILITY_FILTER_IDS` is the only env var needed to control which facility IDs are admitted. Update it and do a rolling restart — no rebuild required. Skipped events return `200 OK` with `status: "skipped"` so OpenHIM marks the transaction as Completed.

### WireMock Stub for Collector

Create `wiremock/mappings/collector-events.json`:

```json
{
  "request": {
    "method": "POST",
    "urlPattern": "/v1/events"
  },
  "response": {
    "status": 202,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "data": {
        "eventId": "{{randomValue type='UUID'}}",
        "status": "accepted",
        "correlationId": "corr-{{randomValue type='UUID'}}",
        "timestamp": "{{now}}"
      }
    },
    "transformers": ["response-template"]
  }
}
```

## 7. Running the Application

### Option A: IDE (IntelliJ / VS Code)

1. Open the project root as a Gradle project
2. Run `CceEmitterAdaptorApplication.main()` with `--spring.profiles.active=dev`
3. Verify: `curl http://localhost:8082/actuator/health`

### Option B: Gradle CLI

```bash
# Start with dev profile
./gradlew bootRun --args='--spring.profiles.active=dev'

# Or build and run the JAR
./gradlew bootJar
java -jar build/libs/cce-emitter-adaptor-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
```

### Option C: Docker

```bash
# Build Docker image
docker build -t cce-emitter-adaptor:latest .

# Start local dev stack (OpenHIM, MongoDB, WireMock)
docker compose up -d

# Run application against local OpenHIM
./gradlew bootRun --args='--spring.profiles.active=dev'

# Or run standalone container (production)
docker run -p 8082:8082 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e OPENHIM_CORE_HOST=openhim-core \
  -e CCE_COLLECTOR_URL=http://collector:8081 \
  -e KEYCLOAK_HOST=https://keycloak.cce.mdtlabs.org \
  -e KEYCLOAK_REALM=cce \
  -e KEYCLOAK_CLIENT_ID=<client-id> \
  -e KEYCLOAK_CLIENT_SECRET=<client-secret> \
  cce-emitter-adaptor:latest
```

## 8. Verifying the Setup

### Health Check

```bash
curl -s http://localhost:8082/actuator/health | jq
# Expected: {"status":"UP","groups":["liveness","readiness"]}
```

### Metrics

```bash
curl -s http://localhost:8082/actuator/prometheus | grep cce_emitter
```

### Send a Test Event

```bash
curl -X POST http://localhost:8082/inbound \
  -H "Content-Type: application/json" \
  -H "X-OpenHIM-ClientID: ebuzima-emr-client" \
  -H "X-Facility-Id: FAC-001" \
  -H "X-Source-Event-Id: enc-visit-001" \
  -d '{
    "resourceType": "Encounter",
    "id": "enc-visit-001",
    "status": "finished",
    "class": {"code": "AMB"},
    "subject": {"reference": "Patient/PAT-12345"},
    "period": {"start": "2026-02-25T08:00:00Z"}
  }'
```

Expected: `202 Accepted` with `application/json+openhim` response.

## 9. Useful Gradle Commands

| Command | Purpose |
|---------|---------|
| `./gradlew clean build` | Full build + tests |
| `./gradlew test` | Run tests only |
| `./gradlew bootRun` | Run application |
| `./gradlew bootJar` | Build executable JAR |
| `./gradlew dependencies` | Show dependency tree |
| `./gradlew dependencyInsight --dependency hapi-fhir-base` | Inspect specific dependency |
| `./gradlew tasks --all` | List all available tasks |
| `./gradlew jacocoTestReport` | Generate test coverage (if JaCoCo configured) |

## 10. IDE Setup

### IntelliJ IDEA

1. File → Open → select `emitter-adaptor/` folder
2. IntelliJ auto-detects `build.gradle.kts` and imports
3. Enable **Annotation Processing** (for Lombok): Settings → Build → Compiler → Annotation Processors → Enable
4. Set Project SDK to Java 21

### VS Code

1. Install "Extension Pack for Java" and "Spring Boot Extension Pack"
2. Open `emitter-adaptor/` folder
3. Java Language Server will auto-detect Gradle project
4. Run/Debug via **Spring Boot Dashboard** panel

## 11. Troubleshooting

| Problem | Fix |
|---------|-----|
| `./gradlew: Permission denied` | `chmod +x gradlew` |
| `FhirContext.forR4()` slow first call | Normal — HAPI initializes models on first use (~2s). Subsequent calls are instant. |
| OpenHIM registration fails | Check `openhim.core.host` and credentials. Non-fatal — mediator still functions. |
| Collector 404 | Verify `cce.collector.url` and `cce.collector.events-path` |
| Java 21 not found | Install Temurin 21: `sdk install java 21.0.5-tem` (SDKMAN) |

## 12. Testing

### Unit Tests

```bash
./gradlew test
```

Unit tests use JUnit 5 + Spring Boot Test. Located in `src/test/java/` mirroring the main package structure.

### Integration Tests

```bash
# Run integration tests only
./gradlew test --tests '*IntegrationTest'

# Run a specific integration test
./gradlew test --tests '*FullPipelineIntegrationTest'
```

Integration tests use `@ActiveProfiles("integration")` with `application-integration.yml`:
- Random `server.port: 0` for test isolation
- Programmatic `WireMockServer` + `@DynamicPropertySource` to inject Collector URL
- Heartbeat disabled, fast retry (100ms backoff)

| Test Class | Description |
|------------|-------------|
| `FullPipelineIntegrationTest` | End-to-end: FHIR → CloudEvent → Collector WireMock (happy path, source routing, Bundle ignore, duplicate handling, correlation ID) |
| `RetryIntegrationTest` | Retry behavior: 503→exhaustion→502, 422→no retry, retry→eventual success, 400→no retry |
| `ActuatorMetricsIntegrationTest` | Health probes, Prometheus scrape, custom metric registration (uses `TestRestTemplate`, not MockMvc) |

**FHIR Fixtures:** `src/test/resources/fhir/encounter-visit.json`, `observation-lab.json`

## 13. Running with Beckn (Proposal)

> **Status:** Built on branch `demo-beckn`, on by default (`beckn.enabled=true`; set `OPENHIM_EMITTER_BECKN_ENABLED=false` to turn it off). Full design: [Beckn Integration](beckn-integration.md).

By default nothing in this guide changes: `beckn.enabled` is `false` and every source uses `output: collector`, so events go from OpenHIM to the CCE Collector as above.

### 13.1 Switches

| Setting | Env var | Effect |
|---------|---------|--------|
| `beckn.enabled` | `OPENHIM_EMITTER_BECKN_ENABLED` | `true` registers `/beckn/bpp-webhook/{action}` and `/beckn/bap-webhook/{action}` and allows `output: beckn` |
| `cce.emitter.sources.<key>.output` | — (YAML) | `collector` or `beckn`, per source. Unset: follows `beckn.enabled` |

Both are needed for the full Beckn path. With `beckn.enabled=true` and all sources on `collector`, the Beckn endpoints work but every event still goes to the collector.

### 13.2 Dependencies

| Dependency | Local option |
|------------|--------------|
| `openhim-onix` (`onix-bap`, `onix-bpp`) | The HIE team's containers, published on host ports that do not clash with OpenHIM (5000, 5001, 8080) or this adaptor (8082). Or WireMock stubs (below) |
| OpenHIM channel for Beckn | A channel with `/inbound` as **primary** route ([OpenHIM Channel Setup §9](openhim-channel-setup.md#9-beckn-channel-proposal)). For a quick test, `beckn.openhim-channel.url` can point at `http://localhost:8082/inbound` directly; the answer is then the `application/json+openhim` envelope, which `OpenHimChannelClient` unpacks the same way OpenHIM Core does |
| CCE side (`cce-onix`, `cce-beckn-service`) | Not needed locally when `onix-bap` is stubbed |

WireMock stubs for the onix callers (in the existing WireMock container). They answer in the shape `cce-beckn-service` uses, and echo the request's `messageId` as the real answer does:

```json
{ "request":  { "method": "POST", "urlPath": "/bap/caller/update" },
  "response": { "status": 200, "transformers": ["response-template"],
                "headers": { "Content-Type": "application/json" },
                "body": "{ \"message\": { \"status\": \"ACK\", \"messageId\": \"{{jsonPath request.body '$.context.messageId'}}\" } }" } }
```

```json
{ "request":  { "method": "POST", "urlPath": "/bpp/caller/on_update" },
  "response": { "status": 200, "transformers": ["response-template"],
                "headers": { "Content-Type": "application/json" },
                "body": "{ \"message\": { \"status\": \"ACK\", \"messageId\": \"{{jsonPath request.body '$.context.messageId'}}\" } }" } }
```

To test CCE's other answers, swap the `/bap/caller/update` stub's status and body:

| CCE answer to test | Status | Body `message` |
|--------------------|--------|----------------|
| Refusal | 400 | `{ "status": "NACK", "messageId": "…", "error": { "code": "EVENT_REJECTED", "message": "Payload validation failed: …" } }` |
| Broken rule | 400 | `{ "status": "NACK", "messageId": "…", "error": { "code": "BAD_REQUEST", "message": "performance[0].id is longer than 50 characters" } }` |
| May pass | 500 | `{ "status": "NACK", "messageId": "…", "error": { "code": "COLLECTOR_UNAVAILABLE", "message": "The event could not be passed to CCE. Send it again later." } }` |
| Slow CCE (`"fixedDelayMilliseconds": 17000` on the stub) | 200 | `{ "status": "ACK", "messageId": "…" }`. Must still succeed with the timeouts in [Beckn Integration §8.3](beckn-integration.md#83-every-answer-returns-in-the-same-call) |

### 13.3 Local settings (`application-dev.yml`)

```yaml
beckn:
  enabled: true
  # schema-base-url: default, https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema
  onix:
    bap-caller-url: http://localhost:5055/bap/caller
    bpp-caller-url: http://localhost:5055/bpp/caller
    timeout: 25000
  self:
    # network-id: optional, goes into context.networkId
    bap-id: bap.openhim.example.org
    bap-uri: http://localhost:8082/beckn/bap-webhook
    bpp-id: bpp.openhim.example.org
    bpp-uri: http://localhost:8082/beckn/bpp-webhook
  cce:
    bpp-id: bpp.mdtlabs.org
    bpp-uri: https://cce.example.org/bpp/receiver
    contract-id: 9d4e2f6a-1b3c-4d5e-8f70-b1c2d3e4f5a6
    transaction-id: 4a7b9c2d-3e5f-4a1b-8c6d-7e8f9a0b1c2d
    commitment-file: classpath:beckn/commitment-cce.json   # copy of cce-beckn-service src/main/resources/commitment.json
  openhim-channel:
    url: http://localhost:5000/cce-beckn
    username: cce-beckn-endpoint
    password: dev-password
    timeout: 35000
  subscribers:
    - subscriber-id: bap.openmrs.example.org
      subscriber-uri: https://openmrs.example.org/bap/receiver
      commitment-file: classpath:beckn/commitment-hie-feed.json
      source-system: openmrs-kicukiro

cce:
  emitter:
    sources:
      openmrs-kicukiro:
        output: beckn
```

### 13.4 Send a Test `update` (as `onix-bpp` would)

```bash
curl -X POST http://localhost:8082/beckn/bpp-webhook/update \
  -H "Content-Type: application/json" \
  -d @src/test/resources/beckn/update-from-openmrs.json
# Expected: 200 Ack. WireMock shows POST /bap/caller/update (to CCE) and POST /bpp/caller/on_update (ACCEPTED)
```

The body is the sample in [Beckn Integration §3.6](beckn-integration.md#36-example-inbound-update).

| Check | Expected |
|-------|----------|
| Every answer | The body is `{ "message": { "status": "ACK" or "NACK", "messageId": <the request's>, … } }` |
| Unknown `bapId` or missing contract id | 400 `Nack` `BAD_REQUEST`; no call to the channel |
| Two `performance` entries | 400 `Nack` `BAD_REQUEST`; no call to the channel |
| `X-Source-System` names no source | Channel answers 200 `ignored` → 400 `Nack` `EVENT_REJECTED`; `cce_emitter_beckn_channel_ignored_total` goes up |
| WireMock `/bap/caller/update` returns 400 `EVENT_REJECTED` | `/inbound` answers 400 `COLLECTOR_CLIENT_ERROR` → 400 `Nack` `EVENT_REJECTED` with CCE's text; no `on_update` |
| WireMock `/bap/caller/update` returns 400 `BAD_REQUEST` | 500 `Nack` `HIE_ERROR` with CCE's text; no `on_update` |
| WireMock `/bap/caller/update` returns 500 | `/inbound` answers 502 → 500 `Nack` `UNAVAILABLE`; one call to WireMock (no retry); no `on_update` |
| `action` = `confirm` | 400 `Nack` `NOT_SUPPORTED` |

### 13.5 Send a Test `on_update` (as `onix-bap` would)

```bash
curl -X POST http://localhost:8082/beckn/bap-webhook/on_update \
  -H "Content-Type: application/json" \
  -d '{"context":{"version":"2.0.0","action":"on_update","bapId":"bap.openhim.example.org","bppId":"bpp.mdtlabs.org","transactionId":"4a7b9c2d-3e5f-4a1b-8c6d-7e8f9a0b1c2d","messageId":"c7e1d2f3-4a5b-4c6d-8e7f-9a0b1c2d3e4f","timestamp":"2026-11-13T08:00:37Z"},"message":{"contract":{"id":"9d4e2f6a-1b3c-4d5e-8f70-b1c2d3e4f5a6","performance":[{"id":"b2d6e8f0-1a3c-5e7f-9b1d-3f5a7c9e1b3d","status":{"code":"DUPLICATE"}}]}}}'
# Expected: 200 Ack with the same messageId; log line with DUPLICATE; cce_emitter_events_duplicate_total goes up.
# Also works when no update with this messageId was sent first: CCE's on_update may arrive before the Ack.
```
