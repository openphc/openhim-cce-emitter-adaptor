# Building a CCE Emitter Adaptor — Development Guide

## 1. Purpose & Audience

This guide explains how to build a new **emitter adaptor** for the **Care Coordination Engine (CCE)** platform — a service that receives clinical events from a source system (EMR, lab system, CHW app, etc.), transforms them into **CloudEvents v1.0** envelopes, and forwards them through the **CCE Gateway Service** to the **CCE Collector Service**.

The [openhim-cce-emitter-adaptor](https://github.com/openphc/openhim-cce-emitter-adaptor/tree/release-1.0.0) is the **reference implementation**: a Spring Boot 3.x service that adapts FHIR R4 payloads from EMR source systems. Your adaptor may use any language or framework, and any inbound integration style (middleware route, direct HTTP, polling, message queue) — **only the outbound contract to the CCE platform is fixed**.

**Reference repositories:**

| Repository | Role |
|------------|------|
| [openhim-cce-emitter-adaptor](https://github.com/openphc/openhim-cce-emitter-adaptor/tree/release-1.0.0/docs) | Reference adaptor implementation + docs |
| [gateway-service](https://github.com/openphc/gateway-service) | CCE Gateway — authentication (Keycloak JWT) + routing |
| [cce-collector-service](https://github.com/openphc/cce-collector-service/tree/release-1.0.0/docs) | Collector — validation, deduplication, Kafka publishing |

## 2. Where an Adaptor Fits

```
┌───────────────────────────────────────────────────────────────┐
│                     Source System                              │
│        (EMR / lab system / CHW app / registry ...)             │
└──────────────────────────┬────────────────────────────────────┘
                           │  source-native payloads
                           │  (FHIR, HL7v2, custom JSON, ...)
                           ▼
┌───────────────────────────────────────────────────────────────┐
│              ★ YOUR EMITTER ADAPTOR ★                          │
│                                                                │
│  1. Receive / fetch events from the source system              │
│  2. Extract patient UPID, facility ID, source event ID         │
│  3. Build CloudEvents v1.0 envelope (deterministic id)         │
│  4. Authenticate: OAuth2 client_credentials → Keycloak JWT     │
│  5. POST to Gateway with retry/backoff                         │
└──────────────────────────┬────────────────────────────────────┘
                           │  HTTP POST /v1/events
                           │  Authorization: Bearer <JWT>
                           ▼
┌───────────────────────────────────────────────────────────────┐
│              CCE Gateway Service                               │
│   Spring Cloud Gateway — JWT validation (Keycloak realm),      │
│   role checks, routing to downstream services                  │
└──────────────────────────┬────────────────────────────────────┘
                           │
                           ▼
┌───────────────────────────────────────────────────────────────┐
│              CCE Collector Service                             │
│   Validate envelope → Validate payload → Deduplicate           │
│   → Publish to Kafka topic `cce.events.inbound`                │
│     (message key = subject / patient UPID)                     │
└──────────────────────────┬────────────────────────────────────┘
                           │
                           ▼
┌───────────────────────────────────────────────────────────────┐
│              CCE Compliance Service                            │
│   Match → Enroll → Complete Steps → Detect Deviations          │
└───────────────────────────────────────────────────────────────┘
```

The Collector is the **single point of entry** for all clinical events into the CCE platform. The Gateway sits in front of it and owns authentication and rate limiting — the Collector itself does not authenticate requests.

## 3. Adaptor Responsibilities (Contract Checklist)

Every adaptor, regardless of technology, must:

| # | Responsibility | Notes |
|---|----------------|-------|
| 1 | **Receive or fetch** events from the source system | Push (webhook/route) or pull (polling) — your choice |
| 2 | **Extract the patient UPID** | Becomes the CloudEvent `subject`. Reject events with no resolvable patient identifier |
| 3 | **Extract event metadata** | Facility ID, source event ID, clinical event time (where available) |
| 4 | **Build a CloudEvents v1.0 envelope** | See §5 — the exact contract the Collector validates |
| 5 | **Generate an idempotent event `id`** | Same source event → same `id`, always. See §5.3 |
| 6 | **Authenticate to the Gateway** | OAuth2 `client_credentials` → Keycloak JWT Bearer token. See §6 |
| 7 | **Forward with retry** | Retry 5xx/timeouts with exponential backoff; never retry 4xx. See §7 |
| 8 | **Treat duplicates as success** | Collector returns `200` (`status: "duplicate"`) idempotently — not an error |
| 9 | **Strip clinical findings before forwarding** | CCE stores *that* a clinical step happened — never *what the finding was*. Mandatory; see §5.6 |
| 10 | **Stay stateless** | No local persistence of event state; the Collector owns dedup, Kafka owns the stream |
| 11 | **Expose health + metrics** | Liveness/readiness probes, event counters, forwarding latency. See §10 |

## 4. Collector Events API — The Outbound Contract

Authoritative source: [cce-collector-service docs/api-reference.md](https://github.com/openphc/cce-collector-service/blob/release-1.0.0/docs/api-reference.md).

### 4.1 Endpoint

```
POST {gateway-url}/v1/events
Content-Type: application/json
Authorization: Bearer <keycloak-access-token>
```

- **Max payload size:** 1 MB (Collector-side, configurable via `CCE_COLLECTOR_MAX_PAYLOAD_SIZE`)
- **In deployed environments the adaptor always calls the Gateway URL**, never the Collector directly. In local dev you may point at a Collector instance or a WireMock stub (see §9).

### 4.2 Response Codes

| Status | Meaning | Body | Adaptor Action |
|--------|---------|------|----------------|
| `202 Accepted` | Event queued to Kafka | `{"data":{"eventId":"...","status":"accepted","correlationId":"...","timestamp":"..."}}` | Success |
| `200 OK` | Duplicate event (idempotent) | `{"data":{"eventId":"...","status":"duplicate",...}}` | Success — count as duplicate, do **not** retry |
| `400 Bad Request` | CloudEvents envelope validation failed | `{"error":{"code":"VALIDATION_ERROR","message":"..."}}` | Do not retry — fix mapping |
| `422 Unprocessable Entity` | FHIR payload validation failed | `{"error":{"code":"PAYLOAD_VALIDATION_ERROR","message":"..."}}` | Do not retry — fix payload |
| `401 / 403` | Missing/invalid/expired JWT, or missing role (Gateway) | — | Refresh token once; if still failing, alert — config problem |
| `500 / 502 / 503` | Kafka publish failure or transient error | `{"error":{"code":"KAFKA_PUBLISH_FAILURE\|INTERNAL_ERROR",...}}` | Retry with backoff (max 3) |

### 4.3 Deduplication

The Collector deduplicates on the compound key **(`id`, `source`)** over a configurable lookback window (default **30 days**, `CCE_COLLECTOR_DEDUP_LOOKBACK_DAYS`). This is why the deterministic event `id` (§5.3) matters: a retried or replayed source event maps to the same `id` and is safely absorbed as a `200 duplicate`.

## 5. The CloudEvents v1.0 Envelope

### 5.1 Required Fields

| Field | Type | Constraint | How to populate |
|-------|------|-----------|-----------------|
| `specversion` | string | Must be `"1.0"` | Constant |
| `id` | string | Non-blank, ≤ 50 chars | Deterministic UUID — see §5.3 |
| `source` | string | Non-blank | Your source-system identifier (e.g. `"myemr"`). Fixed per adaptor/source; part of the dedup key |
| `type` | string | Non-blank | Event type. For FHIR payloads the reference adaptor uses the `resourceType` as-is (e.g. `"Encounter"`) |
| `subject` | string | Non-blank | **Patient UPID** — extracted from the payload (see §5.4) |
| `datacontenttype` | string | `application/fhir+json` or `application/json` | `application/fhir+json` for FHIR R4 payloads (full HAPI FHIR validation at Collector); `application/json` for non-FHIR (basic JSON validity only) |
| `data` | object | Non-null | The event payload itself — **with clinical findings removed**. See §5.6 |

### 5.2 Optional / Extension Fields

| Field | Purpose |
|-------|---------|
| `time` | ISO-8601 clinical event time. Server fills with `received_at` if omitted — so send it when the source provides it |
| `correlationid` | Trace correlation ID. Propagate from inbound context if available; auto-generated by the Collector if absent |
| `facilityid` | Facility identifier. Send bare IDs — strip any `Location/` or `Organization/` reference prefix |
| `sourceeventid` | The source system's internal event ID — input to deterministic `id` generation |
| `protocolinstanceid`, `protocoldefinitionid`, `actionid` | Set downstream by the Compliance Service — adaptors do **not** populate these |

Unknown extra fields are captured by the Collector as CloudEvents extension attributes.

### 5.3 Deterministic Event ID (Idempotency)

**Rule: the same source event must always produce the same CloudEvent `id`.**

The reference implementation ([EventIdGenerator.java](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/cloudevents/EventIdGenerator.java)):

- If a `sourceEventId` is available → **UUID v5** (name-based, SHA-1, RFC 4122 §4.3) over the name `"{sourceIdentifier}:{sourceEventId}"`, using the well-known DNS namespace UUID `6ba7b810-9dad-11d1-80b4-00c04fd430c8`.
- If no `sourceEventId` is available → random UUID v4 (dedup protection is lost for that event — prefer sources that provide stable event IDs).

Any language can reproduce this: UUID v5 with the DNS namespace is standard (`uuid.uuid5(uuid.NAMESPACE_DNS, name)` in Python, `uuidv5` in Node, etc.).

### 5.4 Subject (Patient UPID) Extraction

For FHIR R4 sources, the reference extraction rules ([PatientIdExtractor.java](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/fhir/PatientIdExtractor.java)):

| Resource Type | Subject source |
|--------------|----------------|
| `Patient` | `identifier[]` entry whose `system` matches the configured UPID system URI (`http://openphc.org/identifier/upid`), falling back to `Patient.id` |
| `RelatedPerson` | `patient` reference |
| `Encounter`, `Observation`, `Condition`, `MedicationRequest`, `MedicationDispense`, `ServiceRequest`, `Procedure`, `DiagnosticReport` | `subject` reference |
| `EpisodeOfCare`, `Immunization` | `patient` reference |

Strip the `Patient/` prefix from references. For **FHIR payloads**, the Collector cross-checks the payload's patient reference against the envelope `subject` — they must match, or the event is rejected with `422`.

### 5.5 Example Envelope

```json
{
  "specversion": "1.0",
  "id": "a1b2c3d4-e5f6-5890-abcd-ef1234567890",
  "source": "myemr",
  "type": "Encounter",
  "subject": "UPID-PAT-12345",
  "time": "2026-02-25T08:00:00.000Z",
  "datacontenttype": "application/fhir+json",
  "facilityid": "FAC-0001",
  "sourceeventid": "enc-uuid-visit-001",
  "correlationid": "txn-8842",
  "data": {
    "resourceType": "Encounter",
    "id": "enc-uuid-visit-001",
    "status": "finished",
    "subject": { "reference": "Patient/UPID-PAT-12345" },
    "period": { "start": "2026-02-25T08:00:00Z" }
  }
}
```

### 5.6 Data Minimisation — Strip Clinical Findings

**This is mandatory, not optional.** CCE is a care *coordination* engine. It needs to know **that** a
clinical step happened, **when**, for **which patient**, at **which facility** — it never needs to
know **what the clinical finding was**. Everything you put in `data` is persisted: in the
Collector's `inbound_event_log`, on the Kafka topic, in `compliance_event_log`, and in the ClickHouse
analytics mirror. Anything you send is stored, so strip findings **before** you forward.

Do the stripping **after** you have extracted the UPID, facility ID and clinical event time from the
complete payload — otherwise you lose the values you need for `subject`, `facilityid` and `time`.

#### Remove these

Common to every resource type:

```
valueQuantity, valueCodeableConcept, valueString, valueBoolean, valueInteger,
valueRange, valueRatio, valueSampledData, valueTime, valueDateTime, valuePeriod,
valueAttachment, component, note, text, interpretation, dataAbsentReason,
bodySite, method, specimen, referenceRange, contained, severity, stage, evidence,
dosageInstruction, reasonCode, reasonReference, orderDetail, patientInstruction
```

Plus, per resource type — because the same element means different things:

| Resource | Also remove | Why |
|----------|-------------|-----|
| `Condition` | `code` | The diagnosis — *"Type 2 diabetes mellitus"* |
| `AllergyIntolerance` | `code`, `reaction` | The allergen — *"allergy on aminophyline"* |
| `ServiceRequest` | `code` | The test ordered — *"a1-Acid Glycoprotein"* |
| `Procedure` | `code`, `outcome`, `complication` | The procedure performed and its result |
| `MedicationRequest` | `medicationCodeableConcept`, `medicationReference`, `dispenseRequest` | The drug, quantity and refills |
| `MedicationDispense` | `medicationCodeableConcept`, `medicationReference`, `quantity` | The drug and how much |
| `MedicationAdministration` | `medicationCodeableConcept`, `medicationReference`, `dosage`, `supportingInformation` | The drug and the amount given |
| `ImagingStudy` | `conclusion`, `conclusionCode`, `description`, `procedureCode`, `series`, `modality` | The radiology findings |
| `Immunization` | `vaccineCode` | The vaccine given |
| `Observation` | `component` only | **Keep `code`** — see below |

Also remove the patient's name wherever it appears: **`subject.display`** and **`patient.display`**
(some resources use `patient` rather than `subject`). Keep `subject.reference` /
`patient.reference` — that carries the UPID CCE keys on.

#### Keep `code` on Observation

This is the one that catches people out. On `Observation`, `code` is the observation **type**
(LOINC `8716-3` "Vital signs", `33747-0` "Chief Complaints") — not the finding. Protocol triggers
match on it, so removing it stops compliance tracking working. Remove the `value[x]` and
`component[]`; keep the `code`.

```jsonc
// before — the reading is the finding
{ "resourceType": "Observation",
  "code":  { "coding": [ { "code": "vs_systole", "display": "Vs Systole" } ] },
  "valueCodeableConcept": { "coding": [ { "code": "vs_systole", "display": 100 } ] } }

// after — CCE knows a systolic BP was taken, not that it was 100
{ "resourceType": "Observation",
  "code":  { "coding": [ { "code": "vs_systole", "display": "Vs Systole" } ] } }
```

#### Keep everything CCE matches on

| Keep | Used for |
|------|----------|
| `resourceType`, `category`, `type`, `class`, `status`, `clinicalStatus`, `verificationStatus`, `serviceType`, `intent`, `identifier` | Protocol trigger matching |
| `effectiveDateTime`, `issued`, `period`, `occurrenceDateTime`, `authoredOn`, `onsetDateTime`, `recordedDate`, `whenHandedOver`, `performedDateTime` | SLA / deviation timing |
| `subject.reference` / `patient.reference` | Patient identity (UPID) |
| `location`, `locationReference`, `hospitalization.origin`, `extension` (`source-facility`) | Facility attribution |
| `performer`, `requester`, `asserter`, `participant` | Practitioner shown in the dashboard |
| `encounter` | Links steps within one visit |

#### Reaching nested content

Most findings sit at the resource root. Where one is nested inside a structure you must keep,
use an explicit path rather than widening the root list. Scope it to the resource type — a global
path applies to every type, which is rarely what you mean:

```yaml
# Applied to EVERY resource
- resource-type: "*"
  fields: >-
    valueQuantity, valueString, ... note, text,
    subject.display, patient.display

# Each type lists only what it ADDS to the above
- resource-type: Encounter
  # hospitalization is KEPT (hospitalization.origin is a facility source),
  # but its discharge outcome is clinical
  fields: diagnosis, hospitalization.dischargeDisposition
```

Writing the shared content once, rather than repeating it in every type's list, is what keeps the
file reviewable — and removes the chance of the copies drifting apart.

Make an array step explicit — `reaction[].manifestation`, not `reaction.manifestation`. A path that
walks into an array without the marker matches nothing.

Prefer removing a **whole array** at the root over pathing into it: `component` on `Observation`
drops every sub-observation's `value[x]` in one step. Reach for a path only when part of the
structure must survive.

#### Three traps

1. **Do not write a recursive scrub.** Removing `valueString` everywhere also removes it from
   `extension[]`, which is how `source-facility` attribution works, and strips the `coding`/`display`
   elements the dashboard renders. Remove named fields at the **resource root** plus a short list of
   explicit nested paths — nothing deeper.
2. **A path that matches nothing must not fail quietly.** This is the worst failure mode in the
   whole control: the config looks right, no error appears, and clinical data keeps flowing. Whoever
   added the path believes the issue is closed. Detect the mismatch at runtime, log a warning naming
   the path *and its corrected form*, and increment a counter you can alert on.
3. **The result must still be valid FHIR.** When `datacontenttype` is `application/fhir+json` the
   Collector re-parses the body with HAPI and rejects malformed payloads as `INVALID_FHIR`. Removing
   optional elements is safe; do not remove `resourceType`.

#### Make it configurable and observable

Drive the field lists from configuration rather than hard-coding them, so a rule can be adjusted
without a rebuild — and put the *full* list in the config file, not just a master switch. A rule that
lives only in code cannot be audited by the people who have to sign it off.

Emit a counter each time an event is redacted (see §10) and alert if events keep arriving while that
counter stops incrementing — that is how you detect redaction silently breaking.

Then test the **shipped configuration**, not just the redaction code. A unit test proves the engine
honours the rules it is handed; it says nothing about the rules you actually deploy. Bind the real
config file in a test and assert on what comes out. Verify that test can fail — break a rule
deliberately and confirm it goes red.

Never log the values you strip. Log field **names** only.

## 6. Authenticating Through the Gateway

The [gateway-service](https://github.com/openphc/gateway-service) is a **Spring Cloud Gateway** that validates credentials against a **Keycloak** realm before routing to downstream services. Two credential types are accepted:

### 6.1 Bearer JWT (recommended for adaptors)

The adaptor obtains an access token from Keycloak using the **OAuth2 `client_credentials` grant** and sends it as `Authorization: Bearer <token>` on every `/v1/events` call. The Gateway's `JwtValidationFilter` verifies the token's **issuer** (configured realm), **audience**, and **roles** (claim path `realm_access.roles`) against the route's required permissions.

**Onboarding steps (per adaptor, done by the platform/Keycloak admin):**

1. Create a **confidential client** in the CCE Keycloak realm for your adaptor (e.g. `myemr-emitter`), with **Service Accounts enabled** (client_credentials).
2. Grant the service account the role required by the events route (e.g. `EMITTER_INBOUND_WRITE`).
3. Share the `client-id` / `client-secret` with the adaptor deployment (env vars / secrets manager — never in source control).

**Token fetch (what your adaptor does at runtime):**

```bash
curl -X POST "https://{keycloak-host}/realms/{realm}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id={your-client-id}" \
  -d "client_secret={your-client-secret}"
# → { "access_token": "eyJ...", "expires_in": 300, ... }
```

**Cache the token** and refresh it before expiry (the reference implementation refreshes with a safety margin in [CollectorTokenService.java](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/service/CollectorTokenService.java)) — do not fetch a new token per event. On a `401`, refresh once and retry the request.

### 6.2 Basic Auth Exchange (fallback for non-JWT clients)

The Gateway can optionally accept `Authorization: Basic <base64(user:password)>` and exchange it for a Keycloak JWT via the resource-owner-password grant (controlled by `BASIC_AUTH_ENABLED`, `BASIC_AUTH_TOKEN_URI`, `BASIC_AUTH_CLIENT_ID`, `BASIC_AUTH_CLIENT_SECRET`; disabled by default). This exists to bridge legacy clients — new adaptors should implement the JWT flow (§6.1).

### 6.3 Gateway Route for the Collector

Routes are declared in the Gateway's `application.yml`. A route forwarding events to the Collector follows the same pattern as the existing routes:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: cce-collector-service
          uri: ${COLLECTOR_SERVICE_URI}
          predicates:
            - Path=/v1/events/**
          filters:
            - name: JwtValidationFilter
```

If the deployment's Gateway does not yet route `/v1/events`, request the route from the platform team as part of adaptor onboarding.

## 7. Forwarding, Retries & Error Mapping

The reference behavior ([CollectorForwardingService.java](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/service/CollectorForwardingService.java)), which every adaptor should reproduce:

| Collector/Gateway response | Adaptor behavior |
|---------------------------|------------------|
| `202` accepted | Success — increment forwarded counter |
| `200` duplicate | Success — increment duplicate counter, never retry |
| `400`, `422` | **No retry.** Log with correlation ID, increment rejected counter, surface the error to the source-side flow |
| `401` | Refresh token once, retry once; then fail |
| `5xx`, connect/read timeout | **Retry with exponential backoff, max 3 attempts** (reference: 1s initial backoff, timeout 5s). Exhausted retries → surface as a gateway/forwarding error (`502` in the reference adaptor) |

Additional rules:

- **One CloudEvent per POST** — the events endpoint takes a single envelope, not a batch.
- **Keep payloads under 1 MB** — reject or truncate oversized source events before forwarding.
- **Propagate `correlationid`** end-to-end so a source event can be traced through Gateway → Collector → Kafka → Compliance logs.

## 8. Step-by-Step: Building a New Adaptor

### Step 1 — Onboarding prerequisites (platform team)

- [ ] Keycloak confidential client + service account role for your adaptor (§6.1)
- [ ] Gateway route to the Collector exists in the target environment (§6.3)
- [ ] Agree on your `source` identifier string (unique per source system — part of the dedup key)
- [ ] Agree on `type` values your adaptor will emit (FHIR `resourceType` values for FHIR sources)

### Step 2 — Scaffold the service

Any HTTP-capable stack works. The reference stack: Java 21 + Spring Boot 3.4.x + Gradle, HAPI FHIR 7.4.0 (FHIR parsing), Spring Retry, Micrometer/Prometheus. Keep the service **stateless** — no database.

### Step 3 — Implement source-side ingestion

Whatever fits your source system: an inbound webhook endpoint, a middleware secondary route (§12), a polling loop, or a queue consumer. Decide your source-identification strategy (the reference adaptor matches a client-ID header against configured client IDs, with `X-Source-System` as fallback, and **silently ignores non-matching traffic with `200 OK`** since a shared route sees all channel traffic).

### Step 4 — Implement extraction & mapping

- Patient UPID → `subject` (§5.4) — reject events where it cannot be resolved
- Facility ID → `facilityid` (bare ID, no reference prefix)
- Source event ID → `sourceeventid`
- Clinical event time → `time`
- Payload → `data`, with the correct `datacontenttype`

Extract all of the above from the **complete** payload before redacting (Step 5a) — once clinical
fields are stripped, the values behind `subject`, `facilityid` and `time` may no longer be present.

### Step 5 — Implement the envelope builder + deterministic ID

Follow §5. Reference: [CloudEventEnvelopeBuilder.java](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/cloudevents/CloudEventEnvelopeBuilder.java) and [EventIdGenerator.java](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/cloudevents/EventIdGenerator.java).

### Step 5a — Strip clinical findings

Apply the §5.6 rules to `data` as the last thing the envelope builder does, after extraction. Drive
the field lists from configuration. Verify against real payloads from your source system — not
hand-written fixtures — and assert two things: the finding is gone, and every field in the
§5.6 "keep" table survives. Reference: [ClinicalDataRedactor.java](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/redaction/ClinicalDataRedactor.java).

### Step 6 — Implement the forwarding client

Token service (fetch + cache + refresh, §6.1) and the POST to `{gateway-url}/v1/events` with the retry/error mapping from §7.

### Step 7 — Add observability

Health probes, Prometheus metrics, and structured logs with correlation IDs (§10).

### Step 8 — Test

Unit tests for extraction/mapping/ID determinism, integration tests against a stubbed Collector, then end-to-end against the dev Gateway (§9).

### Step 9 — Deploy

Containerize (non-root user, `HEALTHCHECK`), externalize all environment-specific config (§11), wire liveness/readiness probes and Prometheus scraping.

## 9. Local Development & Testing

### 9.1 Stub the Collector with WireMock

The reference repo ships a WireMock stub ([wiremock/mappings/collector-events-accepted.json](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/wiremock/mappings/collector-events-accepted.json)) that answers `POST /v1/events` with a `202`:

```json
{
  "request": { "method": "POST", "url": "/v1/events" },
  "response": {
    "status": 202,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "data": {
        "eventId": "stub-evt-001",
        "status": "accepted",
        "correlationId": "stub-corr-001",
        "timestamp": "2026-02-25T08:00:00Z"
      }
    }
  }
}
```

Add companion stubs for `200 duplicate`, `400`, `422`, and `503` to exercise your retry and error-mapping logic. The reference integration tests to model yours on: `FullPipelineIntegrationTest` (happy path), `RetryIntegrationTest` (retry on 5xx, no retry on 4xx), `ActuatorMetricsIntegrationTest` (probes + metrics).

### 9.2 End-to-end smoke test against a dev Gateway

```bash
# 1. Get a token
TOKEN=$(curl -s -X POST "https://{keycloak-host}/realms/{realm}/protocol/openid-connect/token" \
  -d "grant_type=client_credentials&client_id={client-id}&client_secret={client-secret}" \
  | jq -r .access_token)

# 2. Post a CloudEvent through the Gateway
curl -X POST "https://{gateway-host}/v1/events" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "specversion": "1.0",
    "id": "test-'"$(uuidgen)"'",
    "source": "myemr",
    "type": "Encounter",
    "subject": "UPID-TEST-0001",
    "datacontenttype": "application/fhir+json",
    "data": {
      "resourceType": "Encounter",
      "id": "enc-test-001",
      "status": "finished",
      "subject": {"reference": "Patient/UPID-TEST-0001"},
      "period": {"start": "2026-02-25T08:00:00Z"}
    }
  }'
# → 202 {"data":{"eventId":"...","status":"accepted",...}}

# 3. Re-post the same body (same id) → 200 {"data":{...,"status":"duplicate",...}}
```

**Verification checklist:** `202` on first send; `200 duplicate` on resend of the same `id`+`source`; `400` on a missing required field (e.g. drop `subject`); `422` on a FHIR payload whose patient reference doesn't match `subject`; `401` without a token.

## 10. Observability Requirements

Match the reference adaptor's conventions so dashboards and alerts stay uniform across adaptors ([monitoring-alerting.md](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/docs/monitoring-alerting.md)):

| Metric (Micrometer name) | Type | Purpose |
|--------------------------|------|---------|
| `cce.emitter.events.received` | Counter (`source`, `path`) | Inbound events seen |
| `cce.emitter.events.forwarded` | Counter (`source`) | Successfully accepted by Collector |
| `cce.emitter.events.duplicate` | Counter | Collector `200 duplicate` responses |
| `cce.emitter.events.rejected` | Counter | Collector 4xx rejections |
| `cce.emitter.collector.latency` | Timer | Forwarding round-trip latency |
| `cce.emitter.collector.retries` | Counter | Retry exhaustion |
| `cce.emitter.events.redacted.total` | Counter (`resource_type`) | Events with clinical fields removed (§5.6). Alert if events keep arriving while this stops incrementing — that means redaction has silently stopped. |
| `cce.emitter.redaction.path.mismatch.total` | Counter (`resource_type`, `path`) | A configured nested path matched nothing — it is redacting nothing. Alert on non-zero. |

Logging: structured (JSON in prod), with `correlationId`, `source`, `eventType`, `subject` in the logging context (MDC or equivalent) on every per-event log line. Expose `/actuator/health/liveness`, `/actuator/health/readiness` (or equivalents) and a Prometheus scrape endpoint.

## 11. Configuration Reference (Adaptor Side)

Modeled on the reference adaptor's [application.yml](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/resources/application.yml) — externalize per environment:

```yaml
cce:
  collector:
    url: https://gateway.example.org      # CCE Gateway base URL (NOT the Collector directly)
    events-path: /v1/events
    timeout: 5000                          # ms
    retry:
      max-attempts: 3
      backoff-ms: 1000                     # exponential backoff base
    auth:
      keycloak-host: https://keycloak.example.org
      realm: cce
      client-id: myemr-emitter
      client-secret: ${KEYCLOAK_CLIENT_SECRET}
      token:                               # static Bearer fallback for local dev only
  emitter:
    patient-identifier-system: "http://openphc.org/identifier/upid"
    facility-filter:
      ids: []                              # optional allowlist of facility IDs; empty = allow all
    sources:                               # source routing (multi-source adaptors)
      myemr:
        client-id: myemr-client
```

| Env var (reference adaptor) | Purpose |
|-----------------------------|---------|
| `CCE_COLLECTOR_URL` | Gateway base URL |
| `KEYCLOAK_HOST`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` | OAuth2 client_credentials |
| `FACILITY_FILTER_IDS` | Optional facility allowlist (events from other facilities are skipped, not failed) |
| `SPRING_PROFILES_ACTIVE` | `dev` / `prod` profile selection |

## 12. Optional: OpenHIM Mediator Integration

If your source system's traffic already flows through an **OpenHIM** instance, the cleanest ingestion path is a **secondary route** on the existing channel — the adaptor receives a copy of each transaction without being on the primary path. This is entirely on the *inbound* side and independent of the CCE contract. It adds three OpenHIM-specific obligations:

1. **Registration** — POST a mediator descriptor to OpenHIM Core `/mediators` on startup (with `defaultChannelConfig: []`), Basic auth.
2. **Heartbeat** — periodic POST to `/mediators/{urn}/heartbeat` for liveness in the OpenHIM console.
3. **Response envelope** — respond with `Content-Type: application/json+openhim`, wrapping status, response body, and an orchestration log of the Collector call.

No third-party mediator library is needed — the reference adaptor implements all three with plain Spring components ([MediatorRegistrar](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/openhim/MediatorRegistrar.java), [HeartbeatScheduler](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/openhim/HeartbeatScheduler.java), [OpenHimResponseWrapper](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/src/main/java/org/openphc/cce/emitter/openhim/OpenHimResponseWrapper.java)). For channel configuration, see [openhim-channel-setup.md](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/docs/openhim-channel-setup.md); for the full mediator API surface, [api-reference.md §6](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/docs/api-reference.md).

Non-OpenHIM adaptors skip this section entirely and use plain HTTP responses.

## 13. Design Principles Recap

| Principle | Application |
|-----------|-------------|
| **Stateless** | No local database; all durable state lives in the Collector (dedup log) and Kafka |
| **Idempotent output** | Deterministic CloudEvent `id`; duplicates absorbed by the Collector as `200` |
| **Fail-fast** | Reject unmappable events immediately with descriptive errors; never forward garbage |
| **Retry transient, never permanent** | Backoff on 5xx/timeouts; 4xx means the event or mapping is wrong |
| **Config over code** | New sources/facilities via configuration (`cce.emitter.sources`), not code changes |
| **Separate trust boundaries** | Inbound auth (source-side) is independent of outbound auth (Keycloak → Gateway) |
| **Observable** | Correlation IDs end-to-end, per-event metrics, health probes |

## 14. Further Reading

| Document | Content |
|----------|---------|
| [architecture-overview.md](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/docs/architecture-overview.md) | Reference adaptor: system context, pipeline, packages |
| [data-dictionary.md](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/docs/data-dictionary.md) | Field-level definitions, config properties, metrics, MDC |
| [api-reference.md](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/docs/api-reference.md) | Reference adaptor endpoints, request/response examples |
| [flow-diagrams.md](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/docs/flow-diagrams.md) | Sequence diagrams for the full pipeline |
| [monitoring-alerting.md](https://github.com/openphc/openhim-cce-emitter-adaptor/blob/release-1.0.0/docs/monitoring-alerting.md) | Prometheus alert rules, Grafana dashboards |
| [cce-collector-service docs](https://github.com/openphc/cce-collector-service/tree/release-1.0.0/docs) | Collector API reference, architecture, kafka-events, deployment guide |
| [gateway-service](https://github.com/openphc/gateway-service) | Gateway routing + auth configuration |
