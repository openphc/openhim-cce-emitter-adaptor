# CCE Emitter Adaptor — API Reference

## 1. Overview

| Property | Value |
|----------|-------|
| **Base URL** | `http://<host>:8082` |
| **Protocol** | HTTP (HTTPS with Spring Boot `server.ssl.*`) |
| **Content Type** | `application/json` |
| **Response Format** | `application/json+openhim` (mediator response envelope) |

> **Beckn:** two more endpoints, `/beckn/bpp-webhook/{action}` and `/beckn/bap-webhook/{action}`, exist only with `beckn.enabled=true` (default `false`). See §8. `/inbound` is unchanged.

## 2. Inbound Event Endpoint

The adaptor exposes a single inbound endpoint. Source system adaptor is selected based on request headers (`X-OpenHIM-ClientID`).

> **Non-POST Handling:** OpenHIM secondary routes forward ALL HTTP methods matching the channel URL pattern. Since this mediator only processes POST requests, non-POST methods (GET, PUT, DELETE, PATCH, HEAD, OPTIONS) are acknowledged with a `200 OK` OpenHIM envelope containing `"Non-POST request ignored"`. This avoids polluting the OpenHIM transaction log with 405 errors.

### 2.1 POST /inbound

Generic inbound endpoint. Routes to the matching source based on `X-OpenHIM-ClientID` header (matched against configured client IDs in `cce.emitter.sources`).

```
POST /inbound
```

---

### Request Format

**Headers:**

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes | `application/json` |
| `X-OpenHIM-ClientID` | No | OpenHIM-authenticated client ID. Matched against configured source client IDs for adaptor routing. Automatically set by OpenHIM Core after client authentication. |
| `X-Source-System` | No | Source system identifier (e.g., `ebuzima`). Fallback when `X-OpenHIM-ClientID` is absent. |
| `X-Facility-Id` | No | Facility FOSA ID |
| `X-Source-Event-Id` | No | Source system's original event ID |
| `X-OpenHIM-TransactionID` | No | OpenHIM transaction ID. **Highest priority** source for CloudEvent `correlationid` — preferred over `X-Correlation-Id`. Automatically set by OpenHIM Core on every routed request. |
| `X-Correlation-Id` | No | Trace correlation ID. Used as `correlationid` if `X-OpenHIM-TransactionID` is absent. If both are absent, adaptor generates a UUID. |

> **Note:** This header list is derived from the CCE solution design document and local OpenHIM testing. The actual headers available may change based on the RHIE deployment configuration.

**Body:** Valid FHIR R4 resource JSON — an individual resource (e.g., `Encounter`, `Observation`, `Patient`, `RelatedPerson`). Bundle resources are silently ignored (out of scope for v1.0).

**Supported FHIR Resource Types:**

| Resource Type | Subject Extraction |
|--------------|--------------------|
| `Patient` | Extracted from `Patient.identifier[]` matching configured system URI (`http://openphc.org/identifier/upid`), falling back to `Patient.id` |
| `RelatedPerson` | Extracted from `RelatedPerson.patient` reference |
| `Encounter`, `Observation`, `Condition`, `MedicationRequest`, `MedicationDispense`, `ServiceRequest`, `Procedure`, `DiagnosticReport` | Extracted from `subject` reference |
| `EpisodeOfCare`, `Immunization` | Extracted from `patient` reference |

### Response Format

**Status:** `202 Accepted`

**Content-Type:** `application/json+openhim`

**Body:**

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Successful",
  "response": {
    "status": 202,
    "headers": {
      "Content-Type": "application/json"
    },
    "body": "{\"eventsProcessed\":1,\"eventsAccepted\":1,\"eventsRejected\":0,\"eventsDuplicate\":0}",
    "timestamp": "2026-02-25T08:00:05Z"
  },
  "orchestrations": [
    {
      "name": "Forward to CCE Collector",
      "request": {
        "method": "POST",
        "path": "/v1/events",
        "body": "{\"specversion\":\"1.0\",\"type\":\"Encounter\",...}",
        "timestamp": "2026-02-25T08:00:04Z"
      },
      "response": {
        "status": 202,
        "body": "{\"data\":{\"eventId\":\"evt-uuid\",\"status\":\"accepted\",\"correlationId\":\"corr-uuid\",\"timestamp\":\"2026-02-25T08:00:04.500Z\"}}",
        "timestamp": "2026-02-25T08:00:04.500Z"
      }
    }
  ]
}
```

---

## 3. Request & Response Examples

### 3.1 eBUZIMA Clinical Visit (FHIR Encounter)

**Request:**

```bash
curl -X POST http://localhost:8082/inbound \
  -H "Content-Type: application/json" \
  -H "X-OpenHIM-ClientID: ebuzima-emr-client" \
  -H "X-Facility-Id: FAC-FOSA-001" \
  -d '{
    "resourceType": "Encounter",
    "id": "enc-uuid-visit-kicukiro-001",
    "status": "finished",
    "class": {
      "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
      "code": "AMB",
      "display": "ambulatory"
    },
    "subject": {
      "reference": "Patient/UPID-PAT-12345"
    },
    "period": {
      "start": "2026-02-25T08:00:00Z"
    }
  }'
```

**Generated CloudEvent (sent to Collector):**

```json
{
  "specversion": "1.0",
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "source": "ebuzima",
  "type": "Encounter",
  "subject": "UPID-PAT-12345",
  "time": "2026-02-25T08:00:00.000Z",
  "datacontenttype": "application/fhir+json",
  "facilityid": "FAC-FOSA-001",
  "data": {
    "resourceType": "Encounter",
    "id": "enc-uuid-visit-kicukiro-001",
    "status": "finished",
    "class": {"code": "AMB"},
    "subject": {"reference": "Patient/UPID-PAT-12345"},
    "period": {"start": "2026-02-25T08:00:00Z"}
  }
}
```

### 3.2 FHIR Patient Resource

**Request:**

```bash
curl -X POST http://localhost:8082/inbound \
  -H "Content-Type: application/json" \
  -H "X-OpenHIM-ClientID: ebuzima-emr-client" \
  -d '{
    "resourceType": "Patient",
    "id": "pat-uuid-001",
    "identifier": [
      {
        "system": "http://openphc.org/identifier/upid",
        "value": "260225-0002-5501"
      }
    ],
    "name": [{"family": "KAYITESI", "given": ["Marie-Claire"]}]
  }'
```

**Subject extraction:** The UPID `260225-0002-5501` is extracted from `Patient.identifier[]` where `system` matches the configured `cce.emitter.patient-identifier-system` URI. If no matching identifier is found, `Patient.id` is used as fallback.

### 3.3 Non-POST Request (Silently Ignored)

```bash
curl -X GET http://localhost:8082/inbound \
  -H "X-OpenHIM-ClientID: ebuzima-emr-client"
```

**Response:** `200 OK`

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Successful",
  "response": {
    "status": 200,
    "headers": {"Content-Type": "application/json"},
    "body": "{\"message\":\"Non-POST request ignored\",\"method\":\"GET\"}",
    "timestamp": "2026-02-25T08:00:00Z"
  },
  "orchestrations": []
}
```

### 3.4 FHIR Bundle (Out of Scope)

Bundle resources (`"resourceType": "Bundle"`) are silently ignored — the adaptor returns a 200 OK response with no processing. Bundle support will be added in a future release.

---

## 4. Error Responses

Error responses are wrapped in the OpenHIM mediator envelope with `"status": "Failed"`.

### 4.1 Facility Filter Skipped (200)

When `FACILITY_FILTER_IDS` is configured (non-empty) and the event's resolved facility ID is not in the allowlist, the adaptor returns `200 OK` with `status: "skipped"` — the event is **not** forwarded to the Collector. OpenHIM records the transaction as **Completed** (not Failed). Events with no resolvable facility ID (e.g. `Patient`, `Observation`) always pass through unconditionally.

```json
{
  "status": "skipped",
  "message": "Event skipped by facility filter: facilityId='9999' source='spice'"
}
```

Events with no facility ID (e.g. `Patient`, `RelatedPerson`) are always forwarded and never reach the filter.

> **Note:** Configure `ids` with bare ID values only (e.g. `0030`, `1302`). `FacilityIdExtractor` strips any `ResourceType/` prefix generically during extraction — both `Location/1302` and `Organization/1302` resolve to `1302` before reaching the filter.

### 4.3 Unrecognized Source (200 — Silently Ignored)

When no source adaptor matches the inbound request (no `X-OpenHIM-ClientID` or `X-Source-System` header matches any configured source), the adaptor silently ignores the request and returns a `200 OK` response. No error is raised. This is by design — the adaptor sits on a secondary route and receives all traffic on that OpenHIM channel; only matching requests are processed.

### 4.4 Patient ID Not Found (400)

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Failed",
  "response": {
    "status": 400,
    "headers": {"Content-Type": "application/json"},
    "body": "{\"error\":{\"code\":\"PATIENT_ID_NOT_FOUND\",\"message\":\"No patient reference found in Observation\"}}",
    "timestamp": "2026-02-25T08:00:05Z"
  },
  "orchestrations": []
}
```

### 4.5 Internal Server Error (500)

Caught by the global catch-all exception handler for any unexpected errors not covered by specific handlers.

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Failed",
  "response": {
    "status": 500,
    "headers": {"Content-Type": "application/json"},
    "body": "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"Unexpected error details\"}}",
    "timestamp": "2026-02-25T08:00:05Z"
  },
  "orchestrations": []
}
```

### 4.6 Collector Forwarding Failure (502)

```json
{
  "x-mediator-urn": "urn:mediator:cce-emitter-adaptor",
  "status": "Failed",
  "response": {
    "status": 502,
    "headers": {"Content-Type": "application/json"},
    "body": "{\"error\":{\"code\":\"COLLECTOR_FORWARDING_ERROR\",\"message\":\"All retries exhausted for event a1b2c3d4\"}}",
    "timestamp": "2026-02-25T08:00:10Z"
  },
  "orchestrations": [
    {
      "name": "Forward to CCE Collector (attempt 1/3)",
      "request": {"method": "POST", "path": "/v1/events"},
      "response": {"status": 503, "body": "Service Unavailable"},
      "timestamp": "2026-02-25T08:00:06Z"
    }
  ]
}
```

---

## 5. Actuator Endpoints

Spring Boot Actuator endpoints exposed for operations.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/actuator/health` | GET | Overall health status |
| `/actuator/health/liveness` | GET | Kubernetes liveness probe |
| `/actuator/health/readiness` | GET | Kubernetes readiness probe |
| `/actuator/info` | GET | Application info (name, version) |
| `/actuator/prometheus` | GET | Prometheus metrics scrape endpoint |
| `/actuator/metrics` | GET | All available metrics list |
| `/actuator/metrics/{metricName}` | GET | Specific metric detail |

### Health Response

```json
{
  "status": "UP",
  "groups": ["liveness", "readiness"]
}
```

### Prometheus Metrics (excerpt)

```
# HELP cce_emitter_events_received_total Total inbound events received
# TYPE cce_emitter_events_received_total counter
cce_emitter_events_received_total{source="ebuzima",path="/inbound"} 42.0

# HELP cce_emitter_events_forwarded_total Events forwarded to Collector
# TYPE cce_emitter_events_forwarded_total counter
cce_emitter_events_forwarded_total{source="ebuzima"} 40.0

# HELP cce_emitter_events_duplicate_total Duplicate events
# TYPE cce_emitter_events_duplicate_total counter
cce_emitter_events_duplicate_total 2.0

# HELP cce_emitter_collector_latency_seconds Collector forwarding latency
# TYPE cce_emitter_collector_latency_seconds summary
cce_emitter_collector_latency_seconds_count 42.0
cce_emitter_collector_latency_seconds_sum 8.456

# HELP cce_emitter_events_filtered_total Events denied by facility filter
# TYPE cce_emitter_events_filtered_total counter
cce_emitter_events_filtered_total{source="spice",facility="9999",reason="NOT_IN_ALLOWLIST"} 3.0

# HELP cce_emitter_events_redacted_total Inbound events from which clinical fields were removed
# TYPE cce_emitter_events_redacted_total counter
cce_emitter_events_redacted_total{resource_type="Observation"} 128.0
cce_emitter_events_redacted_total{resource_type="Condition"} 14.0

# HELP cce_emitter_redaction_path_mismatch_total Configured redaction paths that matched nothing because an array was found where an object was expected (add '[]' to the segment)
# TYPE cce_emitter_redaction_path_mismatch_total counter
cce_emitter_redaction_path_mismatch_total{resource_type="AllergyIntolerance",path="reaction.manifestation"} 0.0
```

---

## 6. OpenHIM Core API Calls (Outbound)

Calls made by the adaptor to OpenHIM Core for mediator lifecycle.

### 6.1 Registration

```
POST https://<openhim-core>:8080/mediators
Authorization: Basic <base64(username:password)>
Content-Type: application/json

{
  "urn": "urn:mediator:cce-emitter-adaptor",
  "version": "1.0.0",
  "name": "CCE Emitter Adaptor",
  "description": "Transforms source system events into CloudEvents for CCE Compliance pipeline",
  "defaultChannelConfig": [],
  "endpoints": [...]
}
```

**Response:** `201 Created` (first registration) or `200 OK` (update).

### 6.2 Heartbeat

```
POST https://<openhim-core>:8080/mediators/urn:mediator:cce-emitter-adaptor/heartbeat
Authorization: Basic <base64(username:password)>
Content-Type: application/json

{
  "uptime": 3600000
}
```

**Response:** `200 OK`.

---

## 7. Downstream Call (Outbound)

### Forward to CCE Collector

```
POST <collector-url>/v1/events
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "specversion": "1.0",
  "id": "...",
  "source": "...",
  "type": "Encounter",
  ...
}
```

> **Authentication:** The adaptor authenticates to the CCE Collector (via CCE Gateway) using an OAuth2 Bearer token obtained from Keycloak via the `client_credentials` grant. Tokens are cached and refreshed automatically by `CollectorTokenService`. If Keycloak is not configured, falls back to a static Bearer token from `cce.collector.auth.token`. This is separate from the inbound OpenHIM channel auth.

**Success:** `202 Accepted`
**Duplicate:** `200 OK`
**Validation Error:** `400 Bad Request` (missing `type`)
**Server Error:** `5xx` → retried with exponential backoff (max 3 attempts)

---

## 8. Beckn Endpoints and Calls (Proposal)

> **Status:** Built on branch `demo-beckn`, on by default (`beckn.enabled=true`; set `OPENHIM_EMITTER_BECKN_ENABLED=false` to turn it off). Full design: [Beckn Integration](beckn-integration.md).

These exist only with `beckn.enabled=true`. `/inbound` and the collector call in §7 are unchanged; a source keeps the collector output unless it is set to `output: beckn`.

Both endpoints are called by the HIE's Beckn adapter (`openhim-onix`), which has already checked the Beckn signature and schema and appends the action name to the path. No token is checked, as for `/inbound`. Answers use the body shape of `cce-beckn-service`, with the request's `messageId`:

```json
{ "message": { "status": "ACK", "messageId": "<request messageId>" } }
```

```json
{ "message": { "status": "NACK", "messageId": "<request messageId>",
               "error": { "code": "<CODE>", "message": "<text>" } } }
```

| Code | HTTP | When |
|------|------|------|
| — (`ACK`) | 200 | Success |
| `BAD_REQUEST` | 400 | A rule is broken in the HMIS's message: unknown subscriber or contract, not exactly one `performance` entry, a missing field |
| `NOT_SUPPORTED` | 400 | An action this endpoint does not take |
| `EVENT_REJECTED` | 400 | The pipeline refused the event (`ignored`, `skipped`, no patient, bad FHIR), or CCE answered `EVENT_REJECTED` (CCE's text) |
| `UNAVAILABLE` | 500 | Something that may pass: OpenHIM, the collector, CCE or onix down or slow. Send again later with the same performance id |
| `HIE_ERROR` | 500 | A fault in the HIE: CCE answered `BAD_REQUEST` or `NOT_SUPPORTED` to this adaptor's own `update`, or OpenHIM refused this adaptor's client (401, 403, 404). Send again later; the HIE team must fix it |

### 8.1 POST /beckn/bpp-webhook/{action}

Called by `onix-bpp` with requests from an HMIS (OpenMRS, Spice) on its "HIE feed" contract.

| `action` | What the adaptor does | Answer |
|----------|-----------------------|--------|
| `update` | Unwraps the FHIR resource and posts it to the OpenHIM channel (below) | By the channel's answer |
| `confirm`, `status`, `cancel`, `select`, `init`, `track`, `rate`, `support`, any other | Nothing. Not supported in the demo | `Nack` (400) `NOT_SUPPORTED` |

**Request:** a Beckn `update`. `context.bapId` must be a `beckn.subscribers[].subscriber-id` and `message.contract.id` its `contract-id`, or the answer is `Nack` (400) `BAD_REQUEST`. **Exactly one** `performance` entry, with an `id`, whose `performanceAttributes` is a `CceClinicalEvent` box; more than one is refused with `BAD_REQUEST`. Sample: [Beckn Integration §3.6](beckn-integration.md#36-example-inbound-update).

**Call to the OpenHIM channel:**

```
POST {beckn.openhim-channel.url}
Authorization: Basic <beckn.openhim-channel.username:password>
Content-Type: application/json
X-Source-System: <subscriber's source-system>
X-Source-Event-Id: <performance[0].id>
X-Correlation-Id: <context.messageId>

<performance[0].performanceAttributes.resource>
```

No `X-Facility-Id` is sent; the pipeline reads the facility from the resource.

**Answer mapping:**

| Channel answers (from `/inbound`) | HMIS gets |
|-----------------------------------|-----------|
| 202, event processed | `Ack`, then `on_update` with `ACCEPTED` |
| 200 `"status":"ignored"` (no source matched, not FHIR, `Bundle`) | `Nack` (400) `EVENT_REJECTED` |
| 200 `"status":"skipped"` (facility filter) | `Nack` (400) `EVENT_REJECTED` |
| 400 `PATIENT_ID_NOT_FOUND`, 422 `FHIR_MAPPING_ERROR`, 4xx `COLLECTOR_CLIENT_ERROR` from the collector | `Nack` (400) `EVENT_REJECTED`, text = the code and message |
| 400 `COLLECTOR_CLIENT_ERROR`, message `CCE EVENT_REJECTED: <text>` | `Nack` (400) `EVENT_REJECTED`, text `CCE: <text>` |
| 400 `COLLECTOR_CLIENT_ERROR`, message `CCE BAD_REQUEST: …` or `CCE NOT_SUPPORTED: …` | `Nack` (500) `HIE_ERROR`, text = that message (`CCE <code>: <text>`) |
| OpenHIM's own 401, 403 or 404 (client or channel setup) | `Nack` (500) `HIE_ERROR`, OpenHIM's text |
| 5xx (`COLLECTOR_FORWARDING_ERROR`, `INTERNAL_ERROR`, OpenHIM route failure), 408, 429, timeout | `Nack` (500) `UNAVAILABLE` |
| any other 2xx (not `processed`, `ignored` or `skipped`) | `Nack` (500) `HIE_ERROR` |

### 8.2 POST /beckn/bap-webhook/{action}

Called by `onix-bap` with callbacks from CCE on the HIE's contract with CCE.

| `action` | What the adaptor does | Answer |
|----------|-----------------------|--------|
| `on_update` from CCE | Logs the `messageId` (of this adaptor's `update`), the performance id (the CloudEvent `id`) and status, `ACCEPTED` or `DUPLICATE`. Log only | `Ack` |
| `on_update` from another `bppId` or contract | Logs it | `Nack` (400) `BAD_REQUEST` |
| `on_status` (CCE alerts), any other | Logs it. Out of scope for now | `Nack` (400) `NOT_SUPPORTED` |

CCE's `on_update` may arrive before the `Ack` of the `update`, or not at all (CCE sends it once). It never changes an answer already given.

### 8.3 Calls to onix

| Message | Container | Path | Contract | When |
|---------|-----------|------|----------|------|
| `update` to CCE | `onix-bap` | `{beckn.onix.bap-caller-url}/update` (`/bap/caller/update`). `cce-onix` passes it to `cce-beckn-service` `POST /api/bpp-webhook/update`, port 8075 | HIE to CCE (`beckn.cce.*`, commitment `cce-care-coordination`) | A CloudEvent from a source with `output: beckn` |
| `on_update` to the HMIS | `onix-bpp` | `{beckn.onix.bpp-caller-url}/on_update` (`/bpp/caller/on_update`) | The HMIS's HIE feed (`beckn.subscribers[]`) | After an `Ack` on §8.1 |

`update` to CCE carries the CloudEvent in a `CceClinicalEvent` box: [Beckn Integration §5.2–5.3](beckn-integration.md#52-cloudevent--cceclinicalevent-box). Its answer is mapped like a collector answer, so `/inbound` answers as it does today:

| `onix-bap` answers | `/inbound` answers |
|--------------------|--------------------|
| 200 `ACK` | 202, like collector `accepted` |
| 400 `NACK` (`EVENT_REJECTED`, `BAD_REQUEST`, `NOT_SUPPORTED`), or a 400 from `cce-onix`'s schema check | 400 `COLLECTOR_CLIENT_ERROR`, message `CCE <code>: <CCE's text>`, like a collector 4xx. No retry |
| 500 `NACK` (`COLLECTOR_UNAVAILABLE`, `INTERNAL_ERROR`), 408, 429, other 5xx, timeout, connection refused | 502 `COLLECTOR_FORWARDING_ERROR`, like a collector failure. No retry inside the route (`beckn.cce.retry.max-attempts: 1`): a second try would not fit the channel's timeout. The HMIS resends |

CCE's `Ack` can take up to 17 s, so `beckn.onix.timeout` is 25 s ([Beckn Integration §8.3](beckn-integration.md#83-every-answer-returns-in-the-same-call)). The outbound performance id is the CloudEvent `id` (36 characters; CCE allows 50), and CCE uses it unchanged as its CloudEvent `id`.

`on_update` to the HMIS: performance `{ "id": <the HMIS's performance[0].id>, "status": { "code": "ACCEPTED" } }`, same `transactionId` and `messageId` as the `update` it answers. A failure is logged and counted; the `Ack` stands.
