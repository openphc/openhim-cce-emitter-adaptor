# CCE Emitter Adaptor — API Reference

## 1. Overview

| Property | Value |
|----------|-------|
| **Base URL** | `http://<host>:8082` |
| **Protocol** | HTTP (HTTPS with Spring Boot `server.ssl.*`) |
| **Content Type** | `application/json` |
| **Response Format** | `application/json+openhim` (mediator response envelope) |

> **Sygnal:** one more endpoint, `POST /sygnal/event/report`, exists only with `beckn.enabled=true` (the default on this branch). See §8. `/inbound` is unchanged.

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
| `X-Sygnal-Event-Id` | No | Set by the Sygnal endpoint (§8.1): the HMIS's event id. Used as the CloudEvent `id` when it is a full UUID, else ignored |
| `X-Sygnal-Occurred-At` | No | Set by the Sygnal endpoint (§8.1): when the clinical event happened. Used as the CloudEvent `time` when it parses as an ISO-8601 offset date-time, else the time of receipt |
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

## 8. Sygnal Endpoint and Call (Proposal)

> **Status:** Built on branch `sygnal-actions`, on by default (`beckn.enabled=true`; set `OPENHIM_EMITTER_BECKN_ENABLED=false` to turn it off). Full design: [Beckn Integration](beckn-integration.md). The settings keep the `beckn.*` prefix.

These exist only with `beckn.enabled=true`. `/inbound` and the collector call in §7 are unchanged; a source without an explicit `output` follows `beckn.enabled` (Sygnal when it is on), and `output: collector` keeps the collector.

Both hops use one Sygnal action, `sygnal/event/report`: the HMIS sends it to the HIE (§8.1), and the HIE sends it on to CCE (§8.2). There are no callbacks (no `on_update`, `on_status` or `on_report`): the synchronous HTTP answer is the whole result. The endpoint is called by the HIE's onix container (`onix-openhim`), which has already checked the signature and the schema and uses the action as the path. No token is checked, as for `/inbound`. Answers use the body shape of `cce-beckn-service`:

```json
{ "message": { "status": "ACK", "messageId": "<request messageId>" } }
```

```json
{ "message": { "status": "NACK", "messageId": "<request messageId>",
               "error": { "code": "<CODE>", "message": "<text>" } } }
```

`messageId` is the request's when it is a UUID, else a new UUID. The codes are from the canonical Beckn list:

| Code | HTTP | When |
|------|------|------|
| — (`ACK`) | 202 | CCE has the event (new, or one it already had) |
| `CTX_ACTION_MISMATCH` | 400 | `context.action` is not `sygnal/event/report` |
| `CTX_MISSING_FIELD` | 400 | `context.senderId`, `context.receiverId` or `context.messageId` is missing |
| `CTX_INVALID_FIELD` | 400 | `context.senderId` is not a configured subscriber (`beckn.subscribers[].subscriber-id`), or `context.receiverId` is not `beckn.self.id` |
| `SCH_INVALID_JSON` | 400 | The body is not JSON. A 4xx Spring refuses before the check (for example 415 when the `Content-Type` is not JSON) keeps its status, with this code |
| `SCH_REQUIRED_FIELD_MISSING` | 400 | `events[0].id` is missing |
| `SCH_SCHEMA_VALIDATION_FAILED` | 400 | Not exactly one event; `events[0].id` not a full UUID; `revision` present and not 1; `from.id` present and not `context.senderId`; `occurredAt` (or `context.timestamp` when there is none) not ISO-8601; `eventAttributes.resource` not an object with a `resourceType` |
| `SCH_TYPE_NOT_SUPPORTED` | 400 | `eventAttributes.@type` is not `cce:CceClinicalEvent` |
| `BIZ_GENERIC_ERROR` | 400 | The pipeline refused the event (`ignored`, `skipped`, no patient, bad FHIR, collector refusal), or CCE refused it (CCE's code and text) |
| `NET_DOWNSTREAM_UNAVAILABLE` | 500 | Something that may pass: OpenHIM, the collector, CCE or onix down or slow. Send the event again later with the same event id |
| `NET_INTERNAL_ERROR` | 500 | A fault in the HIE: CCE refused this adaptor's own context (`CTX_*`, wrong HIE settings), OpenHIM refused this adaptor's client (401, 403, 404), an unexpected answer, or an unexpected exception. Send again later; the HIE team must fix it |

400 = do not send it again as it is. 5xx = may send it again; the resend carries the same event id, so CCE answers `Ack` (duplicate).

### 8.1 POST /sygnal/event/report

Called by `onix-openhim`'s receiver with a `sygnal/event/report` from an HMIS (OpenMRS). The adaptor checks it (codes above), unwraps the FHIR resource and posts it to the OpenHIM channel. It answers only when the channel has answered, that is, after CCE has answered the HIE's own `sygnal/event/report` (§8.2).

**Request:** exactly one event, whose `eventAttributes` is a `CceClinicalEvent` box. Sample: [Beckn Integration §3](beckn-integration.md).

```json
{
  "context": {
    "version": "2.0.0",
    "action": "sygnal/event/report",
    "networkId": "sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry",
    "senderId": "bap.openmrs.nextsteps.mdtlabs.org",
    "receiverId": "bap.openhim.nextsteps.mdtlabs.org",
    "receiverUri": "https://hie.example.org/sygnal/receiver",
    "transactionId": "6f1c2a4e-1d7b-4a55-9a0e-3f2b8c9d0e11",
    "messageId": "0b9e7f3a-52c4-4e7d-8a61-7c2d9e4f1a22",
    "timestamp": "2026-11-20T08:15:00Z"
  },
  "message": {
    "events": [ {
      "@type": "sygnal:CoordinationEvent",
      "id": "3d5f8a90-7b1c-4e2a-9f63-1a2b3c4d5e6f",
      "revision": 1,
      "eventType": "DELIVERY_RECORDED",
      "granularity": "INDIVIDUAL",
      "from": { "id": "bap.openmrs.nextsteps.mdtlabs.org", "role": "PROVIDER" },
      "occurredAt": "2026-11-20T08:10:00Z",
      "eventAttributes": {
        "@context": "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v1.0/context.jsonld",
        "@type": "cce:CceClinicalEvent",
        "eventType": "Encounter",
        "patientId": "Patient/12345",
        "fhirVersion": "4.0.1",
        "sourceEventId": "encounter-98765",
        "resource": { "resourceType": "Encounter", "id": "98765", "...": "..." }
      }
    } ]
  }
}
```

**Call to the OpenHIM channel:**

```
POST {beckn.openhim-channel.url}
Authorization: Basic <beckn.openhim-channel.username:password>
Content-Type: application/json
X-Source-System: <subscriber's source-system>
X-Source-Event-Id: <eventAttributes.sourceEventId, else events[0].id>
X-Sygnal-Event-Id: <events[0].id>
X-Sygnal-Occurred-At: <events[0].occurredAt, else context.timestamp>
X-Correlation-Id: <context.messageId>

<events[0].eventAttributes.resource>
```

`X-Source-Event-Id` uses `sourceEventId` only when it is header-safe (printable ASCII, at most 200 characters). `X-Sygnal-Occurred-At` is left out when both times are blank; `X-Correlation-Id` when the `messageId` is not header-safe. No `X-Facility-Id` is sent; the pipeline reads the facility from the resource. OpenHIM routes the request to `/inbound`, where the CloudEvent `id` is `X-Sygnal-Event-Id` (when it is a full UUID) and the CloudEvent `time` is `X-Sygnal-Occurred-At` (when it parses as an ISO-8601 offset date-time, else the time of receipt). So CCE de-duplicates on the same event id the HMIS sent.

**Answer mapping:**

| Channel answers (from `/inbound`) | HMIS gets |
|-----------------------------------|-----------|
| 2xx `"status":"processed"`, or 202 (CCE `Ack`, new or duplicate) | 202 `Ack` |
| 2xx `"status":"ignored"` (no source matched, not FHIR, `Bundle`) | `Nack` (400) `BIZ_GENERIC_ERROR`, text `ignored: …` |
| 2xx `"status":"skipped"` (facility filter) | `Nack` (400) `BIZ_GENERIC_ERROR`, text `skipped: …` |
| 4xx `COLLECTOR_CLIENT_ERROR`, message `CCE BIZ_GENERIC_ERROR: <text>` | `Nack` (400) `BIZ_GENERIC_ERROR`, text `CCE: <text>` |
| 4xx `COLLECTOR_CLIENT_ERROR`, message `CCE CTX_…: <text>` (CCE refused the HIE's own context) | `Nack` (500) `NET_INTERNAL_ERROR`, text = that message |
| 4xx `COLLECTOR_CLIENT_ERROR`, message `CCE <other code>: <text>` (for example `SCH_*`) | `Nack` (400) `BIZ_GENERIC_ERROR`, text = that message |
| 4xx pipeline error: 400 `PATIENT_ID_NOT_FOUND`, 422 `FHIR_MAPPING_ERROR`, `COLLECTOR_CLIENT_ERROR` from the collector | `Nack` (400) `BIZ_GENERIC_ERROR`, text = the code and message |
| 408, 429, 5xx (`COLLECTOR_FORWARDING_ERROR`, `INTERNAL_ERROR`, OpenHIM route failure), no answer (timeout) | `Nack` (500) `NET_DOWNSTREAM_UNAVAILABLE`, text ends `Send the event again later.` |
| other 4xx without a pipeline error body (OpenHIM's own 401, 403, 404), any other 2xx | `Nack` (500) `NET_INTERNAL_ERROR` |

### 8.2 Call to onix: sygnal/event/report to CCE

| Message | Container | Path | When |
|---------|-----------|------|------|
| `sygnal/event/report` to CCE | `onix-openhim` | `{beckn.onix.caller-url}/sygnal/event/report` (`http://onix-openhim:8082/sygnal/caller/sygnal/event/report`). `onix-openhim` signs it and sends it to `context.receiverUri` + `/sygnal/event/report` (`onix-cce`'s receiver), which passes it to `cce-beckn-service` `POST /sygnal/event/report`, port 8075 | A CloudEvent from a source with `output: beckn` |

```json
{
  "context": {
    "version": "2.0.0", "action": "sygnal/event/report",
    "networkId": "<beckn.self.network-id>",
    "senderId": "<beckn.self.id>", "receiverId": "<beckn.cce.id>", "receiverUri": "<beckn.cce.uri>",
    "transactionId": "<new UUID per event>", "messageId": "<new UUID per try>", "timestamp": "<now>"
  },
  "message": {
    "events": [ {
      "@type": "sygnal:CoordinationEvent",
      "id": "<CloudEvent id>",
      "revision": 1,
      "eventType": "DELIVERY_RECORDED",
      "granularity": "INDIVIDUAL",
      "from": { "id": "<beckn.self.id>", "role": "PROVIDER" },
      "occurredAt": "<CloudEvent time>",
      "eventAttributes": { "@context": "...", "@type": "cce:CceClinicalEvent", "...": "..." }
    } ]
  }
}
```

The event carries the CloudEvent in a `CceClinicalEvent` box: [Beckn Integration §5](beckn-integration.md). The event object has no `@context`; there is no contract and no commitment. The event `id` is the CloudEvent `id`, which for an event that came in over Sygnal is the HMIS's own event id. Its answer is mapped like a collector answer, so `/inbound` answers as it does today:

| `onix-openhim` answers | `/inbound` answers |
|------------------------|--------------------|
| 2xx, body not `NACK` (CCE answers 202 `ACK` for a new and for a duplicate event) | 202, like collector `accepted` |
| 4xx (not 408 or 429) `NACK` (`BIZ_GENERIC_ERROR`, `CTX_*`, `SCH_*`), or a 4xx from `onix-cce`'s schema check | 400 `COLLECTOR_CLIENT_ERROR`, message `CCE <code>: <text>` (code `BIZ_GENERIC_ERROR` when the body has none), like a collector 4xx. No retry |
| 2xx `NACK` with `NET_DOWNSTREAM_UNAVAILABLE` or `NET_INTERNAL_ERROR` | 502 `COLLECTOR_FORWARDING_ERROR` |
| 2xx `NACK` with any other code | 400 `COLLECTOR_CLIENT_ERROR`, as a 4xx `NACK` |
| 408, 429, 5xx, timeout, connection refused | 502 `COLLECTOR_FORWARDING_ERROR`, like a collector failure. No retry inside the route (`beckn.cce.retry.max-attempts: 1`): a second try would not fit the channel's timeout. The HMIS resends |

CCE's `Ack` can take up to about 17 s, so `beckn.onix.timeout` is 20 s, under the OpenHIM channel timeout (about 22–23 s) and `beckn.openhim-channel.timeout` (25 s) ([Beckn Integration §8.3](beckn-integration.md#83-every-answer-returns-in-the-same-call)). The OpenHIM orchestration is named `Forward to CCE over Sygnal`, path `/sygnal/caller/sygnal/event/report`.
