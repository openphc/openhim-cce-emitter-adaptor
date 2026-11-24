# CCE Emitter Adaptor — Sygnal Integration

**Status:** Built on `sygnal-actions`, on by default (`beckn.enabled=true`; set `OPENHIM_EMITTER_BECKN_ENABLED=false` to turn it off). Where the code differs from the design below, §16 says how.
**Branch:** `sygnal-actions`
**Background:** The CCE profile of Sygnal, `cce-beckn-service/schema/sygnal-profile/v3.0/` (`cce-sygnal-apis.yaml`, which refers to `cce-fhir-extension.yaml`), is the wire contract. *Design: Beckn support for CCE* and the *HMIS integration guide* (`cce-beckn-service/docs/hmis-integration-guide.md`) describe `cce-beckn-service`. This branch replaces the earlier Beckn `update` / `on_update` design.

**Change 2026-11-23: the payload is the extended `sygnal:CoordinationEvent` (profile v3.0).** On 2026-11-23 the customer decided to open and extend CCE's copy of `sygnal:CoordinationEvent` (profile v3.0), instead of introducing a CCE type of its own, `cce:CceClinicalEvent`, as the payload (profile v2.0, proposed the same day and never released here). So the event of a `sygnal/event/report` is a `sygnal:CoordinationEvent` on both hops, with the fields of a clinical event directly on it: `patientId`, `fhirVersion`, `resource`, and optionally `facilityId`, `facilityName` and `sourceEventId`. There is no `cce:CceClinicalEvent` in the message, no `eventAttributes` bag (profile v1.0, 2026-11-20) and no `@context`. Sygnal's published schema is not changed: the type is opened and extended in CCE's copy only, which every participant of this network loads. The reasons and the history are in the wire contract, `schema/sygnal-profile/v3.0/cce-sygnal-apis.yaml` in `cce-beckn-service`.

Only the payload changed: the action, the context, the endpoints, the answers, the time limits, the settings, the OpenHIM channel flow and the `X-Sygnal-*` headers are as before. This adaptor refuses the older shapes: a `cce:CceClinicalEvent` (profile v2.0) with `Nack` (400) `SCH_TYPE_NOT_SUPPORTED`, and an event that has its FHIR resource in an `eventAttributes` bag (profile v1.0) with `Nack` (400) `SCH_SCHEMA_VALIDATION_FAILED`, because `events[0].resource` is missing.

**Change 2026-11-23 (later the same day): the profile is two files.** Profile v3.0 is now two files in one folder, `schema/sygnal-profile/v3.0/` in `cce-beckn-service`: `cce-sygnal-apis.yaml` (Sygnal's own fields; the types are open, `additionalProperties: true`) and `cce-fhir-extension.yaml` (`CoordinationEventFhirExtension`, `SignalFhirExtension`: the FHIR fields), which the first refers to. A Sygnal adapter such as `onix-openhim` loads the first as its spec; the second must sit in the same folder. The messages on the wire are unchanged, and so is this adaptor. The same day the `cce:CceClinicalEvent` and `cce:CceCareAlert` schemas (`CceClinicalEvent/v1.0`, `CceCareAlert/v1.0`) were deleted from `cce-beckn-service`. The OpenMRS ↔ Spice referral messages (standard Beckn, not through the HIE) now carry the same `sygnal:CoordinationEvent`, with an `@context`, in their `performanceAttributes` (schema `CoordinationEvent/v3.0`).

**Change 2026-11-24: Sygnal's types word for word.** CCE's copy of the Sygnal schema now keeps `sygnal:CoordinationEvent` and `sygnal:Signal` exactly as Sygnal v0.9.0 publishes them, except `additionalProperties: true`. CCE's rules (the event `id` is required and a UUID, `revision` is 1) moved into `cce-fhir-extension.yaml`, next to the FHIR fields. The event on the wire, this adaptor's code and its settings are unchanged; `onix-openhim` only needs the new copies of the two profile files. (CCE's alerts now carry CCE's level of severity in `cceSeverity`, a field of its own on the Signal; they do not pass through this adaptor.)

**Scope:** Let an HMIS (OpenMRS) send clinical events to CCE over a Sygnal network, through the HIE. This adaptor unwraps what comes in, runs it through the OpenHIM channel and its existing pipeline, and wraps the result for CCE.

**Naming.** This document calls the feature "Sygnal". The settings keep the `beckn.*` prefix, the environment variables keep the `OPENHIM_EMITTER_BECKN_*` prefix, the output value is still `beckn`, and several classes and metrics keep `Beckn` / `beckn` in their names.

**Unchanged when off.** With `beckn.enabled=false` and no source set to `output: beckn`, the adaptor behaves exactly as before: OpenHIM channel → `/inbound` → CloudEvent → CCE collector. A source that keeps posting FHIR through OpenHIM keeps going to the collector.

All hosts below are made up, except where they come from `cce-beckn-service` or from the defaults in `application.yml`.

**Towards CCE, this adaptor is the HMIS.** CCE sees the HIE's `senderId` (`beckn.self.id`) as the sender. So `cce-beckn-service`'s *HMIS integration guide* applies in full to the `sygnal/event/report` this adaptor sends. What this document says about CCE is taken from that guide and from the `cce-beckn-service` code.

---

## 1. Summary

- **This adaptor does the Sygnal work in the HIE.** There is no separate HIE service.
- **One Sygnal action on both hops.** The HMIS sends `sygnal/event/report` to the HIE, and the HIE sends `sygnal/event/report` to CCE. No other action is used.
- **New inbound endpoint.** `POST /sygnal/event/report` takes a report from an HMIS, takes the FHIR resource out of it, and posts the resource to an OpenHIM channel.
- **The pipeline stays as it is.** The channel routes to the existing `/inbound`. Source resolution, FHIR parsing, patient id, facility id, clinical data redaction and the CloudEvent are unchanged. The only addition: the CloudEvent keeps the HMIS's event id and time (§3.2).
- **New output, chosen per source.** `cce.emitter.sources.<key>.output` is `collector` (the old behaviour) or `beckn`. A source without an explicit output follows `beckn.enabled`: `beckn` when it is on (the default on this branch), else `collector`. With `beckn`, the CloudEvent is mapped to an extended `sygnal:CoordinationEvent` and sent to CCE as the one event of a `sygnal/event/report` through `onix-openhim`.
- **No callbacks.** Sygnal has no `on_update`, `on_status` or `on_report` for a report. The synchronous HTTP answer is the whole result, on both hops.
- **One answer shape.** The endpoint answers with the `Ack` (202) / `Nack` body CCE uses, with canonical Beckn error codes (§3.3). CCE's own answer is read the same way and passed back to the HMIS (§5.4).
- **No queue, no database.** Every answer comes back in the same HTTP call, hop by hop. All ids and addresses are settings.
- **Redaction stays here.** CCE gets the same minimised content as on the collector path.

The HIE's Sygnal adapter is one onix container, `onix-openhim`, with two modules: `/sygnal/receiver/` (towards the HIE: it receives from the HMIS systems) and `/sygnal/caller/` (from the HIE: it sends to CCE). It belongs to the HIE team. It signs what goes out and checks the signature and schema of what comes in. It needs the CCE Sygnal profile v3.0 as two files in one folder: `cce-sygnal-apis.yaml`, loaded as its spec, and `cce-fhir-extension.yaml`, which the first refers to.

---

## 2. Flows

```
---- OpenMRS to CCE ----
OpenMRS emitter → onix-openmrs /sygnal/caller ══► onix-openhim /sygnal/receiver → openhim-emitter-adaptor POST /sygnal/event/report (unwrap: FHIR + headers) → OpenHIM channel
   → openhim-emitter-adaptor POST /inbound (existing pipeline, CloudEvent) → wrap → onix-openhim /sygnal/caller ══► onix-cce /sygnal/receiver → cce-beckn-service POST /sygnal/event/report → CCE
---- CCE to OpenMRS (alerts) ----   (does not touch the HIE)
cce-beckn-service → onix-cce /sygnal/caller ══(sygnal/signal/emit)══► onix-openmrs /sygnal/receiver → openmrs-receiver-adaptor → OpenMRS
```

══ marks a Sygnal hop: signed by the sender's onix and checked by the receiver's onix. → marks plain HTTP. `openhim-emitter-adaptor` is this adaptor.

Only the first flow touches this adaptor. Inside the HIE it looks like this:

```
onix-openhim /sygnal/receiver ──► CCE Emitter Adaptor: POST /sygnal/event/report
  (from the HMIS)                     1. unwrap: FHIR + headers ──► OpenHIM channel /cce-beckn (client auth, transaction log)
                                                                         │
                                  CCE Emitter Adaptor: POST /inbound ◄───┘
                                      2. existing pipeline → CloudEvent
                                      3. output for the source:
                                           collector → POST {collector}/v1/events                    (the old path)
                                           beckn     → wrap → POST onix-openhim /sygnal/caller/sygnal/event/report
                                                              └─► onix-openhim ══► onix-cce → cce-beckn-service :8075 /sygnal/event/report
  ◄── 202 Ack / Nack, in the same call (no callback)
```

### 2.1 Identities

One subscriber id per participant. Each participant has its own onix container, listening on 8082 inside the container.

| Participant | Subscriber id (`senderId` / `receiverId`), default | onix container | App endpoint onix forwards to |
|---|---|---|---|
| HMIS (OpenMRS) | `bap.openmrs.nextsteps.mdtlabs.org` | `onix-openmrs` | — (sends only, on this flow) |
| HIE (this adaptor) | `bap.openhim.nextsteps.mdtlabs.org` = `beckn.self.id` | `onix-openhim` | `POST /sygnal/event/report` (port 8082) |
| CCE | `bpp.cce.nextsteps.mdtlabs.org` = `beckn.cce.id` | `onix-cce` | `POST /sygnal/event/report` (port 8075) |

How onix is used:

| Direction | The app does | onix does |
|---|---|---|
| Send | POSTs the full message to `http://onix-openhim:8082/sygnal/caller/<action>` | Signs it and delivers it to `context.receiverUri` + `/` + action |
| Receive | Exposes the action as its path: `POST /sygnal/event/report` | Receives at `https://<public host>/sygnal/receiver/<action>`, checks signature and schema, forwards to the app |

There are no contracts, contract ids or commitments, and the context has no `bapId`, `bapUri`, `bppId` or `bppUri`. onix refuses a context with any of them.

---

## 3. Role 1: the `sygnal/event/report` endpoint

`POST /sygnal/event/report`, called by `onix-openhim`'s receiver. onix has already checked the signature and the schema. The action is the path. The endpoint exists only with `beckn.enabled=true`.

### 3.1 Steps

| Step | What the adaptor does | If it fails |
|---|---|---|
| 1 | Checks `context.action` is `sygnal/event/report` | `Nack` (400) `CTX_ACTION_MISMATCH` |
| 2 | Finds the HMIS by `context.senderId` in `beckn.subscribers[]` | Missing: `Nack` (400) `CTX_MISSING_FIELD`. Not a subscriber: `Nack` (400) `CTX_INVALID_FIELD` |
| 3 | Checks `context.receiverId` is this HIE (`beckn.self.id`) | Missing: `CTX_MISSING_FIELD`. Another id: `CTX_INVALID_FIELD` |
| 4 | Checks `context.messageId` is present | `CTX_MISSING_FIELD` |
| 5 | Checks `message.events` holds **exactly one** event | `SCH_SCHEMA_VALIDATION_FAILED`. None or more than one is refused |
| 6 | Checks `events[0].@type` is `sygnal:CoordinationEvent` | `SCH_TYPE_NOT_SUPPORTED`. This also refuses the `cce:CceClinicalEvent` of profile v2.0 |
| 7 | Checks `events[0].id` is present and a full UUID | Missing: `SCH_REQUIRED_FIELD_MISSING`. Not a UUID: `SCH_SCHEMA_VALIDATION_FAILED` |
| 8 | Checks `events[0].revision`, when present, is the number 1 | `SCH_SCHEMA_VALIDATION_FAILED`. CCE never replaces an event; a correction is a new event |
| 9 | Checks `events[0].from.id`, when present, equals `context.senderId` | `SCH_SCHEMA_VALIDATION_FAILED` |
| 10 | Checks `events[0].occurredAt` is present and an ISO-8601 date-time | Missing: `SCH_REQUIRED_FIELD_MISSING`. Not a date-time: `SCH_SCHEMA_VALIDATION_FAILED` |
| 11 | Checks `events[0].resource` is an object with a `resourceType` | `SCH_SCHEMA_VALIDATION_FAILED`. This also refuses the event of profile v1.0, which has its resource in an `eventAttributes` bag |
| 12 | Posts the resource, as it is, to the OpenHIM channel URL, with the OpenHIM client credentials and the headers below | See §3.3 |
| 13 | Maps the channel's answer to `Ack` or `Nack` (§3.3) and returns it | — |

Every failed check is a `Nack` (400) whose text names the field. Nothing is sent to OpenHIM then.

Before step 1:

| Problem | Answer |
|---|---|
| Body is not JSON | `Nack` (400) `SCH_INVALID_JSON`, new `messageId` |
| Spring refuses the request with a 4xx (for example a `Content-Type` that is not JSON, 415) | That status, `SCH_INVALID_JSON`, new `messageId` |
| Unexpected exception | `Nack` (500) `NET_INTERNAL_ERROR` |

The event is a `sygnal:CoordinationEvent` as CCE's profile extends it (the FHIR fields are `CoordinationEventFhirExtension` in `cce-fhir-extension.yaml`). It holds Sygnal's own fields (`id`, `revision`, `eventType`, `granularity`, `from`, `occurredAt`) and, directly next to them, the fields of a clinical event: `patientId`, `fhirVersion`, `resource` and optionally `facilityId`, `facilityName` and `sourceEventId`. The type is open: neither onix nor this adaptor refuses a field that the profile does not declare, and this adaptor ignores it. `@context` is not needed. Only `resource` goes to the channel as the body; `id`, `occurredAt` and `sourceEventId` go as headers (§3.2). The adaptor does not use `eventType`, `granularity`, `patientId`, `fhirVersion`, `facilityId` and `facilityName` (onix has checked them against the profile): the pipeline reads the patient and the facility from the resource itself, as on the old path. The resource type is logged.

### 3.2 Headers sent to the channel

| Header | Value | Read by the pipeline in | Effect |
|---|---|---|---|
| `Authorization` | Basic auth of the OpenHIM client for this endpoint | OpenHIM Core | Client auth on the channel |
| `Content-Type` | `application/json` | — | As before |
| `X-Source-System` | The subscriber's `source-system` | `SourceAdaptorService.resolveSource()` | Selects the source key, so also the output (§5) |
| `X-Source-Event-Id` | `events[0].sourceEventId` when present and header-safe (printable ASCII, at most 200 characters); else `events[0].id` | `SourceAdaptorService.buildSourceMetadata()` | CloudEvent `sourceeventid` |
| `X-Sygnal-Event-Id` | `events[0].id` | `SourceAdaptorService.buildSourceMetadata()` → `EventIdGenerator` | CloudEvent `id`, unchanged, when it is a full UUID (it always is after step 7). So CCE gets the HMIS's own event id |
| `X-Sygnal-Occurred-At` | `events[0].occurredAt` | `SourceAdaptorService.buildSourceMetadata()` | CloudEvent `time`, when it parses as an ISO-8601 date-time with offset; else the time of receipt |
| `X-Correlation-Id` | `context.messageId`, when header-safe | `SourceAdaptorService.buildSourceMetadata()` | CloudEvent `correlationid`, **but only when `X-OpenHIM-TransactionID` is absent** (§9.1) |

Without the two `X-Sygnal-*` headers the pipeline works as before: CloudEvent `id` = UUID v5 of `<source key>:<X-Source-Event-Id>`, or a random UUID; `time` = time of receipt.

No `X-Facility-Id` is sent. The pipeline falls back to the resource's location (`FacilityIdExtractor`), as for any source that sends no such header.

**Source matching order.** `resolveSource()` tries `X-OpenHIM-ClientID` first and `X-Source-System` second. OpenHIM Core sets `X-OpenHIM-ClientID` to the client of this endpoint. So that client's id **must not** be the `client-id` of any configured source, or every Sygnal event is credited to that source. The adaptor refuses to start if it is (§7).

### 3.3 How the channel's answer is mapped

The channel's route to `/inbound` must be **primary**, so that the mediator's answer comes back to the caller (see [OpenHIM Channel Setup §9](openhim-channel-setup.md#9-beckn-channel-proposal)). OpenHIM Core unpacks the `application/json+openhim` envelope and returns its `response.status` and `response.body`.

The answer waits until the event has been handed on, because there is no callback: it carries the outcome.

| Channel answers | Body | Where it comes from (`/inbound`) | HMIS gets |
|---|---|---|---|
| 2xx with `"status":"processed"`, or 202 | `ProcessedEventsResponse` | Event forwarded and CCE answered `Ack` (new or duplicate) | `Ack` (202) |
| 200 | `"status":"ignored"` | No source matched, body is not FHIR, or a `Bundle` (`InboundEventService`, `SourceAdaptorService.adapt()`) | `Nack` (400) `BIZ_GENERIC_ERROR`, text `ignored: <reason>` |
| 200 | `"status":"skipped"` | Facility not in `cce.emitter.facility-filter.ids` | `Nack` (400) `BIZ_GENERIC_ERROR`, text `skipped: <reason>` (§15) |
| 400 | `PATIENT_ID_NOT_FOUND` | No patient reference in the resource | `Nack` (400) `BIZ_GENERIC_ERROR`, text `<code>: <message>` |
| 422 | `FHIR_MAPPING_ERROR` | Resource cannot be parsed | `Nack` (400) `BIZ_GENERIC_ERROR`, text `<code>: <message>` |
| 4xx | `COLLECTOR_CLIENT_ERROR` | Collector 4xx (collector output) | `Nack` (400) `BIZ_GENERIC_ERROR`, text = the collector's message |
| 4xx | `COLLECTOR_CLIENT_ERROR`, message `CCE BIZ_GENERIC_ERROR: <text>` | CCE refused the event (§5.4) | `Nack` (400) `BIZ_GENERIC_ERROR`, text `CCE: <text>` |
| 4xx | `COLLECTOR_CLIENT_ERROR`, message `CCE CTX_…: <text>` | CCE refused the HIE's own context: the HIE's settings are wrong (§5.4) | `Nack` (500) `NET_INTERNAL_ERROR`, text = the message. The HMIS resends; the HIE team must fix its settings (§15) |
| 4xx | `COLLECTOR_CLIENT_ERROR`, message `CCE <other code>: <text>` (for example `SCH_…`) | CCE refused the report for another reason (§5.4) | `Nack` (400) `BIZ_GENERIC_ERROR`, text `CCE <code>: <text>` |
| other 4xx (401, 403, 404) | No pipeline `error.code` | OpenHIM: client auth failed, no channel matched | `Nack` (500) `NET_INTERNAL_ERROR`, text = OpenHIM's answer. An HIE setup error, not the HMIS's |
| other 2xx | Not `processed`, `ignored` or `skipped` | Unexpected | `Nack` (500) `NET_INTERNAL_ERROR` |
| 5xx, 408, 429 | `COLLECTOR_FORWARDING_ERROR` (502), `INTERNAL_ERROR` (500), or OpenHIM's own 500 | Collector or CCE down or slow, unexpected error, route failed | `Nack` (500) `NET_DOWNSTREAM_UNAVAILABLE`, text ends `Send the event again later.` |
| no answer | — | Timeout, connection refused | `Nack` (500) `NET_DOWNSTREAM_UNAVAILABLE` |

**Answer bodies.** The endpoint answers in the shape `cce-beckn-service` uses (its `MessageFactory`). The `messageId` is the request's when it is a UUID, otherwise a new UUID.

```json
{ "message": { "status": "ACK", "messageId": "9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d" } }
```

```json
{ "message": { "status": "NACK", "messageId": "9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d",
               "error": { "code": "BIZ_GENERIC_ERROR", "message": "CCE: Payload validation failed: …" } } }
```

The codes are the canonical Beckn v2 codes, the same set as `cce-beckn-service` (`model/beckn/ErrorCodes`).

| Code | HTTP | Meaning | What the HMIS should do |
|---|---|---|---|
| — (`ACK`) | 202 | CCE has the event (new, or already known) | Mark it sent. Do not send again |
| `CTX_MISSING_FIELD`, `CTX_INVALID_FIELD`, `CTX_ACTION_MISMATCH` | 400 | A context rule is broken in the HMIS's message: unknown sender, wrong receiver, missing `messageId`, wrong action | Fix the message. Do not resend as it is |
| `SCH_INVALID_JSON`, `SCH_REQUIRED_FIELD_MISSING`, `SCH_SCHEMA_VALIDATION_FAILED`, `SCH_TYPE_NOT_SUPPORTED` | 400 (`SCH_INVALID_JSON`: or the 4xx Spring gave) | The event is malformed: not JSON, not exactly one event, wrong event type, no UUID id, revision not 1, `from` is not the sender, no `occurredAt`, no resource | Fix the message. Do not resend as it is |
| `BIZ_GENERIC_ERROR` | 400 | The pipeline refused the event (`ignored`, `skipped`, no patient, bad FHIR), or CCE refused it | Fix the event. Send the correction as a new event (new event id) |
| `NET_DOWNSTREAM_UNAVAILABLE` | 500 | Something that may pass: OpenHIM, the collector, CCE or an onix container is down or slow | Send the same event again later: same event id, new `messageId`. CCE de-duplicates on the event id |
| `NET_INTERNAL_ERROR` | 500 | A fault in the HIE: OpenHIM refused this adaptor's client (401, 403, 404), an unexpected answer, CCE refused the HIE's own context (`CTX_…`), or a bug. The HMIS's message was fine | Send the same event again later. The HIE team must fix the fault. The text says what it is |

In short: 400 = do not resend as it is; 5xx = may resend, with the same event id.

**Why "ignored" and "skipped" are a `Nack`.** `/inbound` answers 200 for both because it sits on a secondary route on the old path and must not mark OpenHIM transactions as failed. On the Sygnal path, a 200 would turn into an `Ack`, and the HMIS would see success for an event that never reached CCE.

### 3.4 No callback to the HMIS

Sygnal has no callback for a report. The HIE sends no `on_update` (or any other message) to the HMIS: the synchronous answer in §3.3 is the whole result.

### 3.5 Other actions

There is only one endpoint, `POST /sygnal/event/report`. A message on it whose `context.action` is anything else gets `Nack` (400) `CTX_ACTION_MISMATCH`. No other Sygnal or Beckn action is served.

### 3.6 Example inbound `sygnal/event/report`

OpenMRS is the sender, the HIE the receiver. The same shape as the tests use (`BecknTestSupport`).

```json
{
  "context": {
    "version": "2.0.0",
    "action": "sygnal/event/report",
    "networkId": "sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry",
    "senderId": "bap.openmrs.nextsteps.mdtlabs.org",
    "receiverId": "bap.openhim.nextsteps.mdtlabs.org",
    "receiverUri": "https://hie.example.org/sygnal/receiver",
    "transactionId": "0d5f8c1a-7b2e-4f6d-9a3c-1e2b3c4d5e6f",
    "messageId": "9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d",
    "timestamp": "2026-11-13T08:00:35Z"
  },
  "message": {
    "events": [
      {
        "@type": "sygnal:CoordinationEvent",
        "id": "3f6c1c1e-8a53-4b0e-9d7a-2b6f4e1a9c10",
        "revision": 1,
        "eventType": "DELIVERY_RECORDED",
        "granularity": "INDIVIDUAL",
        "from": { "id": "bap.openmrs.nextsteps.mdtlabs.org", "role": "PROVIDER" },
        "occurredAt": "2026-11-13T08:00:00Z",
        "patientId": "1198503150001234",
        "fhirVersion": "4.0.1",
        "sourceEventId": "Encounter/7d2c9b1e-4f3a-4e8b-9c6d-5a1b2c3d4e5f",
        "resource": {
          "resourceType": "Encounter",
          "id": "7d2c9b1e-4f3a-4e8b-9c6d-5a1b2c3d4e5f",
          "meta": { "lastUpdated": "2026-11-13T08:00:12Z" },
          "status": "finished",
          "subject": { "reference": "Patient/1198503150001234" },
          "period": { "start": "2026-11-13T07:30:00Z", "end": "2026-11-13T08:00:00Z" },
          "location": [ { "location": { "reference": "Location/0002" } } ]
        }
      }
    ]
  }
}
```

The event is the `sygnal:CoordinationEvent` itself, with the clinical data directly on it: no `eventAttributes` bag and no `@context`. `eventType` is Sygnal's field, always `DELIVERY_RECORDED` for a clinical event; the kind of clinical event is `resource.resourceType`. There is no contract and no commitment.

What goes to the channel:

```
POST https://openhim-core:5001/cce-beckn
Authorization: Basic <cce-beckn-endpoint credentials>
Content-Type: application/json
X-Source-System: openmrs-kicukiro
X-Source-Event-Id: Encounter/7d2c9b1e-4f3a-4e8b-9c6d-5a1b2c3d4e5f
X-Sygnal-Event-Id: 3f6c1c1e-8a53-4b0e-9d7a-2b6f4e1a9c10
X-Sygnal-Occurred-At: 2026-11-13T08:00:00Z
X-Correlation-Id: 9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d

{ "resourceType": "Encounter", "id": "7d2c9b1e-4f3a-4e8b-9c6d-5a1b2c3d4e5f", ... }
```

When CCE accepts the event, the HMIS gets:

```
HTTP/1.1 202 Accepted

{ "message": { "status": "ACK", "messageId": "9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d" } }
```

---

## 4. Role 2: the existing pipeline

The OpenHIM channel routes the request to `POST /inbound`. The path is the same as before, with two small additions (steps 5 and 7) that apply only when the `X-Sygnal-*` headers are present.

| Step | Component | What it does |
|---|---|---|
| 1 | `InboundEventController` | Builds `InboundRequest`, calls `InboundEventService.process()` |
| 2 | `SourceAdaptorService.resolveSource()` | `X-OpenHIM-ClientID`, then `X-Source-System` → source key. No match → 200 `ignored` |
| 3 | `FhirResourceParser` | Parses the body. Not FHIR or a `Bundle` → 200 `ignored`. Unparseable → 422 |
| 4 | `PatientIdExtractor` | Patient UPID → CloudEvent `subject`. None → 400 |
| 5 | `buildSourceMetadata()` + `FacilityIdExtractor` | Facility from `X-Facility-Id`, else from the resource's location. Source event id and correlation id from headers. **New:** event id from `X-Sygnal-Event-Id` (when a full UUID) and event time from `X-Sygnal-Occurred-At` (when it parses; else the time of receipt) |
| 6 | `FacilityFilter` | Facility not allowed → 200 `skipped` |
| 7 | `CloudEventEnvelopeBuilder` + `ClinicalDataRedactor` | Redacts the resource, builds the CloudEvent. `EventIdGenerator` keeps the given event id when there is one; else a UUID v5 when a source event id is present; else a random UUID |
| 8 | Output (§5) | `collector`: `CollectorForwardingService`. `beckn`: `BecknForwardingService` |
| 9 | `OpenHimResponseWrapper` | Wraps the answer in `application/json+openhim` |

At step 8, `InboundEventService` asks `EventForwardingRouter` which output the event's source uses, instead of calling `CollectorForwardingService` directly.

---

## 5. Role 3: the Sygnal output

### 5.1 Choosing the output

| `cce.emitter.sources.<key>.output` | Behaviour |
|---|---|
| `collector` | The old behaviour. `CollectorForwardingService` posts the CloudEvent to `{cce.collector.url}/v1/events` |
| `beckn` | The CloudEvent is mapped to an extended `sygnal:CoordinationEvent` and sent to CCE as a `sygnal/event/report` through `onix-openhim`'s caller |

The choice is per source, not per request. A source used by a Sygnal subscriber (its `source-system`) is normally set to `beckn`. A source whose system still posts FHIR through OpenHIM stays on `collector`. `output: beckn` with `beckn.enabled=false` is a startup error.

### 5.2 CloudEvent → `sygnal:CoordinationEvent`

`CoordinationEventMapper` builds the event. Every field below is a field of `events[0]` itself, and they are sent in this order. This adaptor sends these fields and no other.

| Event field | From the CloudEvent | Note |
|---|---|---|
| `@type` | — | `sygnal:CoordinationEvent` |
| `id` | `id` | For an event that came in over Sygnal: the HMIS's own event id (`X-Sygnal-Event-Id`), so the **same id on every hop**. Otherwise the UUID v5 or random UUID from `EventIdGenerator`. 36 characters, within CCE's limit of 50. CCE uses it unchanged as its CloudEvent `id` and de-duplicates on it |
| `revision` | — | Always `1`. CCE never replaces an event |
| `eventType` | — | Always `DELIVERY_RECORDED`. This is Sygnal's kind of event, not the CloudEvent `type` |
| `granularity` | — | Always `INDIVIDUAL` |
| `from` | — | `{ "id": beckn.self.id, "role": "PROVIDER" }`. The id equals `context.senderId` |
| `occurredAt` | `time` | For an event that came in over Sygnal: the HMIS's `occurredAt`. Otherwise the time of receipt |
| `patientId` | `subject` | Patient UPID |
| `fhirVersion` | — | Always `4.0.1` |
| `facilityId` | `facilityid` | Left out when blank |
| `facilityName` | `facilityname` | The CloudEvent has no `facilityname` (`CloudEventDto`). Left out until it does (§9.3) |
| `sourceEventId` | `sourceeventid` | Left out when blank. On the Sygnal path this is the HMIS's `sourceEventId`, or its event id when it gave none that fits in a header |
| `resource` | `data` | The **redacted** FHIR resource |

The CloudEvent `type` has no field of its own: it is the FHIR resource type, which CCE reads from `resource.resourceType`. An optional field without a value is left out; it is never sent as `null` or `""`. There is no `@context` and no `eventAttributes` bag.

**How CCE rebuilds the CloudEvent.** `cce-beckn-service` does not receive this adaptor's CloudEvent. It builds a new one from the report (its `CloudEventBuilder`):

| This adaptor's CloudEvent | Carried as | CCE's CloudEvent | Survives? |
|---|---|---|---|
| `id` | `id` | `id` | Yes, unchanged. For a Sygnal event it is the HMIS's id |
| `source` | — | `source` = `context.senderId` (the HIE's `beckn.self.id`) | No. The source key does not reach CCE |
| `type` | `resource.resourceType` | `type` | Yes. It is the FHIR resource type on both sides |
| `subject` | `patientId` | `subject` | Yes |
| `time` | `occurredAt` | `time` | Yes. For a Sygnal event it is the time the HMIS gave |
| `correlationid` | — | `correlationid` = `context.messageId` of the report | No. It is this adaptor's outbound `messageId` |
| `facilityid` | `facilityId` | `facilityid` | Yes, when set |
| (none) | `facilityName` | `facilityname` | Not sent (§9.3) |
| `sourceeventid` | `sourceEventId` | `sourceeventid` | Yes |
| `data` | `resource` | `data` | Yes, the redacted resource |
| `specversion` | — | `1.0` | Same value |
| `datacontenttype` | — | `application/fhir+json` | Set by CCE |
| `protocolinstanceid`, `protocoldefinitionid`, `actionid` | — | — | No. The event has no field for them. Nothing is lost: this adaptor never fills them. CCE's matcher reads them only when set (`MatcherEngine`), for an event that names its protocol step itself. If a source ever needs that, the schema needs these fields |

### 5.3 The `sygnal/event/report` to CCE

Same shape as the inbound report, with the HIE as sender and CCE as receiver. Built by `BecknMessageFactory.eventReport()`.

| Field | Value |
|---|---|
| `context.version` | `2.0.0` |
| `context.action` | `sygnal/event/report` |
| `context.networkId` | `beckn.self.network-id` (required) |
| `context.senderId` | `beckn.self.id`. CCE takes it (at most 100 characters) as the CloudEvent `source` |
| `context.receiverId` | `beckn.cce.id` |
| `context.receiverUri` | `beckn.cce.uri`: CCE's public receiver base. onix appends the action |
| `context.transactionId` | A new UUID per event, the same on every try |
| `context.messageId` | A new UUID per message, also per new try |
| `context.timestamp` | Now, UTC |
| `message.events` | **Exactly one event**: the `sygnal:CoordinationEvent` from §5.2, as it is |

Nothing is wrapped around the event and nothing is added to it: no `@context`, no `eventAttributes` bag and no `cce:CceClinicalEvent`. No contract, no commitments.

Posted to `{beckn.onix.caller-url}/sygnal/event/report`, that is `http://onix-openhim:8082/sygnal/caller/sygnal/event/report` by default. The action is appended as it is; its slashes are not encoded. `onix-openhim` signs the report and sends it to `context.receiverUri` + `/sygnal/event/report` (`onix-cce`'s receiver), which passes it to `cce-beckn-service` at `POST /sygnal/event/report`, port 8075.

```json
{
  "context": {
    "version": "2.0.0",
    "action": "sygnal/event/report",
    "networkId": "sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry",
    "senderId": "bap.openhim.nextsteps.mdtlabs.org",
    "receiverId": "bpp.cce.nextsteps.mdtlabs.org",
    "receiverUri": "https://cce.example.org/sygnal/receiver",
    "transactionId": "4a7b9c2d-3e5f-4a1b-8c6d-7e8f9a0b1c2d",
    "messageId": "c7e1d2f3-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
    "timestamp": "2026-11-13T08:00:36Z"
  },
  "message": {
    "events": [
      {
        "@type": "sygnal:CoordinationEvent",
        "id": "3f6c1c1e-8a53-4b0e-9d7a-2b6f4e1a9c10",
        "revision": 1,
        "eventType": "DELIVERY_RECORDED",
        "granularity": "INDIVIDUAL",
        "from": { "id": "bap.openhim.nextsteps.mdtlabs.org", "role": "PROVIDER" },
        "occurredAt": "2026-11-13T08:00:00Z",
        "patientId": "1198503150001234",
        "fhirVersion": "4.0.1",
        "facilityId": "0002",
        "sourceEventId": "Encounter/7d2c9b1e-4f3a-4e8b-9c6d-5a1b2c3d4e5f",
        "resource": {
          "resourceType": "Encounter",
          "id": "7d2c9b1e-4f3a-4e8b-9c6d-5a1b2c3d4e5f",
          "meta": { "lastUpdated": "2026-11-13T08:00:12Z" },
          "status": "finished",
          "subject": { "reference": "Patient/1198503150001234" },
          "period": { "start": "2026-11-13T07:30:00Z", "end": "2026-11-13T08:00:00Z" },
          "location": [ { "location": { "reference": "Location/0002" } } ]
        }
      }
    ]
  }
}
```

`events[0].id` is the id OpenMRS sent in §3.6, unchanged. `occurredAt` is OpenMRS's too. `facilityId` was read from the resource's location. The resource is the redacted one. Here nothing was removed because the sample `Encounter` has no clinical fields.

### 5.4 How CCE's answer is mapped

The Sygnal output gives `InboundEventService` the same results the collector path gives, so the `/inbound` answer, the OpenHIM transaction and the existing metrics behave the same. `CceAnswerMapper` reads the answer; `BecknForwardingService` turns it into a collector-shaped result.

CCE's answer comes back through `onix-cce` and `onix-openhim` in the same HTTP call. Its body has the shape in §3.3. CCE answers `202 ACK` both for a new event and for one it already has.

| CCE answers | Meaning | Treated like | `/inbound` answers | HMIS gets (§3.3) | Existing metric |
|---|---|---|---|---|---|
| 2xx, body not `NACK` (CCE: 202 `ACK`) | CCE has the event, new or already known | Collector 202 `accepted` | 202, `"status":"processed"`, `collectorStatus` `accepted`. Orchestration response status 202 | `Ack` (202) | `cce.emitter.events.forwarded` |
| 4xx (not 408, 429) `NACK` `BIZ_GENERIC_ERROR`, or a 4xx with no code | The collector refused the event. No retry | Collector 4xx: `CollectorClientException` (400) | 400 `COLLECTOR_CLIENT_ERROR`, message `CCE BIZ_GENERIC_ERROR: <CCE's text>` | `Nack` (400) `BIZ_GENERIC_ERROR`, `CCE: <CCE's text>` | `cce.emitter.events.rejected` |
| 4xx `NACK` `CTX_…` | CCE refused the HIE's own context (its ids, network or receiver). No retry | Collector 4xx | 400 `COLLECTOR_CLIENT_ERROR`, message `CCE CTX_…: <CCE's text>` | `Nack` (500) `NET_INTERNAL_ERROR` (§15) | `cce.emitter.events.rejected` |
| 4xx `NACK` with another code (for example `SCH_…`) | CCE refused the report. No retry | Collector 4xx | 400 `COLLECTOR_CLIENT_ERROR`, message `CCE <code>: <CCE's text>` | `Nack` (400) `BIZ_GENERIC_ERROR`, `CCE <code>: <CCE's text>` | `cce.emitter.events.rejected` |
| 2xx `NACK` with another code | As the 4xx rows above, by code | Collector 4xx | As above | As above | `cce.emitter.events.rejected` |
| 2xx `NACK` `NET_DOWNSTREAM_UNAVAILABLE` or `NET_INTERNAL_ERROR`; 408, 429, 5xx; timeout, connection refused | May pass. Send again later | Collector failure: `CollectorForwardingException` | 502 `COLLECTOR_FORWARDING_ERROR` | `Nack` (500) `NET_DOWNSTREAM_UNAVAILABLE` | `cce.emitter.collector.retries` |

So a refusal is never retried, and an answer that may pass is handled like a collector failure. The `COLLECTOR_CLIENT_ERROR` message carries CCE's code and text, so the `sygnal/event/report` endpoint can pass them to the HMIS (§3.3).

Differences from the collector path:

| Aspect | Collector path | Sygnal output |
|---|---|---|
| Wait per call | `cce.collector.timeout` = 15 s (longer than the collector's Kafka publish time) | `beckn.onix.timeout` = 20 s. CCE's `Ack` can take up to about 17 s (§8.3) |
| Retries | `cce.collector.retry.max-attempts` = 3, backoff 1 s × 2 | `beckn.cce.retry.max-attempts` = 1 (no retry). One try of up to 20 s already fills most of the channel's wait (§8.3). The HMIS resends instead; the event id stays the same, so CCE answers `Ack` for the duplicate (§8.4) |
| Duplicate | Known at once: collector 200 → `eventsDuplicate`, `cce.emitter.events.duplicate` | Not known. CCE answers 202 `ACK` for both, so the result is `accepted` and `cce.emitter.events.duplicate` is not counted |
| Orchestration entry | `Forward to CCE Collector`, `POST /v1/events` | `Forward to CCE over Sygnal`, `POST /sygnal/caller/sygnal/event/report` (the path of `beckn.onix.caller-url` + `/sygnal/event/report`) |
| Auth | Bearer token from `CollectorTokenService` | None from this adaptor. `onix-openhim` signs the message |

### 5.5 What the HIE and CCE agree

There is nothing to agree per contract: no contract id, no `transactionId`, no commitment. The values that must match:

| HIE setting | Must equal |
|---|---|
| `beckn.self.id` | The HIE's subscriber id in the network registry. CCE logs it as the sender and uses it as CloudEvent `source` |
| `beckn.self.network-id` | The network both are members of |
| `beckn.cce.id` | CCE's subscriber id (CCE refuses a report whose `receiverId` is not its own, `CTX_INVALID_FIELD`) |
| `beckn.cce.uri` | CCE's public onix receiver base, e.g. `https://cce.example.org/sygnal/receiver` |

CCE has no allow-list for events: it accepts a report from any signed sender. CCE's HMIS list (`cce-beckn-service`'s `config/hmis.yml`) is only for sending alerts (`sygnal/signal/emit`), which go to OpenMRS directly, not through the HIE. The HIE's id and keys must be in the network registry, so that `onix-cce` can check the signature.

---

## 6. No callback endpoint

Removed. Sygnal has no callbacks: CCE sends no `on_update` for a report, so this adaptor has no callback endpoint. CCE's synchronous answer (§5.4) is the whole result.

---

## 7. Settings

The settings keep the `beckn.*` prefix and the environment variables the `OPENHIM_EMITTER_BECKN_*` prefix. Defaults from `application.yml`:

```yaml
beckn:
  enabled: ${OPENHIM_EMITTER_BECKN_ENABLED:true}
  # Not used in a message since profile v3.0 (no object of the report carries an @context). Kept for existing deployments.
  schema-base-url: ${OPENHIM_EMITTER_BECKN_SCHEMA_BASE_URL:https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema}
  onix:                                              # this HIE's onix container
    caller-url: ${OPENHIM_EMITTER_BECKN_ONIX_CALLER_URL:http://onix-openhim:8082/sygnal/caller}  # + /sygnal/event/report
    timeout: ${OPENHIM_EMITTER_BECKN_ONIX_TIMEOUT_MS:20000}          # §8.3; below openhim-channel.timeout
  self:                                              # the HIE's own Sygnal identity
    id: ${OPENHIM_EMITTER_BECKN_SELF_ID:bap.openhim.nextsteps.mdtlabs.org}   # at most 100 characters; CCE's CloudEvent source
    network-id: ${OPENHIM_EMITTER_BECKN_SELF_NETWORK_ID:sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry}
  cce:                                               # CCE's Sygnal identity
    id: ${OPENHIM_EMITTER_BECKN_CCE_ID:bpp.cce.nextsteps.mdtlabs.org}       # context.receiverId
    uri: ${OPENHIM_EMITTER_BECKN_CCE_URI:}                           # CCE's public receiver base, e.g. https://cce.example.org/sygnal/receiver
    retry:
      max-attempts: 1                                # no retry inside the route, §8.4
  openhim-channel:                                   # where the sygnal/event/report endpoint posts the FHIR
    url: ${OPENHIM_EMITTER_BECKN_OPENHIM_CHANNEL_URL:https://openhim-core:5001/cce-beckn}
    username: ${OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_ID:cce-beckn-endpoint}
    password: ${OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_PASSWORD:}
    timeout: ${OPENHIM_EMITTER_BECKN_OPENHIM_CHANNEL_TIMEOUT_MS:25000}   # §8.3; below onix's 30 s wait for this adaptor
  subscribers: []                                    # one per HMIS; set in a profile, for example:
  # subscribers:
  #   - subscriber-id: bap.openmrs.nextsteps.mdtlabs.org   # the HMIS's context.senderId
  #     source-system: openmrs-kicukiro                    # a key under cce.emitter.sources

# Example sources (not the defaults). With beckn.enabled=true a source without an explicit output
# uses beckn, so a source that must stay on the collector says so.
cce:
  emitter:
    sources:
      ebuzima:
        client-id: ${EBUZIMA_CLIENT_ID}
        output: collector
      openmrs-kicukiro:                          # no client-id: matched by X-Source-System only
        output: beckn
```

| Setting | Environment variable | Default | Meaning |
|---|---|---|---|
| `beckn.enabled` | `OPENHIM_EMITTER_BECKN_ENABLED` | `true` | Turns on the `sygnal/event/report` endpoint and allows `output: beckn` |
| `beckn.schema-base-url` | `OPENHIM_EMITTER_BECKN_SCHEMA_BASE_URL` | CCE's, `https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema` | Base URL of CCE's schema files (`cce-beckn-service`'s `schema/` folder). Not used in a message since profile v3.0: no object of the report carries an `@context`. The setting is still read, so a deployment that sets it needs no change |
| `beckn.onix.caller-url` | `OPENHIM_EMITTER_BECKN_ONIX_CALLER_URL` | `http://onix-openhim:8082/sygnal/caller` | `onix-openhim`'s Sygnal caller. The adaptor appends `/sygnal/event/report` |
| `beckn.onix.timeout` | `OPENHIM_EMITTER_BECKN_ONIX_TIMEOUT_MS` | 20 000 | Connect and read timeout for the onix call, in ms (§8.3) |
| `beckn.self.id` | `OPENHIM_EMITTER_BECKN_SELF_ID` | `bap.openhim.nextsteps.mdtlabs.org` | The HIE's subscriber id: `senderId` towards CCE, the expected `receiverId` from an HMIS. At most 100 characters (CCE's CloudEvent `source`) |
| `beckn.self.network-id` | `OPENHIM_EMITTER_BECKN_SELF_NETWORK_ID` | `sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry` | `context.networkId` of every message the HIE sends. Required |
| `beckn.cce.id` | `OPENHIM_EMITTER_BECKN_CCE_ID` | `bpp.cce.nextsteps.mdtlabs.org` | CCE's subscriber id: `context.receiverId` |
| `beckn.cce.uri` | `OPENHIM_EMITTER_BECKN_CCE_URI` | none | CCE's public receiver base: `context.receiverUri`. Required |
| `beckn.cce.retry.max-attempts` | — | 1 | Tries per report to CCE |
| `beckn.openhim-channel.url` | `OPENHIM_EMITTER_BECKN_OPENHIM_CHANNEL_URL` | `https://openhim-core:5001/cce-beckn` | The channel the `sygnal/event/report` endpoint posts to |
| `beckn.openhim-channel.username` | `OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_ID` | `cce-beckn-endpoint` | The OpenHIM client of that endpoint |
| `beckn.openhim-channel.password` | `OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_PASSWORD` | empty | Its password |
| `beckn.openhim-channel.timeout` | `OPENHIM_EMITTER_BECKN_OPENHIM_CHANNEL_TIMEOUT_MS` | 25 000 | Wait for the channel's answer, in ms (§8.3) |
| `beckn.subscribers[]` | — | empty | One entry per HMIS: `subscriber-id` (its `context.senderId`) and `source-system` (the source key its events use). Nothing else: no URI, contract or commitment |
| `cce.emitter.sources.<key>.output` | — | follows `beckn.enabled` | `collector` or `beckn`. Unset: `beckn` when `beckn.enabled` is on, else `collector` |

Startup checks (`BecknStartupValidator`). All errors are reported in one message.

| Check | On failure |
|---|---|
| A source has `output: beckn` but `beckn.enabled` is `false` | Fail to start |
| With `beckn.enabled`: `beckn.onix.caller-url`, `self.id`, `self.network-id`, `cce.id`, `cce.uri`, `openhim-channel.url` or `openhim-channel.username` is not set | Fail to start |
| `beckn.cce.retry.max-attempts` is below 1 | Fail to start |
| `beckn.self.id` is longer than 100 characters | Fail to start (CCE would refuse every report) |
| `beckn.openhim-channel.username` equals the `client-id` of a source | Fail to start (it would win over `X-Source-System`, §3.2) |
| A subscriber has no `subscriber-id` or no `source-system`, or a `subscriber-id` is listed twice | Fail to start |
| A subscriber's `source-system` is not a key under `cce.emitter.sources` | Fail to start |
| A subscriber's source has output `collector` | Warn. Allowed: the event then goes to the collector directly |
| `beckn.onix.timeout` × `beckn.cce.retry.max-attempts` is not below `beckn.openhim-channel.timeout` | Warn (§8.3) |
| `beckn.openhim-channel.timeout` is 30 000 ms or more | Warn: not below the 30 s onix waits for this adaptor (§8.3) |

---

## 8. Consequences

### 8.1 What changes for CCE

- **CCE sees the HIE as the sender for every facility.** `cce-beckn-service` sets the CloudEvent `source` from `context.senderId`, which is `beckn.self.id`. The source key (`openmrs-kicukiro`) does not reach CCE. CCE tells facilities apart by `facilityId`.
- **CCE gets the same content as on the collector path.** Redaction still happens here, before the event is built.
- **The event id survives end to end, unchanged.** The HMIS's `events[0].id` becomes `X-Sygnal-Event-Id`, then the CloudEvent `id`, then the outbound `events[0].id`, then the CloudEvent `id` at CCE. It is the same UUID on every hop.
- **CCE knows an event by its id.** All events reach CCE with the same `senderId`. A resent event has the same id each time, so CCE answers `Ack` again and counts nothing twice. The source key is no longer part of the id, so two HMIS systems' events are kept apart only because each HMIS uses UUIDs (§14).
- **A correction is a new event.** CCE never replaces an event, and both this adaptor and CCE refuse a `revision` other than 1. A corrected event needs a new event id.
- **`time` now survives; `source` and `correlationid` still change at CCE.** `time` is the HMIS's `occurredAt`. `source` is the HIE's `senderId`, `correlationid` the HIE's outbound `messageId` (§5.2).

### 8.2 What stays the same here

- A source that keeps posting FHIR through OpenHIM keeps going to the collector.
- `/inbound`, its answers and its metrics are unchanged. On the Sygnal output, the same metrics count the same outcomes (§5.4), except `cce.emitter.events.duplicate`.
- The facility filter and redaction apply to Sygnal events as to any other.

### 8.3 Every answer returns in the same call

Many hops lie between the HMIS's emitter and the CCE collector, and there is no queue. Each hop waits for the hop inside it. The synchronous answer carries the result: there is no callback.

The old Beckn flow also waited for the channel before it answered: it gave its `Ack` or `Nack` in the same call, and only then sent an `on_update` asynchronously. Only that `on_update` is gone; the `Ack` is now 202 instead of 200. So the wait is no longer than before.

**Each inner timeout must be shorter than the one around it.** If an outer hop gives up first, the HMIS gets `NET_DOWNSTREAM_UNAVAILABLE` for an event that may have got through. Its resend then carries the same event id, and CCE answers `Ack` (duplicate).

Inside CCE, `cce-beckn-service` waits up to 15 s for its collector. So **CCE's `Ack` can take up to about 17 s**. onix's `responseHeaderTimeout` is 30 s. The budget, inside out:

| Hop | Waits for | Value | Setting |
|---|---|---|---|
| `cce-beckn-service` | Its collector | 15 s, so CCE's `Ack` up to about 17 s | CCE |
| `onix-openhim` caller → `onix-cce` receiver → CCE | CCE's answer | 30 s | onix `responseHeaderTimeout` (both onix) |
| This adaptor (Sygnal output) → `onix-openhim` caller | The hop above. One try | 20 s | `beckn.onix.timeout` |
| OpenHIM channel route (`/cce-beckn`) → `/inbound` | The output | About 22–23 s (between 20 and 25 s) | OpenHIM channel timeout |
| `sygnal/event/report` endpoint → OpenHIM channel | The channel | 25 s | `beckn.openhim-channel.timeout` |
| `onix-openhim` receiver → `sygnal/event/report` endpoint | This adaptor's answer | 30 s | onix `responseHeaderTimeout` |
| OpenMRS emitter → `onix-openmrs` caller → … | The whole route | More than 30 s | OpenMRS side |

The HIE always answers within about 25 s, under the 30 s that onix waits. If the HIE times out first, it answers 500 `NET_DOWNSTREAM_UNAVAILABLE`; the sender resends, and the resend carries the same event id, so CCE answers `Ack` (duplicate). The startup validator warns when `beckn.onix.timeout` × `max-attempts` is not below `beckn.openhim-channel.timeout`, or when `beckn.openhim-channel.timeout` is not below 30 s (§7). When the far side is slow, the HMIS emitter's poll is slowed by this wait.

### 8.4 Retries

The Sygnal output does not retry by default. A retry inside a synchronous route multiplies the time every outer hop must wait: one try of up to 20 s already takes most of the channel's 25 s, so a second try could not finish before the endpoint gives up. The HMIS resends instead, after a 5xx `Nack` or no answer. The collector path keeps its 3 tries.

A resend works because the event id is fixed: the HMIS sends a new `messageId` with the same event id, this adaptor keeps that id as the CloudEvent id and sends a new outbound `messageId`, and CCE answers `Ack` if it already had the event. A refusal (400) is not resent.

With `beckn.cce.retry.max-attempts` above 1, each try has a new `messageId` and the same `transactionId`, with no back-off.

**Resend policy.** CCE keeps no queue for a sender and advises resending with a growing wait, for example after 1 minute, 5 minutes, 30 minutes. This adaptor keeps no queue either, so the wait is the HMIS's job (§15).

---

## 9. Conflicts found in the current code

These differ from what the design expects.

### 9.1 `X-Correlation-Id` loses to `X-OpenHIM-TransactionID`

`SourceAdaptorService.buildSourceMetadata()` takes `X-OpenHIM-TransactionID` first. OpenHIM Core sets it on every routed request. So on the Sygnal path the CloudEvent `correlationid` is the OpenHIM transaction id, not the Sygnal `messageId`. It does not reach CCE anyway (§8.1).

Options: (a) accept it, and have the `sygnal/event/report` endpoint log `messageId` with the OpenHIM transaction id; (b) prefer `X-Correlation-Id` when the source has `output: beckn`. Proposal: (a), no change to the pipeline.

### 9.2 The current route is secondary

The adaptor sits on the eBUZIMA channel as a **secondary** route. A secondary route's answer never reaches the client. The `sygnal/event/report` endpoint must post to a channel where `/inbound` is the **primary** route. Proposal: a new channel for Sygnal traffic only ([OpenHIM Channel Setup §9](openhim-channel-setup.md#9-beckn-channel-proposal)).

### 9.3 The CloudEvent has no `facilityname`

`CloudEventDto` has `facilityid` but no `facilityname`. The event's `facilityName` is left out until a facility name source exists. A `facilityName` (or `facilityId`) that an HMIS puts in its event is not carried over: the pipeline reads the facility from the resource.

### 9.4 The event id is copied, not derived (resolved)

On the old Beckn path, `EventIdGenerator` built the CloudEvent `id` as a UUID v5 of `<source key>:<X-Source-Event-Id>`, so the id CCE saw was not the HMIS's own. Resolved: the endpoint now sends the HMIS's event id in `X-Sygnal-Event-Id`, and `EventIdGenerator` keeps it (when it is a full UUID). CCE de-duplicates on the same id OpenMRS sent. Without that header (the collector path, other sources), the UUID v5 rule still applies.

### 9.5 Metrics tag the source by client id

`InboundEventService` tags `cce.emitter.events.received` with `X-OpenHIM-ClientID`. All Sygnal events are counted under the endpoint's OpenHIM client. `cce.emitter.events.forwarded` uses the resolved source and is not affected.

---

## 10. Classes

Placed in the existing packages.

| Package | Class | New or changed | Role |
|---|---|---|---|
| `config` | `BecknProperties` | New | Binds `beckn.*` |
| `config` | `EmitterProperties.SourceProperties` | Changed | Adds `output` (`collector`, `beckn`) |
| `config` | `RestClientConfig` | Changed | Adds `becknOnixRestClient` (`onix-openhim`'s caller) and `openHimChannelRestClient`, each with its own timeout |
| `config` | `BecknStartupValidator` | New | The startup checks in §7 |
| `controller` | `SygnalEventReportController` | New | `POST /sygnal/event/report`. HTTP only |
| `adaptor` | `BecknInboundUnwrapper` | New | Finds the subscriber, checks the context and the event, takes the resource and builds the headers (§3.1, §3.2) |
| `adaptor` | `SourceAdaptorService` | Changed | Reads `X-Sygnal-Event-Id` and `X-Sygnal-Occurred-At` (§4) |
| `model` | `SourceMetadata` | Changed | Adds `eventId` |
| `cloudevents` | `EventIdGenerator` | Changed | Keeps the given event id when there is one |
| `cloudevents` | `CoordinationEventMapper` | New | CloudEvent → the extended `sygnal:CoordinationEvent` (§5.2) |
| `service` | `BecknInboundService` | New | Unwrap → channel → answer mapping |
| `service` | `OpenHimChannelClient` | New | Posts the FHIR to the channel. Reads the answer, plain or `application/json+openhim` |
| `service` | `ChannelAnswerMapper` | New | Channel answer → `Ack` / `Nack` (§3.3) |
| `service` | `EventForwardingRouter` | New | Picks `CollectorForwardingService` or `BecknForwardingService` by the event's source. Gives the orchestration name and path |
| `service` | `BecknForwardingService` | New | Builds the report, posts it to `onix-openhim`, maps the answer (§5.4) |
| `service` | `BecknMessageFactory` | New | `eventReport()`: the `sygnal/event/report` to CCE (§5.3) |
| `service` | `BecknOnixClient` | New | `post(action, message)` to `onix-openhim`'s caller; latency timer |
| `service` | `CceAnswerMapper` | New | CCE's answer → accepted, refused or failed (§5.4) |
| `service` | `InboundEventService` | Changed | Calls `EventForwardingRouter` instead of `CollectorForwardingService`. Orchestration name and path follow the output |
| `model.beckn` | `BecknAck`, `BecknAnswer`, `HttpAnswer`, `CoordinationEvent`, `ErrorCodes` | New | `Ack` / `Nack` body; answer (status, code, text); HTTP answer or no answer; the event of the report; the canonical Beckn error codes |
| `exception` | `BecknNackException` | New | A `Nack` code, HTTP status and text |
| `exception` | `BecknExceptionHandler` | New | `@RestControllerAdvice` scoped to `SygnalEventReportController`. Turns errors before the service runs into a `Nack` in the body shape of §3.3, so `GlobalExceptionHandler` stays as it is for `/inbound` |

Removed with the switch to Sygnal: `BecknBppWebhookController`, `BecknBapWebhookController`, `CceCallbackService`, `BecknCallbackService` (the `on_update` to the HMIS), `BecknCommitments`, `BecknActions`, and the resources `beckn/commitment-cce.json` and `beckn/commitment-hie-feed.json`.

`CollectorForwardingService`, `CloudEventEnvelopeBuilder`, `ClinicalDataRedactor` and `FacilityFilter` do not change.

---

## 11. Metrics

Prefix `cce.emitter.`, as for the existing metrics. Prometheus turns dots into underscores and adds `_total` to counters.

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `cce.emitter.beckn.received` | Counter | `action` (`sygnal/event/report`) | Sygnal messages received |
| `cce.emitter.beckn.answered` | Counter | `action`, `result` (`ack`, `nack` = 400, `error` = 5xx), `code` (`none` for an `Ack`) | Sygnal messages answered. `code` is the `Nack` code |
| `cce.emitter.beckn.channel.ignored` | Counter | `subscriber`, `status` (`ignored`, `skipped`) | Channel answered 200 without forwarding. Each one is a `Nack` |
| `cce.emitter.beckn.channel.latency` | Timer | — | Endpoint → channel → answer |
| `cce.emitter.beckn.onix.latency` | Timer | `action` (`sygnal/event/report`) | Round trip to `onix-openhim`'s caller |
| `cce.emitter.beckn.cce.report.sent` | Counter | `source`, `result` (`ack`, `nack`, `error`), `code` | Report sent to CCE. `code` is CCE's `Nack` code, `none` when there is none |

The Sygnal output also counts the collector-path metrics for the same outcomes: `cce.emitter.events.forwarded`, `cce.emitter.events.rejected` (`source=beckn`, `reason=client_error`) and `cce.emitter.collector.retries`. `cce.emitter.events.duplicate` is not counted for Sygnal: CCE's `Ack` does not say whether the event was a duplicate.

Removed: `cce.emitter.beckn.onupdate.sent`, `cce.emitter.beckn.cce.onupdate.received`, the `endpoint` tag on `received` / `answered`, and the `container` tag on `onix.latency`. `cce.emitter.beckn.cce.update.sent` is renamed `cce.emitter.beckn.cce.report.sent`.

Alert proposals are in [Monitoring & Alerting §8](monitoring-alerting.md#8-beckn-metrics-and-alerts-proposal).

---

## 12. Logging

| Where | Level | Logged |
|---|---|---|
| `sygnal/event/report` endpoint, on receipt | INFO | `senderId`, `transactionId`, `messageId`, event id, resource type |
| `sygnal/event/report` endpoint, on answer | INFO | `messageId`, result (`Ack`, `Nack` with code and text), HTTP status, channel status |
| Sygnal output | INFO / WARN | CloudEvent `id`, outbound `messageId`, CCE's answer (`ACK`, or `NACK` with HTTP status, code and text), time taken |

MDC keys, alongside the existing `correlationId`, `source`, `eventType`, `subject`: `becknMessageId`, `becknTransactionId`, `becknAction` (`sygnal/event/report`), `subscriber` (the `senderId`). The resource body is never logged. `onix-openhim` should also not store message bodies (`storeBody: "false"`).

---

## 13. Security

- The `sygnal/event/report` endpoint sits next to `/inbound` and has no token check, like `/inbound`. `onix-openhim`'s receiver has checked the Sygnal signature before calling it. It must be reachable only from `onix-openhim`, on the internal network.
- The endpoint checks that the sender is a configured subscriber and that the report is addressed to this HIE (§3.1).
- The endpoint posts to OpenHIM with its own client, so OpenHIM still authenticates and logs every event.
- The Sygnal output sends no Bearer token. `onix-openhim` signs the message with the HIE's key, and `onix-cce` checks it.

---

## 14. Risks

- **onix's behaviour must hold.** The whole design needs `onix-openhim` and `onix-cce` to pass the far side's `Ack` or `Nack` back in the same HTTP call, within the 30 s `responseHeaderTimeout`.
- **The route is long and synchronous.** Many hops, no queue. Wrong timeouts lead to resends and errors seen by the HMIS (§8.3).
- **A wrong source mapping loses events quietly on the old path.** On the Sygnal path, an `ignored` answer becomes a `Nack`, so the HMIS sees it. The `cce.emitter.beckn.channel.ignored` alert must be watched.
- **The endpoint's OpenHIM client must not match a source's client id** (§3.2), or all Sygnal events are credited to one source.
- **CCE sees one sender for all facilities.** CCE must link events by patient and facility, not by `source`.
- **Event ids are the HMIS's.** The HIE no longer mixes the source key into the id. Every HMIS must send UUIDs that are unique across HMIS systems (random UUIDs are), or CCE could drop one HMIS's event as a duplicate of another's.
- **The HMIS gets errors that are not its own.** A CCE `CTX_…` refusal is passed on as 500 `NET_INTERNAL_ERROR`, and the HMIS keeps resending until the HIE's settings are fixed. A CCE `SCH_…` refusal of the HIE's own report is passed on as 400 `BIZ_GENERIC_ERROR`, though the HMIS cannot fix it (§15).
- **The specification is still moving.** The `Ack` and `Nack` bodies and codes here are the ones `cce-beckn-service` uses, from the canonical Beckn v2 list.

---

## 15. Open points

1. **Facility filter "skipped".** Built: `Nack` (400) `BIZ_GENERIC_ERROR`, like "ignored". The alternative is `Ack`, if a skipped facility is not the HMIS's problem.
2. **OpenHIM 401, 403 or 404** (bad client setup). Decided: `Nack` (500) `NET_INTERNAL_ERROR`, so the HMIS resends once the HIE fixes its setup.
3. **CCE's refusals of the HIE's own report.** Built: a CCE `CTX_…` refusal (CCE refused the HIE's context: its ids, network or receiver) maps to `Nack` (500) `NET_INTERNAL_ERROR`, because it is an HIE misconfiguration and the HMIS should resend once it is fixed. All other CCE 4xx `Nack`s, including `SCH_…` refusals of the HIE's own report and a 4xx without a code, map to `Nack` (400) `BIZ_GENERIC_ERROR`, so the HMIS does not resend. A decision to review: a schema refusal of the HIE's report is also the HIE's fault, not the HMIS's.
4. **Correlation id** (§9.1).
5. **One OpenHIM client per HMIS** instead of one shared client. Then `X-OpenHIM-ClientID` would select the source and `X-Source-System` would not be needed. More OpenHIM setup, cleaner transaction log.
6. **Timeout budget** (§8.3), to agree with the HIE, CCE and HMIS teams. The OpenHIM channel timeout must sit between `beckn.onix.timeout` (20 s) and `beckn.openhim-channel.timeout` (25 s).
7. **`facilityName`** (§9.3).
8. **`eventType` values.** Closed on 2026-11-23: `eventType` is Sygnal's field and is always `DELIVERY_RECORDED` for a clinical event. The kind of clinical event is `resource.resourceType`.
9. **HMIS resend policy** (§8.4). CCE advises a growing wait (1 min, 5 min, 30 min).

---

## 16. Implementation notes (differences from the design)

The build follows §1–§15 except for these points.

| Topic | Design | Built |
|---|---|---|
| Sygnal JSON classes | Typed message classes | Not built. Incoming messages are read as `JsonNode` (the resource must pass through unchanged) and outgoing ones are built as `ObjectNode` by `BecknMessageFactory`, as `cce-beckn-service` does. Typed: `BecknAck`, `CoordinationEvent` (the event of the outgoing report), `BecknAnswer` (status, code, text) and `HttpAnswer` (status/body or no answer) |
| `ProcessedEventsResponse` (§3.3) | `eventsProcessed` ≥ 1 | The real field is `eventsForwarded`; the endpoint takes `"status":"processed"` (or HTTP 202) as success |
| Answer text for CCE's refusals (§3.3) | "CCE's text" | `BIZ_GENERIC_ERROR` from CCE: `CCE: <text>`. Other CCE codes: `CCE <code>: <text>`, so the HMIS and the HIE team see CCE's code |
| Other channel answers | Not covered | 408 and 429 → `NET_DOWNSTREAM_UNAVAILABLE`. A 2xx that is not `processed`, `ignored` or `skipped` → `NET_INTERNAL_ERROR`. A 4xx without the pipeline's `error.code` body is treated as OpenHIM's own refusal → `NET_INTERNAL_ERROR` |
| Answer `messageId` | Always the request's | The request's when it is a UUID; otherwise a new UUID (same rule as `cce-beckn-service`'s `Ids.uuidOrRandom`). A body that is not JSON gets a new UUID |
| CCE's answer in a 2xx with a `NACK` body | Not covered | Treated as a refusal, or as a failure when its code is `NET_DOWNSTREAM_UNAVAILABLE` or `NET_INTERNAL_ERROR` |
| CCE's 4xx without a code | Not covered | Read as `BIZ_GENERIC_ERROR`, with the HTTP status and body start as text |
| Sygnal output refusals | `cce.emitter.events.rejected` | Same metric, tags `source=beckn`, `reason=client_error` (the collector path keeps `source=collector`) |
| `beckn.cce.retry.max-attempts` > 1 | — | Supported: a new `messageId` per try, the same `transactionId`, no back-off. Default 1. `cce.emitter.collector.retries` is counted once when all tries failed |
| Header values | Copied | `X-Source-Event-Id` and `X-Correlation-Id` are sent only when header-safe (printable ASCII, at most 200 characters); `X-Source-Event-Id` falls back to the event id |
| Settings (§7) | — | `beckn.subscribers` is empty by default (set it in a profile). `beckn.cce.uri` has no default and is required |
| Logging | MDC keys | The keys are also in the log patterns: dev console prints `[beckn <action> <messageId> <subscriber>]` when set; prod JSON has `becknMessageId`, `becknTransactionId`, `becknAction`, `subscriber` |
