# CCE Emitter Adaptor — Data Dictionary

## 1. CloudEvents Output Envelope

The adaptor outputs CloudEvents v1.0-compliant JSON to the CCE Collector.

### 1.1 Field Definitions

| Field | Type | Required | Source | Description |
|-------|------|----------|--------|-------------|
| `specversion` | string | **Yes** — always `"1.0"` | Static | CloudEvents specification version |
| `id` | string (UUID) | **Yes** | Generated | Unique event identifier (`UUID.randomUUID()` or deterministic hash). For an event that came in over Sygnal, the HMIS's event id from `X-Sygnal-Event-Id` (§9.2) |
| `source` | string (URI) | **Yes** | Adaptor | Source system identifier (e.g. `"ebuzima"`) |
| `type` | string | **Yes** | From FHIR resource | FHIR `resourceType` value as-is (e.g., `"Encounter"`, `"Observation"`) |
| `subject` | string | Recommended | Extracted from FHIR | Patient UPID. For Patient resources: extracted from `identifier[]` matching configured system URI, then `Patient.id` fallback. For other resources: from `subject` or `patient` reference (prefix stripped). Used as Kafka partition key. |
| `time` | string (ISO-8601) | Recommended | Adaptor | Event creation timestamp in UTC. For an event that came in over Sygnal, when it happened, from `X-Sygnal-Occurred-At` (§9.2) |
| `datacontenttype` | string | Recommended | Static | Always `"application/fhir+json"` |
| `data` | object | Recommended | From FHIR resource | FHIR R4 resource JSON |
| `facilityid` | string | Optional | Header / payload | Facility FOSA ID. Resolution order: (1) `X-Facility-Id` header; (2) `FacilityIdExtractor` — for `Encounter`, `hospitalization.origin` first, then `location[0].location` (the `source-facility` extension is never consulted for `Encounter` — see §3.5); for other types, `locationReference[0]` (e.g. `ServiceRequest`) or `location` direct reference (e.g. `Procedure`, `Immunization`). Any `ResourceType/` prefix stripped (`Location/1302` → `1302`, `Organization/1302` → `1302`). `null` for resources with no location info (e.g. `Patient`, `Observation`). |
| `sourceeventid` | string | Optional | Header / payload | Source system's original event ID from `X-Source-Event-Id` header |
| `correlationid` | string | Recommended | Header or generated | Trace correlation ID. Priority: (1) `X-OpenHIM-TransactionID` header, (2) `X-Correlation-Id` header, (3) adaptor-generated UUID. |
| `protocolinstanceid` | string | Optional | Usually null | Protocol instance — emitter normally does not set this |
| `protocoldefinitionid` | string | Optional | Usually null | Protocol definition — emitter normally does not set this |
| `actionid` | string | Optional | Usually null | Action — emitter normally does not set this |

### 1.2 Core Field Population

| Rule | Detail |
|------|--------|
| **Adaptor populates all required fields** | The adaptor's core responsibility is to populate `id`, `source`, `type`, `subject`, `time`, `datacontenttype`, and `data` for correct downstream processing by the Compliance Service. |
| **Extension attributes are lowercase** | Per CloudEvents spec, custom extension attributes use `lowercase` without separators: `facilityid`, `sourceeventid`, `correlationid`, `protocolinstanceid`, etc. |

### 1.3 Sample CloudEvent

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
  "sourceeventid": "ebz-visit-9876",
  "correlationid": "trace-abc-123",
  "data": {
    "resourceType": "Encounter",
    "id": "enc-001",
    "status": "finished",
    "class": {"code": "AMB"},
    "subject": {"reference": "Patient/UPID-PAT-12345"},
    "period": {"start": "2026-02-25T08:00:00Z"}
  }
}
```

## 2. Inbound Request Model

### 2.1 HTTP Request (from OpenHIM Core)

For the full list of inbound headers, see [API Reference — §2.1 POST /inbound](api-reference.md#21-post-inbound).

### 2.2 InboundRequest Fields

| Field | Type | Populated From | Description |
|-------|------|----------------|-------------|
| `body` | String | HTTP body | Raw request body |
| `headers` | Map<String, String> | HTTP headers (normalized to lowercase keys) | All request headers |
| `path` | String | `HttpServletRequest.getRequestURI()` | Request path |

> **Note:** `InboundRequest` is immutable — created via static `from()` factory methods. Source metadata (`SourceMetadata`) is built separately in `SourceAdaptorService` from request headers.

### 2.3 SourceMetadata Fields

| Field | Type | Source | Description |
|-------|------|--------|-------------|
| `sourceIdentifier` | String | Resolved from `X-OpenHIM-ClientID` or `X-Source-System` header, or path segment | e.g., `"ebuzima"` |
| `facilityId` | String | `X-Facility-Id` header, or extracted by `FacilityIdExtractor` from resource location fields | Nullable — null for resources with no location info |
| `sourceEventId` | String | `X-Source-Event-Id` header | Nullable |
| `correlationId` | String | `X-OpenHIM-TransactionID` header, `X-Correlation-Id` header, or generated by adaptor | For downstream tracing. Nullable on input. |
| `eventTime` | OffsetDateTime | `X-Sygnal-Occurred-At` header when it parses, else `Instant.now(ZoneOffset.UTC)` | When the adaptor received the event, or, over Sygnal, when it happened |
| `sourcePath` | String | Request URI path | e.g., `/inbound` |
| `eventId` | String | `X-Sygnal-Event-Id` header, when it is a full UUID | Kept as the CloudEvent `id`. Nullable: without it the id is derived or new |

## 3. Configuration Properties

### 3.1 OpenHIM Properties

Prefix: `openhim`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `openhim.core.host` | String | `localhost` | OpenHIM Core hostname |
| `openhim.core.api-port` | int | `8080` | OpenHIM Core API port (HTTPS) |
| `openhim.core.username` | String | `root@openhim.org` | OpenHIM Core API username |
| `openhim.core.password` | String | — | OpenHIM Core API password |
| `openhim.heartbeat.enabled` | boolean | `true` | Enable/disable heartbeat scheduler |
| `openhim.heartbeat.interval-seconds` | int | `10` | Heartbeat interval in seconds |

### 3.2 Mediator Properties

Prefix: `mediator`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `mediator.urn` | String | `urn:mediator:cce-emitter-adaptor` | Unique mediator URN |
| `mediator.version` | String | `1.0.0` | Mediator version string |
| `mediator.name` | String | `CCE Emitter Adaptor` | Human-readable mediator name |
| `mediator.endpoint.host` | String | `emitter-adaptor` | Hostname/container name registered with OpenHIM Core |
| `mediator.endpoint.path` | String | `/inbound` | Request path registered with OpenHIM Core |
| `mediator.endpoint.type` | String | `http` | Protocol type (`http` or `https`) |

### 3.3 Collector Properties

Prefix: `cce.collector`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `cce.collector.url` | String | `http://localhost:5001` | Collector base URL |
| `cce.collector.events-path` | String | `/v1/events` | Events endpoint path |
| `cce.collector.timeout` | int | `15000` | HTTP connect + read timeout in ms. Must be longer than the Collector's Kafka publish time (3 s + 10 s) |
| `cce.collector.retry.max-attempts` | int | `3` | Maximum retry attempts for 5xx/timeout |
| `cce.collector.retry.backoff-ms` | int | `1000` | Initial backoff delay in ms (doubles per retry) |
| `cce.collector.auth.token` | String | — | Static Bearer token (fallback when Keycloak is not configured) |
| `cce.collector.auth.keycloak-host` | String | — | Keycloak base URL (e.g., `https://keycloak.cce.mdtlabs.org`) |
| `cce.collector.auth.realm` | String | — | Keycloak realm name (e.g., `cce`) |
| `cce.collector.auth.client-id` | String | — | OAuth2 client ID for `client_credentials` grant |
| `cce.collector.auth.client-secret` | String | — | OAuth2 client secret |

> **Auth mode selection:** If all four Keycloak properties (`keycloak-host`, `realm`, `client-id`, `client-secret`) are set, OAuth2 `client_credentials` flow is used and tokens are cached/refreshed automatically. Otherwise, the static `token` value is used as a Bearer token.

### 3.4 Emitter Source Properties

Prefix: `cce.emitter`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `cce.emitter.patient-identifier-system` | String | `http://openphc.org/identifier/upid` | System URI used to match the UPID in `Patient.identifier[]`. When processing a Patient resource, the adaptor searches `identifier[]` for an entry with this system and uses its `value` as the subject. Falls back to `Patient.id` if no match. |
| `cce.emitter.sources.ebuzima.client-id` | String | `ebuzima-emr-client` | OpenHIM client ID for eBUZIMA EMR. Matched against `X-OpenHIM-ClientID` or `X-Source-System` header for adaptor routing. |
| `cce.emitter.sources.<key>.output` | String | follows `beckn.enabled` | **Sygnal.** `collector`: post the CloudEvent to the CCE Collector, as today. `beckn`: wrap it and send it to CCE as a Sygnal `sygnal/event/report`. Not set: `beckn` when `beckn.enabled=true`, else `collector`. See §9. |

### 3.5 Facility Filter Properties

Prefix: `cce.emitter.facility-filter`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `cce.emitter.facility-filter.ids` | List\<String\> | `[]` | `FACILITY_FILTER_IDS` | FOSA facility IDs to allow. Empty list = filter inactive (all events pass). Non-empty = only listed IDs are admitted. Comma-separated in env var form (e.g. `"0030,0042,0099"`). **In YAML, always quote IDs** to preserve leading zeros and avoid integer coercion (e.g. `["0234", "0030"]` — without quotes YAML strips the leading zero). Whitespace trimmed. Stored as `Set<String>` for O(1) lookup. |

**Skip behaviour:** Events with a facility ID that is not in the allowlist return `200 OK` with `status: "skipped"` — they are not forwarded to the Collector and OpenHIM records the transaction as Completed. Events with no facility ID are passed through unconditionally — only events that carry a resolved facility ID are subject to filtering. `FacilityIdExtractor` resolves the facility ID from, for `Encounter`, `hospitalization.origin` first and `location[0].location` as a fallback (per FHIR R4, `hospitalization` is only ever populated on a `TRANSFER_ENCOUNTER`; the `source-facility` extension is deliberately never consulted for `Encounter`); for other types, `locationReference[0]` (e.g. `ServiceRequest`) or a direct `location` reference (e.g. `Procedure`, `Immunization`), falling back to the `source-facility` extension for types with no FHIR location at all (e.g. `Observation`, `Condition`). Any `ResourceType/` prefix is stripped generically so both `Location/1302` and `Organization/1302` compare as `1302`. Resources with no location fields and no extension (e.g. `Patient`, `RelatedPerson`) resolve to `null` and always pass through.

### 3.6 Clinical Data Redaction Properties

Prefix: `cce.emitter.redaction`

CCE is a care **coordination** engine: it needs to know *that* a clinical step happened, when, for which patient and at which facility — not *what the clinical finding was*. Redaction removes the finding before the event leaves the adaptor, so no downstream store (`inbound_event_log`, Kafka, `compliance_event_log`, the ClickHouse mirror) ever receives it.

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `cce.emitter.redaction.enabled` | Boolean | `true` | `REDACTION_ENABLED` | Master switch. `false` forwards full clinical payloads and logs a startup warning. |
| `cce.emitter.redaction.rules` | List\<ResourceRule\> | see below | — (YAML) | One entry per resource type, plus the `"*"` entry. Empty = built-in defaults. |

Each `ResourceRule` is `{ resource-type, fields }`.

#### How rules combine

The rule with `resource-type: "*"` applies to **every** resource. A resource's own rule is applied **on top of it**. So each type lists only what is special about that type, and content that is sensitive everywhere is written once:

```
Condition    →  the "*" list  +  code
Observation  →  the "*" list  +  component      (code deliberately absent)
SomeNewType  →  the "*" list                    (no rule of its own)
```

There is deliberately no second wildcard for "types without a rule of their own": such a type is simply one with nothing to add, and `"*"` already covers it. One token, one meaning.

#### Why rules are per resource type

The same FHIR element carries very different sensitivity depending on the resource. `code` is the clearest case:

| Resource | `code` means | Action |
|----------|--------------|--------|
| `Condition` | the diagnosis — *"Type 2 diabetes mellitus"* (ICD-11 `5A11`) | **removed** |
| `AllergyIntolerance` | the allergen — *"allergy on aminophyline"* | **removed** |
| `ServiceRequest` | the test ordered — *"a1-Acid Glycoprotein"* | **removed** |
| `Observation` | the observation **type** — LOINC `33747-0` "Chief Complaints", `8716-3` "Vital signs" | **kept** — protocol triggers match on it; removing it would stop compliance tracking working |

A single flat field list cannot express this, which is why rules are keyed by resource type.

#### Default rules

The `"*"` rule — applied to every resource. Clinical content that is sensitive wherever it appears and that nothing downstream reads, plus the patient's name:

```
valueQuantity, valueCodeableConcept, valueString, valueBoolean, valueInteger,
valueRange, valueRatio, valueSampledData, valueTime, valueDateTime, valuePeriod,
valueAttachment, note, text, interpretation, dataAbsentReason, bodySite, method,
specimen, referenceRange, contained, severity, stage, evidence, dosageInstruction,
reasonCode, reasonReference, orderDetail, patientInstruction,
comment, description, statusReason, instruction, summary,
conclusion, conclusionCode, outcome,
subject.display, patient.display
```

`subject.display` / `patient.display` are the patient's name — not clinical data, but direct identifying data in the same payload, and nothing downstream reads it; the patient is keyed on `subject.reference` / `patient.reference` (the UPID), which is preserved. Both spellings are needed because `AllergyIntolerance` and several other types use `patient` rather than `subject`.

Plus, per type — each listing **only its additions** to the above. Every resource type observed in Rwanda production has an explicit rule (PROD volumes shown), so a type can never fall through to the wildcard unnoticed:

| `resource-type` | PROD volume | Entries (added to the `"*"` list) | Rationale |
|-----------------|-------------|---------------------------|-----------|
| `Observation` | 141,036 | `component` | Sub-observations, each with their own `value[x]`. **`code` kept** — it is the LOINC trigger key. |
| `Encounter` | 47,708 | `diagnosis`, `hospitalization.dischargeDisposition`, **`extension`** | `type` kept — carries `VISIT_ENCOUNTER` / `CONSULTATION_ENCOUNTER` / `TRANSFER_ENCOUNTER`, which drive protocol matching and the referral KPI. `class`, `serviceType`, `period`, `location`, `hospitalization`, `participant` kept. `reasonCode` removed via the common set. |
| `ServiceRequest` | 32,780 | `code` | The test requested. `category` kept (distinguishes a laboratory order), as are `intent`, `occurrenceDateTime` and `locationReference`. |
| `MedicationRequest` | 30,166 | `medicationCodeableConcept`, `medicationReference`, `dispenseRequest` | The drug, and the quantity/refills. `intent` kept — protocol conditions read it — as are `authoredOn`, `requester`, `status`. |
| `Condition` | 24,972 | `code` | The diagnosis itself. `clinicalStatus` / `verificationStatus` kept — the Rwanda protocol's diagnosis step triggers on them. |
| `MedicationDispense` | 20,472 | `medicationCodeableConcept`, `medicationReference`, `quantity` | The drug and how much. `whenHandedOver` kept — a clinical-time field the SLA engine reads. |
| `Consent` | 15,761 | — (nothing to add) | Carries no clinical finding; `category`, `scope`, `status` and `dateTime` are consent metadata and are what the Consent step matches on. Listed explicitly so it is documented as reviewed. |
| `Procedure` | 4,174 | `code`, `complication` | The procedure performed and its result. `performedDateTime`, `location`, `performer` kept. |
| `MedicationAdministration` | 1,614 | `medicationCodeableConcept`, `medicationReference`, `dosage`, `supportingInformation` | The drug, the amount given, and references that may point at clinical data. |
| `AllergyIntolerance` | 22 | `code`, `reaction` | The allergen and reaction detail. `clinicalStatus` / `verificationStatus` / `onsetDateTime` / `recordedDate` kept. |
| `Immunization` | 0 | `vaccineCode` | Not currently received. Pre-declared so a new feed cannot leak the vaccine given before anyone notices the type is unhandled. |
| `*` | — | the common set above | Applied to **every** resource, including types with no entry of their own. Conservative: leaves `code` alone, since on an unrecognised type it may be structural rather than a finding. `ClinicalDataRedactorTest.coversAllProductionResourceTypes` fails if a production type loses its explicit entry. |

#### Entry syntax

An entry is either a root field name or a dot-delimited path. A segment ending in `[]` steps through an array and applies to *every* element:

```
code                                   → Condition.code
subject.display                        → Observation.subject.display
hospitalization.dischargeDisposition   → Encounter.hospitalization.dischargeDisposition
reaction[].manifestation               → AllergyIntolerance.reaction[*].manifestation
series[].instance[].title              → ImagingStudy.series[*].instance[*].title
```

The `[]` is **required** wherever the data is an array. Without it the path walks into objects only and matches nothing.

> ⚠️ A path that matches nothing is the dangerous failure mode for a compliance control: it looks configured and redacts nothing. This is therefore **not silent** — where a path meets an array without `[]`, the adaptor logs a warning naming the path and the fix, and increments `cce.emitter.redaction.path.mismatch.total`. Alert on that counter being non-zero.

**Prefer removing a whole structure over pathing into it.** `component` on `Observation` drops every sub-observation's `value[x]` in one step. Reach for a path only when part of the structure must survive — the one current case:

| `resource-type` | Path | Why it cannot be a root field |
|-----------------|------|-------------------------------|
| `Encounter` | `hospitalization.dischargeDisposition` | The discharge outcome ("Died in hospital", "Transferred to ICU") is clinical, but its parent `hospitalization` is kept because `hospitalization.origin` is a facility source. |

A path whose structure is simply **absent** from a payload is normal — most encounters have no `hospitalization` — and is not reported. Only a type conflict, which means the configuration can never work, is.

#### `extension` — dropped on Encounter only

eBuzima packs a great deal into custom FHIR extensions on transfer Encounters. Captured from UAT on 2026-09-18, **after** the first redaction build was live:

| Extension | Carries |
|-----------|---------|
| `clinical-presentation` | the diagnosis — *"Plasmodium malariae malaria without complication"* |
| `transfer-flags` | `prescriptions` (*"Paracetamol, CAPTOPRIL 25MG"*), `consultation-motif` |
| `vital-signs` / `extended-vitals` | *"T: 36.0, SpO2: 100.0%, RR: 18.0, Pulse: 80.0, BP: 100.0/78.0…"* |
| `maternity-info` | 19 obstetric fields — fetal heart rate, dilation, blood loss, oxytocin, magnesium sulphate… |
| `anc-info` | LMP, EDD, haemoglobin, **HIV status**, blood group |
| `patient-demographics` | **name, dob, gender, phone, serial-number** |
| `patient-address` | province → district → sector → cell → **village** |
| `clinical-scores`, `lab-results`, `additional-notes` | BMI, lab text, free-text notes |

Redaction never recurses into `extension[]` — that is where `source-facility` lives — so none of this was being removed. `Encounter` therefore drops the **whole `extension` array**.

> ⚠️ **This applies to `Encounter` and nothing else.** For every other resource type the `source-facility` extension is the *only* facility signal the payload carries; dropping `extension` there would break facility attribution platform-wide.

Encounter is safe because its facility is resolved from `hospitalization.origin` → `location[0].location`, both of which are kept. The matcher states this explicitly: *"The source-facility extension is deliberately never consulted for Encounter (superseded by reading hospitalization.origin directly)"* (`FacilityService`). Verified the same in `cce-insights-service` `PatientTimelineService`, and confirmed no service reads any of the transfer/referral extension URLs.

Guarded by `RedactionConfigBindingTest.encounterExtensionsAreDropped` and
`nonEncounterTypesKeepTheSourceFacilityExtension`.

#### What is preserved, and why

Protocol matching, SLA evaluation, facility attribution or the Insights dashboard read each of these: `resourceType`, `category`, `type`, `class`, `status`, `clinicalStatus`, `verificationStatus`, `serviceType`, `intent`, `identifier`, `subject.reference`, `subject.identifier`, `encounter`, `performer`/`participant`/`requester`/`asserter`, every clinical-time field (`effectiveDateTime`, `issued`, `period`, `occurrenceDateTime`, `authoredOn`, `onsetDateTime`, `recordedDate`, …), and every facility source (`location[]`, `locationReference`, `hospitalization.origin`, and the `source-facility` / `location` extensions).

#### Behaviour notes

- **Never a blanket recursive scrub.** Named root-level fields plus explicitly configured nested paths. A recursive scrub would also strip the `valueString` *inside* `extension[]` — which is how `source-facility` attribution works — and the `coding`/`display` elements the dashboard renders. Every nested removal is something a human chose.
- **Removing a whole structure is cheaper and safer than pathing into it.** Removing a named field drops the entire subtree in one step, so `component` on `Observation` takes every sub-observation's `value[x]` with it. Reach for a dotted path only when part of the structure must survive.
- **Entry order does not matter.** Each removal is independent, and the `"*"` rule and the type's own rule are simply applied in turn. Listing `component` first or last changes nothing.
- **Ordering guarantee.** Redaction runs inside `CloudEventEnvelopeBuilder.build()`, i.e. *after* `PatientIdExtractor`, `FacilityIdExtractor` and clinical-time extraction have read the complete resource. Stripping clinical content therefore cannot affect routing, facility attribution or SLA timing.
- **FHIR validity.** The Collector re-parses every payload with HAPI and rejects malformed bodies as `INVALID_FHIR`. Redacted payloads remain structurally valid FHIR R4 — covered by `RedactedPayloadStillValidFhirTest`.
- **Logging.** Redaction log lines record field **names** only, never their values.
- **Scope.** Applies to events from deployment onward. Payloads stored before the change are unaffected and would need a separate backfill.
- **Known trade-off.** The Insights "zero-match events" drill-down displays `code.text` / `code.coding[0].display` for every resource type. For `Condition`, `AllergyIntolerance` and `ServiceRequest` that column will now be blank, since `code` no longer reaches the database. This is a deliberate consequence of removing the diagnosis/allergen/test name.

### 3.7 Server Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `server.port` | int | `8082` | Application HTTP port |
| `management.endpoints.web.exposure.include` | String | `health,info,prometheus,metrics` | Exposed Actuator endpoints |

## 4. OpenHIM Registration Descriptor

### 4.1 MediatorDescriptor Fields

| Field | Type | Description |
|-------|------|-------------|
| `urn` | String | `"urn:mediator:cce-emitter-adaptor"` |
| `version` | String | `"1.0.0"` |
| `name` | String | `"CCE Emitter Adaptor"` |
| `description` | String | Human-readable description |
| `defaultChannelConfig` | Array | **Empty array `[]`** — no channel auto-provisioning (see §4.2) |
| `endpoints` | Array | Mediator endpoint definitions |

### 4.2 Channel Configuration (Manual — No Auto-Provisioning)

The mediator does **not** auto-provision an OpenHIM channel. The `defaultChannelConfig` array in the registration descriptor is intentionally **empty**.

**Reason:** The eBUZIMA EMR already has an existing OpenHIM channel that routes its requests to the SHR and other downstream systems via other mediators. Auto-provisioning a new channel could conflict with or override the existing routing.

**Deployment approach:** The OpenHIM administrator adds the CCE Emitter Adaptor as a **secondary route** on the existing eBUZIMA channel. This way, OpenHIM Core forwards a copy of each eBUZIMA request to both the existing primary mediator (e.g., SHR) and our CCE Emitter Adaptor simultaneously.

#### Secondary Route Entry

The admin adds the following route to the existing eBUZIMA channel via the OpenHIM Console:

```json
{
  "name": "CCE Emitter Adaptor",
  "host": "cce-emitter-adaptor",
  "port": 8082,
  "path": "/inbound",
  "primary": false,
  "type": "http"
}
```

| Field | Value | Description |
|-------|-------|-------------|
| `name` | `CCE Emitter Adaptor` | Display name in OpenHIM Console |
| `host` | `cce-emitter-adaptor` | Hostname/IP of the adaptor (Docker service name or IP) |
| `port` | `8082` | Adaptor HTTP port |
| `path` | `/inbound` | Adaptor inbound endpoint |
| `primary` | `false` | **Secondary route** — does not affect the primary response to OpenHIM |
| `type` | `http` | HTTP route |

## 5. Collector Response Model

### 5.1 Success (202 Accepted)

```json
{
  "data": {
    "eventId": "evt-uuid",
    "status": "accepted",
    "correlationId": "corr-uuid",
    "timestamp": "2026-02-25T08:00:04.500Z"
  }
}
```

### 5.2 Duplicate (200 OK)

```json
{
  "data": {
    "eventId": "evt-uuid",
    "status": "duplicate",
    "correlationId": "corr-uuid",
    "timestamp": "2026-02-25T08:00:04.500Z"
  }
}
```

### 5.3 Error (400 Bad Request)

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Missing required field: type"
  }
}
```

## 6. OpenHIM Response Envelope

### 6.1 OpenHimResponse Fields

| Field | Type | Description |
|-------|------|-------------|
| `x-mediator-urn` | String | Mediator URN |
| `status` | String | `"Successful"` or `"Failed"` |
| `response` | Object | Primary response body |
| `response.status` | int | HTTP status code |
| `response.headers` | Object | Response headers |
| `response.body` | String | JSON-stringified response body |
| `response.timestamp` | String | ISO-8601 timestamp |
| `orchestrations` | Array | List of downstream calls made |

### 6.2 Orchestration Entry

| Field | Type | Description |
|-------|------|-------------|
| `name` | String | Descriptive name (e.g., `"Forward to CCE Collector"`) |
| `request.method` | String | HTTP method |
| `request.path` | String | Request URL path |
| `request.body` | String | Request body (stringified) |
| `request.timestamp` | String | When request was sent |
| `response.status` | int | Response HTTP status |
| `response.body` | String | Response body (stringified) |
| `response.timestamp` | String | When response was received |

### 6.3 ProcessedEventsResponse (Success Body)

The `response.body` field in a successful (202) OpenHIM envelope contains a `ProcessedEventsResponse` — a typed summary of all forwarded events.

| Field | Type | Description |
|-------|------|-------------|
| `status` | String | Always `"processed"` |
| `eventsForwarded` | int | Number of events forwarded to the Collector |
| `events` | Array | Per-event detail list |
| `events[].eventId` | String | The CloudEvent ID |
| `events[].type` | String | FHIR `resourceType` (CloudEvent `type`) |
| `events[].subject` | String | Patient UPID |
| `events[].collectorStatus` | String | Collector-reported status (`"accepted"` or `"duplicate"`) |

**Example (serialized inside `response.body`):**

```json
{
  "status": "processed",
  "eventsForwarded": 1,
  "events": [
    {
      "eventId": "evt-eb010001-0001-4000-8000-000000000001",
      "type": "Encounter",
      "subject": "260225-0002-5501",
      "collectorStatus": "accepted"
    }
  ]
}
```

## 7. Metrics Reference

Registered in `InboundEventService` and `CollectorForwardingService` via constructor-injected `MeterRegistry`.

| Metric Name | Type | Tags | Registered In | Description |
|-------------|------|------|---------------|-------------|
| `cce.emitter.events.received` | Counter | `source`, `path` | `InboundEventService` | Total inbound events received |
| `cce.emitter.events.forwarded` | Counter | `source` | `InboundEventService` | Events successfully forwarded to Collector |
| `cce.emitter.events.duplicate` | Counter | — | `InboundEventService` | Duplicate events (Collector returned 200) |
| `cce.emitter.events.rejected` | Counter | — | `CollectorForwardingService` | Events rejected by Collector (4xx) |
| `cce.emitter.collector.latency` | Timer | — | `CollectorForwardingService` | Collector forwarding round-trip latency |
| `cce.emitter.collector.retries` | Counter | — | `CollectorForwardingService` | Retry attempts exhausted |
| `cce.emitter.events.filtered` | Counter | `source`, `facility`, `reason` | `FacilityFilter` | Events skipped by facility filter (not forwarded). `reason` value: `NOT_IN_ALLOWLIST`. |
| `cce.emitter.events.redacted.total` | Counter | `resource_type` | `ClinicalDataRedactor` | Events from which at least one clinical field was removed. Incremented once per event, not once per field. A sustained drop to zero while `events.received` stays healthy means redaction has been switched off or the inbound payload shape has changed. |
| `cce.emitter.redaction.path.mismatch.total` | Counter | `resource_type`, `path` | `ClinicalDataRedactor` | A configured dotted path matched nothing because an array was found where an object was expected. **Non-zero means that path is redacting nothing** — add `[]` to the segment named in the accompanying warning. |

## 8. MDC Context Fields

`InboundEventService` populates SLF4J MDC per-event with `try/finally` to ensure cleanup. These fields are included in all log output during event processing.

| MDC Key | Source | Description |
|---------|--------|-------------|
| `correlationId` | `X-OpenHIM-TransactionID` or `X-Correlation-Id` header, or generated | Trace correlation ID |
| `source` | Resolved source key | Source system identifier (e.g., `"ebuzima"`) |
| `eventType` | FHIR `resourceType` | CloudEvents `type` field |
| `subject` | Patient UPID | Patient identifier for the event |

## 9. Sygnal Fields and Settings (Proposal)

> **Status:** Built on branch `sygnal-actions`, on by default (`beckn.enabled=true`; set `OPENHIM_EMITTER_BECKN_ENABLED=false` to turn it off). Full design: [Beckn Integration](beckn-integration.md). With `beckn.enabled=false` and every source on `output: collector`, the CloudEvent and the collector output in §1 are unchanged. The feature is called Sygnal; the settings keep the `beckn.*` prefix and the `OPENHIM_EMITTER_BECKN_*` environment variables.

### 9.1 `sygnal/event/report` Message and `sygnal:CoordinationEvent`

Both legs, HMIS → HIE (inbound) and HIE → CCE (outbound), carry a Sygnal `sygnal/event/report` with **exactly one** event. The event, `message.events[0]`, is a `sygnal:CoordinationEvent` as CCE's profile v3.0 extends it: the fields from `patientId` down are added to the type in CCE's copy of the schema (`CoordinationEventFhirExtension` in `cce-fhir-extension.yaml`, which `cce-sygnal-apis.yaml` refers to; both in `schema/sygnal-profile/v3.0/` of `cce-beckn-service`), and the type is open. Since 2026-11-23 there is no `cce:CceClinicalEvent`, no `eventAttributes` bag and no `@context` in the message ([Beckn Integration](beckn-integration.md), "Change 2026-11-23"). There is no contract and no commitment, and no callback follows.

| Context field | Limit / check | Description |
|---------------|---------------|-------------|
| `context.version` | — | `2.0.0` |
| `context.action` | Must be `sygnal/event/report` | Else `Nack` 400 `CTX_ACTION_MISMATCH` |
| `context.networkId` | Required | Outbound, `beckn.self.network-id` |
| `context.senderId` | Required. Inbound: a `beckn.subscribers[].subscriber-id`. At most 100 characters (CCE) | Outbound, `beckn.self.id`. CCE uses it as CloudEvent `source` |
| `context.receiverId` | Required. Inbound: must be `beckn.self.id` | Outbound, `beckn.cce.id` |
| `context.receiverUri` | — | Outbound, `beckn.cce.uri` (CCE's public onix receiver base). onix sends the message to it + `/sygnal/event/report` |
| `context.transactionId` | UUID | Outbound, a new UUID per event |
| `context.messageId` | Required. At most 100 characters (CCE) | Echoed in the answer when it is a UUID. Outbound, a new UUID per try. CCE uses it as CloudEvent `correlationid` |
| `context.timestamp` | ISO-8601 | Outbound, the time of sending |

| Event field | Required | Limit / check | Description |
|-------------|----------|---------------|-------------|
| `@type` | Yes | Must be `sygnal:CoordinationEvent` | Else `Nack` 400 `SCH_TYPE_NOT_SUPPORTED`. This also refuses the `cce:CceClinicalEvent` of profile v2.0 |
| `id` | Yes | A full UUID (CCE allows at most 50 characters) | The event id. The same id on every hop and every retry: the HMIS's id becomes this adaptor's CloudEvent `id`, which is the outbound event `id`, which CCE uses as its CloudEvent `id`. So a resent copy is a duplicate |
| `revision` | No | When present, the number 1 | Else `Nack` 400 `SCH_SCHEMA_VALIDATION_FAILED`. CCE never replaces an event; a correction is a new event with a new id. Outbound, always `1` |
| `eventType` | Yes | A Sygnal event type (checked by onix) | Sygnal's kind of event: `DELIVERY_RECORDED` for a clinical event. Outbound, always `DELIVERY_RECORDED`. Not the kind of clinical event, which is `resource.resourceType` |
| `granularity` | No | `INDIVIDUAL` or `AGGREGATE` (checked by onix) | Outbound, always `INDIVIDUAL` |
| `from` | No | When `from.id` is present, it must equal `context.senderId` | Else `Nack` 400 `SCH_SCHEMA_VALIDATION_FAILED`. Outbound, `{ "id": beckn.self.id, "role": "PROVIDER" }` |
| `occurredAt` | Yes | ISO-8601 date-time | When the clinical event happened. Outbound, the CloudEvent `time`. CCE uses it as its CloudEvent `time` |
| `patientId` | Yes | — | Patient UPID. Outbound, the CloudEvent `subject` |
| `fhirVersion` | Yes | `4.0.1` | Always `4.0.1` |
| `facilityId` | No | — | Facility id |
| `facilityName` | No | — | Facility display name. Not sent by this adaptor |
| `sourceEventId` | No | — | The sender's own id for the event |
| `resource` | Yes | An object with `resourceType` | One FHIR R4 resource. Else `Nack` 400 `SCH_SCHEMA_VALIDATION_FAILED`; this also refuses the event of profile v1.0, which has its resource in an `eventAttributes` bag. Its `resourceType` is the kind of clinical event |

The type is open: a field that the profile does not declare is not refused, by onix or by this adaptor, and this adaptor ignores it. `@context` is not needed. This adaptor itself sends the fields above, in this order, and no other. An optional field without a value is left out.

The error codes of each check are in [API Reference §8](api-reference.md#8-sygnal-endpoint-and-call-proposal).

### 9.2 Inbound Event → Channel Request

| Channel request part | Taken from |
|----------------------|------------|
| Body | `events[0].resource`, as it is |
| `X-Source-System` | `beckn.subscribers[].source-system` of the subscriber matched by `context.senderId` |
| `X-Source-Event-Id` | `events[0].sourceEventId` when present and header-safe (printable ASCII, at most 200 characters), else `events[0].id`. Becomes CloudEvent `sourceeventid` |
| `X-Sygnal-Event-Id` | `events[0].id`. The CloudEvent `id`, when it is a full UUID (`EventIdGenerator`); else ignored |
| `X-Sygnal-Occurred-At` | `events[0].occurredAt`. The CloudEvent `time`, when it parses as an ISO-8601 offset date-time; else the time of receipt |
| `X-Correlation-Id` | `context.messageId`, when header-safe. Used for `correlationid` only if `X-OpenHIM-TransactionID` is absent, which it is not when the request comes through OpenHIM |
| `X-Facility-Id` | Not sent. Facility comes from the resource (`FacilityIdExtractor`) |

Without the `X-Sygnal-*` headers (a request that did not come in over Sygnal) nothing changes: CloudEvent `id` = UUID v5 of `<source key>:<X-Source-Event-Id>`, or a random UUID; `time` = time of receipt.

The event's `eventType`, `granularity`, `patientId`, `fhirVersion`, `facilityId` and `facilityName` are not sent to the channel. The pipeline reads patient and facility from the resource.

### 9.3 CloudEvent → Outbound Event

| Event field | From the CloudEvent (§1.1) | Note |
|-------------------|----------------------------|------|
| `@type` | — | `sygnal:CoordinationEvent` |
| `id` | `id` | Fixed per event. For an event that came in over Sygnal, the HMIS's event id. CCE uses it unchanged as its CloudEvent `id`, so a resent copy is a duplicate (CCE answers `202 ACK` for it too) |
| `revision` | — | `1` |
| `eventType` | — | `DELIVERY_RECORDED` |
| `granularity` | — | `INDIVIDUAL` |
| `from` | — | `{ "id": beckn.self.id, "role": "PROVIDER" }` |
| `occurredAt` | `time` | For an event that came in over Sygnal, the HMIS's `occurredAt`; else the time of receipt |
| `patientId` | `subject` | |
| `fhirVersion` | — | `4.0.1` |
| `facilityId` | `facilityid` | Left out when blank |
| `facilityName` | `facilityname` | Not in `CloudEventDto` today. Left out |
| `sourceEventId` | `sourceeventid` | Left out when blank |
| `resource` | `data` | Already redacted (§3.6) |
| — | `type` | Not a field: the FHIR resource type is `resource.resourceType`, which CCE uses as its CloudEvent `type` |
| Not carried | `specversion`, `source`, `datacontenttype`, `correlationid`, `protocolinstanceid`, `protocoldefinitionid`, `actionid` | `cce-beckn-service` rebuilds the CloudEvent: `source` = `context.senderId` (`beckn.self.id`), `time` = `occurredAt`, `correlationid` = the outbound `context.messageId`, `datacontenttype` = `application/fhir+json`. The protocol fields are lost. Full mapping: [Beckn Integration §5](beckn-integration.md) |

### 9.4 Sygnal Settings

Prefix: `beckn`

| Property | Env var | Type | Default | Description |
|----------|---------|------|---------|-------------|
| `beckn.enabled` | `OPENHIM_EMITTER_BECKN_ENABLED` | boolean | `true` | Turns on `POST /sygnal/event/report` and allows `output: beckn`. Sources without an explicit `output` follow it |
| `beckn.schema-base-url` | `OPENHIM_EMITTER_BECKN_SCHEMA_BASE_URL` | String | `https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema` | Base URL of CCE's schema files (`cce-beckn-service`'s `schema/` folder). Not used in a message since profile v3.0: no object of the report carries an `@context`. Still read, so a deployment that sets it needs no change |
| `beckn.onix.caller-url` | `OPENHIM_EMITTER_BECKN_ONIX_CALLER_URL` | String | `http://onix-openhim:8082/sygnal/caller` | `onix-openhim`'s caller. The adaptor appends `/sygnal/event/report` |
| `beckn.onix.timeout` | `OPENHIM_EMITTER_BECKN_ONIX_TIMEOUT_MS` | int | `20000` | Connect + read timeout for the onix call, in ms. Must stay below the OpenHIM channel timeout and `beckn.openhim-channel.timeout`. See [Beckn Integration §8.3](beckn-integration.md#83-every-answer-returns-in-the-same-call) |
| `beckn.self.id` | `OPENHIM_EMITTER_BECKN_SELF_ID` | String | `bap.openhim.nextsteps.mdtlabs.org` | The HIE's one Sygnal subscriber id: `senderId` towards CCE, `receiverId` the HMIS systems must use. At most 100 characters (CCE's CloudEvent `source`) |
| `beckn.self.network-id` | `OPENHIM_EMITTER_BECKN_SELF_NETWORK_ID` | String | `sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry` | Required. `context.networkId` of every message |
| `beckn.cce.id` | `OPENHIM_EMITTER_BECKN_CCE_ID` | String | `bpp.cce.nextsteps.mdtlabs.org` | CCE's subscriber id: `context.receiverId` |
| `beckn.cce.uri` | `OPENHIM_EMITTER_BECKN_CCE_URI` | String | — | Required. CCE's public onix receiver base, e.g. `https://cce.example.org/sygnal/receiver`: `context.receiverUri` |
| `beckn.cce.retry.max-attempts` | — | int | `1` | Tries per `sygnal/event/report` to CCE. No retry inside the route: a second try would not fit the channel's timeout. The HMIS resends |
| `beckn.openhim-channel.url` | `OPENHIM_EMITTER_BECKN_OPENHIM_CHANNEL_URL` | String | `https://openhim-core:5001/cce-beckn` | OpenHIM channel the Sygnal endpoint posts to |
| `beckn.openhim-channel.username` | `OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_ID` | String | `cce-beckn-endpoint` | OpenHIM client for the Sygnal endpoint. Must not equal any `cce.emitter.sources.<key>.client-id` |
| `beckn.openhim-channel.password` | `OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_PASSWORD` | String | — | Its password |
| `beckn.openhim-channel.timeout` | `OPENHIM_EMITTER_BECKN_OPENHIM_CHANNEL_TIMEOUT_MS` | int | `25000` | Wait for the channel's answer, in ms. Below the 30 s onix waits for this adaptor |
| `beckn.subscribers[].subscriber-id` | — | String | — | An HMIS's Sygnal subscriber id (its `context.senderId`), e.g. `bap.openmrs.nextsteps.mdtlabs.org` |
| `beckn.subscribers[].source-system` | — | String | — | Source key under `cce.emitter.sources`, sent as `X-Source-System` |

`BecknStartupValidator` checks these at start. Errors stop the start: a source with `output: beckn` while `beckn.enabled=false`; with `beckn.enabled=true`, a missing `onix.caller-url`, `self.id`, `self.network-id`, `cce.id`, `cce.uri`, `openhim-channel.url` or `openhim-channel.username`; `retry.max-attempts` below 1; `self.id` over 100 characters; `openhim-channel.username` equal to a source's `client-id`; a subscriber without `subscriber-id` or `source-system`, a duplicate `subscriber-id`, or a `source-system` that is not a key under `cce.emitter.sources`. Warnings only: a subscriber's source has `output: collector`; `onix.timeout` × `max-attempts` ≥ `openhim-channel.timeout`; `openhim-channel.timeout` ≥ 30000.

### 9.5 Sygnal Metrics and MDC

Metrics are listed in [Beckn Integration §11](beckn-integration.md#11-metrics) and [Monitoring & Alerting](monitoring-alerting.md). MDC keys: `becknMessageId`, `becknTransactionId`, `becknAction` (`sygnal/event/report`), `subscriber` (the `senderId`), alongside those in §8.
