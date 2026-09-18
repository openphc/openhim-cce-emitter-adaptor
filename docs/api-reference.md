# CCE Emitter Adaptor — API Reference

## 1. Overview

| Property | Value |
|----------|-------|
| **Base URL** | `http://<host>:8082` |
| **Protocol** | HTTP (HTTPS with Spring Boot `server.ssl.*`) |
| **Content Type** | `application/json` |
| **Response Format** | `application/json+openhim` (mediator response envelope) |

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
