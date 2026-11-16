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
