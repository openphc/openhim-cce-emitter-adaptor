# CCE Emitter Adaptor — Beckn Integration

**Status:** Built on `demo-beckn`, on by default (`beckn.enabled=true`; set `OPENHIM_EMITTER_BECKN_ENABLED=false` to turn it off). Where the code differs from the proposal below, §16 says how.
**Branch:** `demo-beckn`
**Background:** *Design: Beckn support for OpenMRS* (`openmrs-beckn-service/docs/design-openmrs-beckn-integration.md`), mainly §4.4, §4.7, §5.2, §11 and Appendix A.3. In that document, "the mediator" is this adaptor. *Design: Beckn support for CCE* describes `cce-beckn-service`.

**Scope:** Let an HMIS (OpenMRS or Spice) send clinical events to CCE over a Beckn network, through the HIE. This adaptor unwraps what comes in, runs it through the OpenHIM channel and its existing pipeline, and wraps the result for CCE.

**Unchanged by default.** With `beckn.enabled=false` (the default) and no source set to `output: beckn`, the adaptor behaves exactly as today: OpenHIM channel → `/inbound` → CloudEvent → CCE collector. A source that keeps posting FHIR through OpenHIM keeps going to the collector.

All hosts, ids and setting names below are made up or proposed, except where they come from `cce-beckn-service`.

**Towards CCE, this adaptor is the HMIS.** CCE sees OpenHIM's `bapId` as the sender. So `cce-beckn-service`'s *HMIS integration guide* (`cce-beckn-service/docs/hmis-integration-guide.md`) applies in full to the `update` this adaptor sends. What this document says about CCE is taken from that guide and from the `cce-beckn-service` code.

---

## 1. Summary

- **This adaptor does the Beckn work in the HIE.** It replaces the separate "openhim-beckn-service" that was planned before. There is no new service.
- **New inbound endpoint.** `POST /beckn/bpp-webhook/{action}` takes an `update` from an HMIS, takes the FHIR resource out of it, and posts the resource to an OpenHIM channel.
- **The pipeline stays as it is.** The channel routes to the existing `/inbound`. Source resolution, FHIR parsing, patient id, facility id, clinical data redaction and the CloudEvent are unchanged.
- **New output, chosen per source.** `cce.emitter.sources.<key>.output` is `collector` (today's behaviour) or `beckn`. A source without an explicit output follows `beckn.enabled`: `beckn` when it is on (the default on this branch), else `collector`. With `beckn`, the CloudEvent is wrapped in a `CceClinicalEvent` box and sent to CCE as an `update` through `openhim-onix`.
- **New callback endpoint.** `POST /beckn/bap-webhook/{action}` takes CCE's `on_update` and logs it.
- **One answer shape.** Both Beckn endpoints answer with CCE's `Ack` / `Nack` body (§3.3). CCE's own answer is read the same way and passed back to the HMIS (§5.4).
- **No queue, no database.** Every answer comes back in the same HTTP call, hop by hop. All ids and addresses are settings.
- **Redaction stays here.** CCE gets the same minimised content as on today's path.

The HIE's Beckn adapter, `openhim-onix`, runs two containers: `onix-bpp` towards the HMIS systems and `onix-bap` towards CCE. It belongs to the HIE team. It signs what goes out and checks the signature and schema of what comes in.

---

## 2. Flows

```
---- OpenMRS to CCE ----
openmrs-emitter-adaptor → openmrs-onix(bap) ══► openhim-onix(bpp) → openhim-emitter-adaptor (unwrap: FHIR + headers) → OpenHIM channel
                                                                        → openhim-emitter-adaptor (existing pipeline, CloudEvent) → wrap → openhim-onix(bap) ══► cce-onix(bpp) → cce-beckn-service → CCE
---- Spice to CCE ----
fhir-emitter → spice-beckn-service → spice-onix(bap) ══► openhim-onix(bpp) → … the same …
---- Spice to OpenMRS ----   (does not touch the HIE)
Spice → fhir-emitter → spice-beckn-service → spice-onix(bap) ══► openmrs-onix(bpp) → openmrs-receiver-adaptor → OpenMRS
---- OpenMRS to Spice ----   (does not touch the HIE)
OpenMRS → openmrs-emitter-adaptor → openmrs-onix(bap) ══► spice-onix(bpp) → spice-beckn-service → Spice
```

══ marks a Beckn hop: signed by the sender's adapter and checked by the receiver's adapter. → marks plain HTTP. `openhim-emitter-adaptor` is this adaptor.

Only the first two flows touch this adaptor. Inside the HIE they look like this:

```
openhim-onix(bpp) ──► CCE Emitter Adaptor: POST /beckn/bpp-webhook/update
  (from the HMIS)         1. unwrap: FHIR + headers ──► OpenHIM channel (client auth, transaction log)
                                                              │
                      CCE Emitter Adaptor: POST /inbound ◄────┘
                          2. existing pipeline → CloudEvent
                          3. output for the source:
                               collector → POST {collector}/v1/events             (today)
                               beckn     → wrap → POST onix-bap /bap/caller/update
                                                  └─► openhim-onix(bap) ══► cce-onix(bpp) → cce-beckn-service :8075 /api/bpp-webhook/update

openhim-onix(bap) ──► CCE Emitter Adaptor: POST /beckn/bap-webhook/on_update       (CCE's answer, logged)
```

### 2.1 Roles and contracts

| Contract | Consumer (BAP) | Provider (BPP, issues the ids) | What travels on it |
|---|---|---|---|
| HIE feed, one per HMIS | The HMIS (OpenMRS, Spice) | OpenHIM | Events as `update`. `on_update` with `ACCEPTED` |
| HIE to CCE | OpenHIM | CCE | Events as `update`. `on_update` with `ACCEPTED` or `DUPLICATE` |

No message opens a contract in the demo (no `confirm`), so no contract id is issued. The sender puts a non-blank contract id of its choosing in each `update`; the receiver does not match it against settings, as `cce-beckn-service` does, and echoes it in `on_update`. On the HMIS leg, OpenHIM accepts any non-blank contract id and repeats its own commitment (`hie-event-feed`, §3.4). Towards CCE, OpenHIM sends `beckn.cce.contract-id` and CCE's fixed commitment (`cce-care-coordination`, §5.3).

| Container | OpenHIM plays | Called by this adaptor | Calls this adaptor |
|---|---|---|---|
| `onix-bpp` | Provider, towards each HMIS | `/bpp/caller/on_update` | `/beckn/bpp-webhook/{action}` |
| `onix-bap` | Consumer, towards CCE | `/bap/caller/update` | `/beckn/bap-webhook/{action}` |

---

## 3. Role 1: the unwrap endpoint

`POST /beckn/bpp-webhook/{action}`, called by `onix-bpp`. The adapter has already checked the Beckn signature and the schema. It appends the action name to the path.

### 3.1 Steps for `update`

| Step | What the adaptor does | If it fails |
|---|---|---|
| 1 | Finds the HMIS by `context.bapId` in `beckn.subscribers[]` | `Nack` (400) `BAD_REQUEST`, unknown subscriber |
| 2 | Checks `message.contract.id` is present. Any value is accepted | `Nack` (400) `BAD_REQUEST`, missing contract id |
| 3 | Checks there is **exactly one** `performance` entry with an `id`, its box is `cce:CceClinicalEvent`, and `resource` has a `resourceType` | `Nack` (400) `BAD_REQUEST`. The text names the field. None or more than one entry is refused |
| 4 | Takes `performance[0].performanceAttributes.resource`, as it is | — |
| 5 | Posts the resource to the OpenHIM channel URL, with the OpenHIM client credentials and the headers below | See §3.3 |
| 6 | Maps the channel's answer to `Ack` or `Nack` (§3.3) | — |
| 7 | After `Ack`, sends `on_update` with `ACCEPTED` to the HMIS (§3.4) | Logged and counted. The `Ack` stands |

The box (`CceClinicalEvent`) holds `eventType`, `patientId`, `fhirVersion`, `resource` and optionally `sourceEventId`. Only `resource` goes to the channel. The pipeline reads the patient and the facility from the resource itself, as on today's path. The other box fields are logged.

### 3.2 Headers sent to the channel

| Header | Value | Read by the pipeline in | Effect |
|---|---|---|---|
| `Authorization` | Basic auth of the OpenHIM client for this endpoint | OpenHIM Core | Client auth on the channel |
| `Content-Type` | `application/json` | — | As today |
| `X-Source-System` | The subscriber's `source-system` | `SourceAdaptorService.resolveSource()` | Selects the source key, so also the output (§5) |
| `X-Source-Event-Id` | `performance[0].id` | `SourceAdaptorService.buildSourceMetadata()` → `EventIdGenerator` | CloudEvent `sourceeventid`. The CloudEvent `id` becomes a UUID v5 of `<source key>:<performance[0].id>`, so it is fixed for the same event |
| `X-Correlation-Id` | `context.messageId` | `SourceAdaptorService.buildSourceMetadata()` | CloudEvent `correlationid`, **but only when `X-OpenHIM-TransactionID` is absent** (§9.1) |

No `X-Facility-Id` is sent. The pipeline falls back to the resource's location (`FacilityIdExtractor`), as for any source that sends no such header.

**Source matching order.** `resolveSource()` tries `X-OpenHIM-ClientID` first and `X-Source-System` second. OpenHIM Core sets `X-OpenHIM-ClientID` to the client of this endpoint. So that client's id **must not** be the `client-id` of any configured source, or every Beckn event is credited to that source. The adaptor refuses to start if it is (§7).

### 3.3 How the channel's answer is mapped

The channel's route to `/inbound` must be **primary**, so that the mediator's answer comes back to the caller (see [OpenHIM Channel Setup §9](openhim-channel-setup.md#9-beckn-channel-proposal)). OpenHIM Core unpacks the `application/json+openhim` envelope and returns its `response.status` and `response.body`.

| Channel answers | Body | Where it comes from (`/inbound`) | HMIS gets |
|---|---|---|---|
| 202 | `ProcessedEventsResponse` (`"status":"processed"`, `eventsForwarded` ≥ 1) | Event forwarded and accepted | `Ack` (200), then `on_update` `ACCEPTED` |
| 200 | `"status":"ignored"` | No source matched, body is not FHIR, or a `Bundle` (`InboundEventService`, `SourceAdaptorService.adapt()`) | `Nack` (400) `EVENT_REJECTED`, text `ignored: <reason>` |
| 200 | `"status":"skipped"` | Facility not in `cce.emitter.facility-filter.ids` | `Nack` (400) `EVENT_REJECTED`, text `skipped: <reason>` (§15) |
| 400 | `PATIENT_ID_NOT_FOUND` | No patient reference in the resource | `Nack` (400) `EVENT_REJECTED`, text = the code and message |
| 422 | `FHIR_MAPPING_ERROR` | Resource cannot be parsed | `Nack` (400) `EVENT_REJECTED`, text = the code and message |
| 4xx | `COLLECTOR_CLIENT_ERROR` | Collector 4xx | `Nack` (400) `EVENT_REJECTED`, text = the collector's message |
| 4xx | `COLLECTOR_CLIENT_ERROR`, message `CCE <code>: <text>` | CCE `Nack` (400) on the Beckn output (§5.4) | CCE said `EVENT_REJECTED`: `Nack` (400) `EVENT_REJECTED`, text `CCE: <CCE's text>`. CCE said `BAD_REQUEST` or `NOT_SUPPORTED`, or CCE's adapter refused the schema: `Nack` (500) `HIE_ERROR`, text `CCE <code>: <CCE's text>`. That message was this adaptor's own `update`, so the HMIS cannot fix it |
| other 4xx (401, 403, 404) | — | OpenHIM: client auth failed, no channel matched | `Nack` (500) `HIE_ERROR`, text = OpenHIM's answer. An HIE setup error, not the HMIS's |
| 5xx, 408, 429 | `COLLECTOR_FORWARDING_ERROR` (502), `INTERNAL_ERROR` (500), or OpenHIM's own 500 | Collector or CCE down or slow, unexpected error, route failed | `Nack` (500) `UNAVAILABLE` |
| no answer | — | Timeout, connection refused | `Nack` (500) `UNAVAILABLE` |

**Answer bodies.** Both Beckn endpoints answer in the shape `cce-beckn-service` uses (its `MessageFactory`). The `messageId` is always the one of the request.

```json
{ "message": { "status": "ACK", "messageId": "9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d" } }
```

```json
{ "message": { "status": "NACK", "messageId": "9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d",
               "error": { "code": "EVENT_REJECTED", "message": "CCE: Payload validation failed: …" } } }
```

| Code | HTTP | Meaning | What the HMIS should do |
|---|---|---|---|
| — (`ACK`) | 200 | CCE has the event | Mark it sent. Do not send again |
| `BAD_REQUEST` | 400 | A rule is broken in the HMIS's message: unknown subscriber or contract, not exactly one entry, a missing field | Fix the message. Do not resend as it is |
| `NOT_SUPPORTED` | 400 | An action other than `update` (§3.5) | Do not send it |
| `EVENT_REJECTED` | 400 | The pipeline refused the event (`ignored`, `skipped`, no patient, bad FHIR), or CCE refused it | Fix the event. A corrected event needs a new performance id |
| `UNAVAILABLE` | 500 | Something that may pass: OpenHIM, the collector, CCE or an onix container is down or slow | Send the same event again later, same performance id, new `messageId` |
| `HIE_ERROR` | 500 | A fault in the HIE: this adaptor's own `update` broke a CCE rule, or OpenHIM refused this adaptor's client (401, 403, 404). The HMIS's message was fine | Send the same event again later. The HIE team must fix the fault. The text says what it is |

**Why "ignored" and "skipped" are a `Nack`.** `/inbound` answers 200 for both because it sits on a secondary route today and must not mark OpenHIM transactions as failed. On the Beckn path, a 200 would turn into an `Ack`, and the HMIS would see success for an event that never reached CCE.

### 3.4 `on_update` to the HMIS

Sent after the `Ack` has been returned, through `onix-bpp`'s `/bpp/caller/on_update`. No state is kept: every answer on the route comes back in the same call, so the `Ack` already means CCE accepted the event.

| Field | Value |
|---|---|
| `context.action` | `on_update` |
| `context.networkId` | Copied from the `update` when it has one, as CCE does |
| `context.bapId`, `bapUri` | The subscriber's `subscriber-id`, `subscriber-uri` |
| `context.bppId`, `bppUri` | `beckn.self.bpp-id`, `bpp-uri` |
| `context.transactionId`, `messageId` | Those of the `update` it answers |
| `message.contract.id` | The `update`'s `message.contract.id` |
| `message.contract.status` | `{ "code": "ACTIVE" }` |
| `message.contract.commitments` | From the subscriber's `commitment-file`: OpenHIM's own HIE feed commitment (below) |
| `message.contract.performance[0]` | `{ "id": <the update's performance[0].id>, "status": { "code": "ACCEPTED" } }` |

It is sent once. A failure is logged and counted, and the `Ack` stands. The HMIS should not depend on it (the same rule CCE gives for its own `on_update`, §6).

The HIE feed commitment is OpenHIM's own. It has the same shape as CCE's (§5.3), with OpenHIM's ids. Each HMIS sends it in every `update` on its HIE feed contract, and OpenHIM repeats it in `on_update`:

```json
{
  "id": "hie-event-feed",
  "status": { "descriptor": { "code": "ACTIVE" } },
  "resources": [
    { "id": "hie-event-feed-resource",
      "descriptor": { "name": "HIE event feed" },
      "quantity": { "count": 1 } }
  ],
  "offer": { "id": "hie-event-feed-offer",
             "resourceIds": ["hie-event-feed-resource"] }
}
```

### 3.5 Other actions

| Action | Answer |
|---|---|
| `update` | §3.1 |
| `confirm`, `status`, `cancel` | `Nack` (400) `NOT_SUPPORTED`: not supported in the demo |
| `select`, `init`, `track`, `rate`, `support`, any other | `Nack` (400) `NOT_SUPPORTED` |

### 3.6 Example inbound `update`

Adapted from design §5.2. OpenMRS is the consumer, OpenHIM the provider of the HIE feed contract.

```json
{
  "context": {
    "version": "2.0.0",
    "action": "update",
    "bapId": "bap.openmrs.example.org",
    "bapUri": "https://openmrs.example.org/bap/receiver",
    "bppId": "bpp.openhim.example.org",
    "bppUri": "https://hie.example.org/bpp/receiver",
    "transactionId": "0d5f8c1a-7b2e-4f6d-9a3c-1e2b3c4d5e6f",
    "messageId": "9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d",
    "timestamp": "2026-11-13T08:00:35Z"
  },
  "message": {
    "contract": {
      "id": "5b1e7f3c-2a4d-4c6e-8f90-a1b2c3d4e5f6",
      "commitments": [ { "id": "hie-event-feed", "...": "the same in every message" } ],
      "performance": [
        {
          "id": "3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10",
          "performanceAttributes": {
            "@context": "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v1.0/context.jsonld",
            "@type": "cce:CceClinicalEvent",
            "eventType": "Encounter",
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
        }
      ]
    }
  }
}
```

What goes to the channel:

```
POST https://openhim-core:5001/cce-beckn
Authorization: Basic <cce-beckn-endpoint credentials>
Content-Type: application/json
X-Source-System: openmrs-kicukiro
X-Source-Event-Id: 3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10
X-Correlation-Id: 9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d

{ "resourceType": "Encounter", "id": "7d2c9b1e-4f3a-4e8b-9c6d-5a1b2c3d4e5f", ... }
```

---

## 4. Role 2: the existing pipeline (unchanged)

The OpenHIM channel routes the request to `POST /inbound`. Nothing in this path changes.

| Step | Component | What it does |
|---|---|---|
| 1 | `InboundEventController` | Builds `InboundRequest`, calls `InboundEventService.process()` |
| 2 | `SourceAdaptorService.resolveSource()` | `X-OpenHIM-ClientID`, then `X-Source-System` → source key. No match → 200 `ignored` |
| 3 | `FhirResourceParser` | Parses the body. Not FHIR or a `Bundle` → 200 `ignored`. Unparseable → 422 |
| 4 | `PatientIdExtractor` | Patient UPID → CloudEvent `subject`. None → 400 |
| 5 | `buildSourceMetadata()` + `FacilityIdExtractor` | Facility from `X-Facility-Id`, else from the resource's location. Source event id and correlation id from headers |
| 6 | `FacilityFilter` | Facility not allowed → 200 `skipped` |
| 7 | `CloudEventEnvelopeBuilder` + `ClinicalDataRedactor` | Redacts the resource, builds the CloudEvent. `EventIdGenerator` gives a UUID v5 when a source event id is present |
| 8 | Output (new choice, §5) | `collector`: `CollectorForwardingService` as today. `beckn`: `BecknForwardingService` |
| 9 | `OpenHimResponseWrapper` | Wraps the answer in `application/json+openhim` |

The only change is at step 8: `InboundEventService` asks a small router which output the event's source uses, instead of calling `CollectorForwardingService` directly.

---

## 5. Role 3: the Beckn output

### 5.1 Choosing the output

| `cce.emitter.sources.<key>.output` | Behaviour |
|---|---|
| `collector` | Today's behaviour. `CollectorForwardingService` posts the CloudEvent to `{cce.collector.url}/v1/events` |
| `beckn` | The CloudEvent is wrapped and sent to CCE as `update` through `onix-bap` |

The choice is per source, not per request. A source used by a Beckn subscriber (its `source-system`) is normally set to `beckn`. A source whose system still posts FHIR through OpenHIM stays on `collector`. `output: beckn` with `beckn.enabled=false` is a startup error.

### 5.2 CloudEvent → `CceClinicalEvent` box

| Box field | From the CloudEvent | Note |
|---|---|---|
| `performance[0].id` | `id` | The UUID v5 from `EventIdGenerator`, so fixed for the same HMIS event. 36 characters, within CCE's limit of 50. CCE uses it unchanged as its CloudEvent `id` |
| `@context` | — | `<beckn.schema-base-url>/CceClinicalEvent/v1.0/context.jsonld` |
| `@type` | — | `cce:CceClinicalEvent` |
| `eventType` | `type` | The FHIR resource type, e.g. `Encounter`. CCE requires a non-empty value and asks that the values be agreed with the CCE team (its own example is `org.openphc.cce.encounter`) |
| `patientId` | `subject` | Patient UPID |
| `facilityId` | `facilityid` | Left out when null |
| `facilityName` | `facilityname` | The CloudEvent has no `facilityname` today (`CloudEventDto`). Left out until it does |
| `sourceEventId` | `sourceeventid` | On the Beckn path this is the HMIS's `performance[0].id` |
| `fhirVersion` | — | Always `4.0.1` |
| `resource` | `data` | The **redacted** FHIR resource |

**How CCE rebuilds the CloudEvent.** `cce-beckn-service` does not receive this adaptor's CloudEvent. It builds a new one from the `update` (its `CloudEventBuilder`):

| This adaptor's CloudEvent | Carried as | CCE's CloudEvent | Survives? |
|---|---|---|---|
| `id` | `performance[0].id` | `id` | Yes, unchanged |
| `source` | — | `source` = `context.bapId` (OpenHIM's `beckn.self.bap-id`) | No. The source key does not reach CCE |
| `type` | `eventType` | `type` | Yes |
| `subject` | `patientId` | `subject` | Yes |
| `time` | — | `time` = `context.timestamp` of the `update` | No. It is the time the `update` was sent |
| `correlationid` | — | `correlationid` = `context.messageId` of the `update` | No. It is this adaptor's outbound `messageId` |
| `facilityid` | `facilityId` | `facilityid` | Yes, when set |
| (none) | `facilityName` | `facilityname` | Not sent today (§9.3) |
| `sourceeventid` | `sourceEventId` | `sourceeventid` | Yes |
| `data` | `resource` | `data` | Yes, the redacted resource |
| `specversion` | — | `1.0` | Same value |
| `datacontenttype` | — | `application/fhir+json` | Set by CCE |
| `protocolinstanceid`, `protocoldefinitionid`, `actionid` | — | — | No. The box has no field for them. Nothing is lost today: this adaptor never fills them. CCE's matcher reads them only when set (`MatcherEngine`), for an event that names its protocol step itself. If a source ever needs that, the schema needs these fields |

### 5.3 The `update` to CCE

Same shape as the inbound `update`, with OpenHIM as consumer and CCE as provider, on the HIE's own contract with CCE.

| Field | Value |
|---|---|
| `context.networkId` | `beckn.self.network-id`. Left out when not set. CCE copies it into its `on_update` |
| `context.bapId`, `bapUri` | `beckn.self.bap-id`, `bap-uri`. CCE takes `bapId` (at most 100 characters) as the CloudEvent `source` |
| `context.bppId`, `bppUri` | `beckn.cce.bpp-id`, `bpp-uri` |
| `context.transactionId` | `beckn.cce.transaction-id` |
| `context.messageId` | A new UUID per message, also per new try (at most 100 characters) |
| `context.timestamp` | Now, UTC. CCE takes it as the CloudEvent `time` |
| `message.contract.id` | `beckn.cce.contract-id` |
| `message.contract.commitments` | CCE's fixed commitment, from `beckn.cce.commitment-file` (below) |
| `message.contract.performance` | **Exactly one entry**: the box from §5.2. CCE refuses any other count with `BAD_REQUEST` |

Posted to `{beckn.onix.bap-caller-url}/update`, that is `onix-bap`'s `/bap/caller/update`. `cce-onix` (bpp) passes it to `cce-beckn-service` at `POST /api/bpp-webhook/update`, port 8075.

**The commitment.** CCE expects this exact commitment in every `update` and repeats it in every message it sends. `beckn.cce.commitment-file` is a copy of `cce-beckn-service`'s `src/main/resources/commitment.json`. CCE does not read it, but both adapters check it against the Beckn core schema.

```json
{
  "id": "cce-care-coordination",
  "status": { "descriptor": { "code": "ACTIVE" } },
  "resources": [
    { "id": "cce-care-coordination-resource",
      "descriptor": { "name": "CCE care coordination" },
      "quantity": { "count": 1 } }
  ],
  "offer": { "id": "cce-care-coordination-offer",
             "resourceIds": ["cce-care-coordination-resource"] }
}
```

```json
{
  "context": {
    "version": "2.0.0",
    "action": "update",
    "bapId": "bap.openhim.example.org",
    "bapUri": "https://hie.example.org/bap/receiver",
    "bppId": "bpp.mdtlabs.org",
    "bppUri": "https://cce.example.org/bpp/receiver",
    "transactionId": "4a7b9c2d-3e5f-4a1b-8c6d-7e8f9a0b1c2d",
    "messageId": "c7e1d2f3-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
    "timestamp": "2026-11-13T08:00:36Z"
  },
  "message": {
    "contract": {
      "id": "9d4e2f6a-1b3c-4d5e-8f70-b1c2d3e4f5a6",
      "commitments": [ { "id": "cce-care-coordination", "...": "the fixed commitment above" } ],
      "performance": [
        {
          "id": "b2d6e8f0-1a3c-5e7f-9b1d-3f5a7c9e1b3d",
          "performanceAttributes": {
            "@context": "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v1.0/context.jsonld",
            "@type": "cce:CceClinicalEvent",
            "eventType": "Encounter",
            "patientId": "1198503150001234",
            "facilityId": "0002",
            "fhirVersion": "4.0.1",
            "sourceEventId": "3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10",
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
        }
      ]
    }
  }
}
```

`performance[0].id` is the CloudEvent `id`: UUID v5 of `openmrs-kicukiro:3f6c1c1e-…`. The resource is the redacted one. Here nothing was removed because the sample `Encounter` has no clinical fields.

### 5.4 How `onix-bap`'s answer is mapped

The Beckn output gives `InboundEventService` the same results the collector path gives, so the `/inbound` answer, the OpenHIM transaction and the existing metrics behave the same.

CCE's answer comes back through `cce-onix` and `onix-bap` in the same HTTP call. Its body has the shape in §3.3. CCE's codes (from `cce-beckn-service`):

| CCE answers | Meaning | Treated like | `/inbound` answers | HMIS gets (§3.3) | Existing metric |
|---|---|---|---|---|---|
| 200 `ACK` | CCE has the event | Collector 202 `accepted` | 202, `eventsAccepted: 1` | `Ack` | `cce.emitter.events.forwarded` |
| 400 `NACK` `EVENT_REJECTED` | The collector refused the event. No retry | Collector 4xx: `CollectorClientException` | 400 `COLLECTOR_CLIENT_ERROR`, message `CCE EVENT_REJECTED: <CCE's text>` | `Nack` (400) `EVENT_REJECTED`, CCE's text | `cce.emitter.events.rejected` |
| 400 `NACK` `BAD_REQUEST` or `NOT_SUPPORTED`, or a 400 from `cce-onix`'s schema check | This adaptor's `update` broke a CCE rule. No retry | Collector 4xx | 400 `COLLECTOR_CLIENT_ERROR`, message `CCE <code>: <CCE's text>` | `Nack` (500) `HIE_ERROR`, CCE's text | `cce.emitter.events.rejected` |
| 500 `NACK` `COLLECTOR_UNAVAILABLE` or `INTERNAL_ERROR`, 408, 429, other 5xx, timeout, connection refused | May pass. Send again later | Collector failure: `CollectorForwardingException` | 502 `COLLECTOR_FORWARDING_ERROR` | `Nack` (500) `UNAVAILABLE` | `cce.emitter.collector.retries` |

So a refusal (400) is never retried, and an answer that may pass is handled like a collector failure today. The `COLLECTOR_CLIENT_ERROR` message carries CCE's code and text, so the unwrap endpoint can pass them to the HMIS (§3.3).

Differences from the collector path:

| Aspect | Collector path (today) | Beckn output (proposal) |
|---|---|---|
| Wait per call | `cce.collector.timeout` = 15 s (longer than the collector's Kafka publish time) | `beckn.onix.timeout` = 25 s. CCE's `Ack` can take up to 17 s (§8.3) |
| Retries | `cce.collector.retry.max-attempts` = 3, backoff 1 s × 2 | `beckn.cce.retry.max-attempts` = 1 (no retry). One try of up to 25 s already fills the channel's 30 s (§8.3). The HMIS resends instead; the fixed event id lets CCE answer `DUPLICATE` (§8.4) |
| Duplicate | Known at once: collector 200 → `eventsDuplicate` | Not known at `Ack` time. CCE says `DUPLICATE` later in `on_update` (§6) |
| Orchestration entry | `Forward to CCE Collector`, `POST /v1/events` | `Forward to CCE over Beckn`, `POST /bap/caller/update` |
| Auth | Bearer token from `CollectorTokenService` | None from this adaptor. `onix-bap` signs the message |

### 5.5 OpenHIM in CCE's HMIS list

The contract id and `transactionId` OpenHIM sends to CCE are agreed with CCE once (no `confirm`; cce-beckn-service only checks the contract id is non-blank). OpenHIM must be entered in CCE's HMIS list (`cce-beckn-service`'s `config/hmis.yml`), with OpenHIM's consumer id as `subscriber-id`. The same values go into `beckn.self.*` and `beckn.cce.*` here (§7).

```yaml
cce:
  beckn:
    hmis:
      - subscriber-id: bap.openhim.example.org             # = beckn.self.bap-id
        subscriber-uri: https://hie.example.org/bap/receiver # = beckn.self.bap-uri
        contract-id: 9d4e2f6a-1b3c-4d5e-8f70-b1c2d3e4f5a6     # = beckn.cce.contract-id
        transaction-id: 4a7b9c2d-3e5f-4a1b-8c6d-7e8f9a0b1c2d  # = beckn.cce.transaction-id
```

CCE does not use this list to accept events; it has no allow-list for them. It uses the list to send alerts (`on_status`), which are out of scope here (§6). OpenHIM's id and keys must also be in the network registry, so that `cce-onix` can check the signature.

---

## 6. The callback endpoint

`POST /beckn/bap-webhook/{action}`, called by `onix-bap` with callbacks from CCE.

| Action | From | What the adaptor does | Answer |
|---|---|---|---|
| `on_update` | CCE (`bppId` = `beckn.cce.bpp-id`; the contract id is not checked) | Logs `messageId`, `performance[0].id` (the CloudEvent `id`) and the status, `ACCEPTED` or `DUPLICATE`. On `DUPLICATE`, also counts `cce.emitter.events.duplicate` | `Ack` |
| `on_update` from another `bppId`, or another contract | — | Logs it | `Nack` (400) `BAD_REQUEST` |
| `on_status` (CCE alerts), any other | — | Logs it. CCE alerts are out of scope for now | `Nack` (400) `NOT_SUPPORTED` |

Answers use the body shape in §3.3, with the callback's `messageId`.

What CCE's `on_update` looks like and how far to trust it (from `cce-beckn-service`):

- It keeps the `messageId` of this adaptor's `update`. Its performance id is the event id, that is the CloudEvent `id`. That is how the log line is matched to the `update`.
- Its `context.networkId` is copied from the `update`, when the `update` had one.
- **It can arrive before the `Ack`.** The two travel on separate connections. The endpoint accepts it either way.
- **It may not arrive at all.** CCE sends it once. The `Ack` is the proof that CCE has the event.
- `DUPLICATE` means CCE already had an event with this id from OpenHIM. It follows a resend (§8.4). Nothing was counted twice.

So it is **logged only**. It never changes what `/inbound` or the unwrap endpoint answered. Nothing is stored and nothing is passed back to the HMIS: the HMIS already got its `Ack` and its own `on_update` (`ACCEPTED`, §3.4).

---

## 7. Settings (proposal)

```yaml
beckn:
  enabled: ${OPENHIM_EMITTER_BECKN_ENABLED:false}                # off: no Beckn endpoints, no Beckn output
  # Start of the @context address. The schema files are in cce-beckn-service's schema/ folder.
  schema-base-url: ${OPENHIM_EMITTER_BECKN_SCHEMA_BASE_URL:https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema}
  onix:
    bap-caller-url: ${OPENHIM_EMITTER_BECKN_ONIX_BAP_CALLER_URL:http://openhim-onix-bap:8081/bap/caller}
    bpp-caller-url: ${OPENHIM_EMITTER_BECKN_ONIX_BPP_CALLER_URL:http://openhim-onix-bpp:8082/bpp/caller}
    timeout: ${OPENHIM_EMITTER_BECKN_ONIX_TIMEOUT_MS:25000}      # see §8.3
  self:                                          # OpenHIM's own Beckn ids
    network-id: ${OPENHIM_EMITTER_BECKN_SELF_NETWORK_ID:}        # optional; goes into context.networkId
    bap-id: bap.openhim.example.org              # at most 100 characters; CCE's CloudEvent source
    bap-uri: https://hie.example.org/bap/receiver
    bpp-id: bpp.openhim.example.org
    bpp-uri: https://hie.example.org/bpp/receiver
  cce:                                           # issued by CCE
    bpp-id: bpp.mdtlabs.org
    bpp-uri: https://cce.example.org/bpp/receiver
    contract-id: 9d4e2f6a-1b3c-4d5e-8f70-b1c2d3e4f5a6
    transaction-id: 4a7b9c2d-3e5f-4a1b-8c6d-7e8f9a0b1c2d
    commitment-file: classpath:beckn/commitment-cce.json   # copy of cce-beckn-service commitment.json
    retry:
      max-attempts: 1                            # no retry inside the route, see §8.4
  openhim-channel:                               # where the unwrap endpoint posts the FHIR
    url: ${OPENHIM_EMITTER_BECKN_OPENHIM_CHANNEL_URL:https://openhim-core:5001/cce-beckn}
    username: ${OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_ID:cce-beckn-endpoint}
    password: ${OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_PASSWORD}
    timeout: ${OPENHIM_EMITTER_BECKN_OPENHIM_CHANNEL_TIMEOUT_MS:35000}   # see §8.3
  subscribers:                                   # one per HMIS
    - subscriber-id: bap.openmrs.example.org
      subscriber-uri: https://openmrs.example.org/bap/receiver
      commitment-file: classpath:beckn/commitment-hie-feed.json
      source-system: openmrs-kicukiro
    - subscriber-id: bap.spice.example.org
      subscriber-uri: https://spice.example.org/bap/receiver
      commitment-file: classpath:beckn/commitment-hie-feed.json
      source-system: spice-beckn

cce:
  emitter:
    sources:
      ebuzima:
        client-id: ${EBUZIMA_CLIENT_ID}          # no output: collector, as today
      openmrs-kicukiro:                          # no client-id: matched by X-Source-System only
        output: beckn
      spice-beckn:
        output: beckn
```

| Setting | Meaning |
|---|---|
| `beckn.enabled` | Turns on both Beckn endpoints and allows `output: beckn`. Default `true` on the `demo-beckn` branch (`OPENHIM_EMITTER_BECKN_ENABLED`) |
| `beckn.schema-base-url` | Base URL of the `CceClinicalEvent` schema, for `@context`. Default: CCE's, `https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema`. The schema files are in `cce-beckn-service`'s `schema/` folder; both `openhim-onix` containers need them (a local copy or a download) |
| `beckn.onix.bap-caller-url` | `onix-bap`'s caller. The adaptor appends `/update` |
| `beckn.onix.bpp-caller-url` | `onix-bpp`'s caller. The adaptor appends `/on_update` |
| `beckn.onix.timeout` | Connect and read timeout for both onix calls, in ms. Default 25 000 (§8.3) |
| `beckn.self.network-id` | Optional. When set, put into `context.networkId` of the `update` to CCE |
| `beckn.self.*` | OpenHIM's consumer id and URI (towards CCE) and provider id and URI (towards the HMIS systems). `bap-id` at most 100 characters |
| `beckn.cce.*` | CCE's provider id and URI, and the ids and commitment of the HIE's contract with CCE. All issued by CCE (§5.5) |
| `beckn.cce.retry.max-attempts` | Tries per `update` to CCE. Default 1 |
| `beckn.openhim-channel.url` | The channel the unwrap endpoint posts to |
| `beckn.openhim-channel.username`, `password` | The OpenHIM client for the unwrap endpoint |
| `beckn.openhim-channel.timeout` | Wait for the channel's answer, in ms. Default 35 000 (§8.3) |
| `beckn.subscribers[]` | One entry per HMIS: its Beckn id and URI, the HIE feed commitment, and the source key its events use. No contract or transaction id: the HMIS's own are accepted and echoed |
| `cce.emitter.sources.<key>.output` | `collector` or `beckn`. Unset: follows `beckn.enabled` (`beckn` when on) |

Startup checks (proposal):

| Check | On failure |
|---|---|
| A source has `output: beckn` but `beckn.enabled` is `false` | Fail to start |
| A subscriber's `source-system` is not a key under `cce.emitter.sources` | Fail to start |
| `beckn.openhim-channel.username` equals the `client-id` of a source | Fail to start (it would win over `X-Source-System`, §3.2) |
| A `commitment-file` cannot be read | Fail to start |
| `beckn.self.bap-id` is longer than 100 characters | Fail to start (CCE would refuse every `update`) |
| A subscriber's source has `output: collector` | Warn. Allowed: the event then goes to the collector directly |

---

## 8. Consequences

### 8.1 What changes for CCE

- **CCE sees OpenHIM as the sender for every facility.** `cce-beckn-service` sets the CloudEvent `source` from `context.bapId`, which is `bap.openhim.example.org`. The source key (`openmrs-kicukiro`) does not reach CCE. CCE tells facilities apart by `facilityId`.
- **CCE gets the same content as today.** Redaction still happens here, before the box is filled.
- **The event id survives end to end.** The HMIS's `performance[0].id` becomes `X-Source-Event-Id`, then a fixed CloudEvent `id`, then the outbound `performance[0].id`, then the CloudEvent `id` at CCE, unchanged. The id is not the HMIS's own value: it is a 36-character UUID v5 of `<source key>:<performance[0].id>`, within CCE's limit of 50.
- **CCE knows an event by (OpenHIM's `bapId`, id).** All events reach CCE with the same `bapId`. The source key inside the UUID v5 keeps two HMIS systems' ids apart. A resent event gets the same id each time, so CCE answers `Ack` and then `DUPLICATE`, and counts nothing twice.
- **A corrected event gets a new id by itself.** After CCE answers `EVENT_REJECTED`, a corrected event needs a new id (with the old one CCE drops it as a duplicate). The HMIS's id is a hash of the resource (the OpenMRS emitter's is), so a corrected resource has a new HMIS id and therefore a new CloudEvent id. An HMIS that keeps its id after a correction would get `DUPLICATE`.
- **`source`, `time` and `correlationid` change at CCE.** `cce-beckn-service` takes `source` from `context.bapId`, `time` from the `update`'s timestamp and `correlationid` from its `messageId` (§5.2).

### 8.2 What stays the same here

- A source that keeps posting FHIR through OpenHIM keeps going to the collector.
- `/inbound`, its answers and its metrics are unchanged. On the Beckn output, the same metrics count the same outcomes (§5.4).
- The facility filter and redaction apply to Beckn events as to any other.

### 8.3 Every answer returns in the same call

About nine hops lie between the HMIS's emitter and the CCE collector, and there is no queue. Each hop waits for the hop inside it. **Each inner timeout must be shorter than the one around it.** If an outer hop gives up first, the HMIS gets `UNAVAILABLE` for an event that may have got through. Its resend then gets `Ack`, and CCE answers `DUPLICATE`.

Inside CCE, `cce-beckn-service` waits up to 15 s for its collector. So **CCE's `Ack` can take up to 17 s** (15 s + overhead). The onix adapter waits 5 s by default (`responseHeaderTimeout`). That is too short for this route. It must be raised on **both** `openhim-onix` containers, and on every other onix container in the chain. The budget, inside out:

| Hop | Waits for | At least | Setting |
|---|---|---|---|
| `openhim-onix` (bap) → `cce-onix` | CCE's `Ack` (up to 17 s) | 20 s | `onix-bap` `responseHeaderTimeout` |
| This adaptor (Beckn output) → `openhim-onix` (bap) caller | The hop above. One try | 25 s | `beckn.onix.timeout` |
| OpenHIM channel route | `/inbound` | 30 s | Channel `timeout` |
| Unwrap endpoint → OpenHIM channel | The hop above | 35 s | `beckn.openhim-channel.timeout` |
| `openhim-onix` (bpp) → unwrap endpoint | The hop above | 40 s | `onix-bpp` `responseHeaderTimeout` |
| `openmrs-onix` (bap) → `openhim-onix` (bpp) | The hop above | 45 s | HMIS adapter |
| OpenMRS emitter → `openmrs-onix` (bap) caller | The whole route | 50 s | HMIS emitter |

The numbers are a starting point to agree with the HIE, CCE and HMIS teams. Spice's chain (`spice-beckn-service` → `spice-onix`) needs the same two outer values. When the far side is slow, the HMIS emitter's poll is slowed by this wait.

### 8.4 Retries

The Beckn output does not retry by default. A retry inside a synchronous route multiplies the time every outer hop must wait: one try of up to 25 s already fills the channel's 30 s, so a second try could not finish before OpenHIM gives up. The HMIS resends instead, after `UNAVAILABLE` (500) or no answer. The collector path keeps its 3 tries.

A resend works because the ids are fixed: the HMIS sends a new `messageId` with the same performance id, this adaptor derives the same CloudEvent id and sends a new outbound `messageId`, and CCE answers `Ack` and `DUPLICATE` if it already had the event. A refusal (400) is not resent.

**Resend policy.** CCE keeps no queue for a sender and advises resending with a growing wait, for example after 1 minute, 5 minutes, 30 minutes. This adaptor keeps no queue either, so the wait is the HMIS's job. The OpenMRS emitter today retries a few seconds apart and then drops the resource. That gap is open (§15).

---

## 9. Conflicts found in the current code

These differ from what the design (§4.7) expects. They need a decision before the build.

### 9.1 `X-Correlation-Id` loses to `X-OpenHIM-TransactionID`

`SourceAdaptorService.buildSourceMetadata()` takes `X-OpenHIM-TransactionID` first. OpenHIM Core sets it on every routed request. So on the Beckn path the CloudEvent `correlationid` is the OpenHIM transaction id, not the Beckn `messageId`. It does not reach CCE anyway (§8.1).

Options: (a) accept it, and have the unwrap endpoint log `messageId` with the OpenHIM transaction id; (b) prefer `X-Correlation-Id` when the source has `output: beckn`. Proposal: (a), no change to the pipeline.

### 9.2 The current route is secondary

The adaptor sits today as a **secondary** route on the eBUZIMA channel. A secondary route's answer never reaches the client. The unwrap endpoint must post to a channel where `/inbound` is the **primary** route. Proposal: a new channel for Beckn traffic only ([OpenHIM Channel Setup §9](openhim-channel-setup.md#9-beckn-channel-proposal)).

### 9.3 The CloudEvent has no `facilityname`

`CloudEventDto` has `facilityid` but no `facilityname`. The box's `facilityName` is left out until a facility name source exists.

### 9.4 The event id is derived, not copied

`EventIdGenerator` builds the CloudEvent `id` from `<source key>:<X-Source-Event-Id>`. The id is fixed per event, but not equal to the HMIS's `performance[0].id`. If the subscriber's `source-system` changes, the same event gets a new id. This suits CCE: the id is 36 characters (CCE allows 50), and the source key keeps HMIS systems apart under OpenHIM's single `bapId` (§8.1). Proposal: keep it.

### 9.5 Metrics tag the source by client id

`InboundEventService` tags `cce.emitter.events.received` with `X-OpenHIM-ClientID`. All Beckn events are counted under the unwrap endpoint's client. `cce.emitter.events.forwarded` uses the resolved source and is not affected.

---

## 10. Proposed classes

Placed in the existing packages. Names are a proposal.

| Package | Class | New or changed | Role |
|---|---|---|---|
| `config` | `BecknProperties` | New | Binds `beckn.*` |
| `config` | `EmitterProperties.SourceProperties` | Changed | Adds `output` (`collector` default, `beckn`) |
| `config` | `RestClientConfig` | Changed | Adds `becknOnixRestClient` and `openHimChannelRestClient`, each with its own timeout |
| `config` | `BecknStartupValidator` | New | The startup checks in §7 |
| `controller` | `BecknBppWebhookController` | New | `POST /beckn/bpp-webhook/{action}`. HTTP only |
| `controller` | `BecknBapWebhookController` | New | `POST /beckn/bap-webhook/{action}`. HTTP only |
| `adaptor` | `BecknInboundUnwrapper` | New | Finds the subscriber, checks the contract and the box, builds the resource and the headers |
| `service` | `BecknInboundService` | New | Unwrap → channel → answer mapping → `on_update` |
| `service` | `OpenHimChannelClient` | New | Posts the FHIR to the channel. Reads the answer, plain or `application/json+openhim` |
| `service` | `BecknCallbackService` | New | Builds and sends `on_update` through `onix-bpp`, after the `Ack` |
| `service` | `EventForwardingRouter` | New | Picks `CollectorForwardingService` or `BecknForwardingService` by the event's source |
| `service` | `BecknForwardingService` | New | Builds the `update`, posts it to `onix-bap`, maps the answer (§5.4) |
| `service` | `InboundEventService` | Changed | Calls `EventForwardingRouter` instead of `CollectorForwardingService`. Orchestration name and path follow the output |
| `cloudevents` | `CceClinicalEventMapper` | New | CloudEvent → box (§5.2) |
| `model.beckn` | `BecknMessage`, `BecknContext`, `BecknContract`, `BecknPerformance`, `CceClinicalEvent`, `BecknAck` | New | Beckn JSON. `BecknAck` is the `Ack` / `Nack` body (§3.3), written by the endpoints and read from CCE's answer |
| `exception` | `BecknNackException` | New | A `Nack` code (`BAD_REQUEST`, `NOT_SUPPORTED`, `EVENT_REJECTED`, `UNAVAILABLE`, `HIE_ERROR`), HTTP status and text |
| `exception` | `BecknExceptionHandler` | New | `@ControllerAdvice` scoped to the Beckn controllers. Turns errors into `Nack` (400) or `Nack` (500) `UNAVAILABLE`, in the body shape of §3.3, so `GlobalExceptionHandler` stays as it is for `/inbound` |

`CollectorForwardingService`, `SourceAdaptorService`, `CloudEventEnvelopeBuilder`, `ClinicalDataRedactor` and `FacilityFilter` do not change.

---

## 11. Metrics (proposal)

Prefix `cce.emitter.`, as for the existing metrics. Prometheus turns dots into underscores and adds `_total` to counters.

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `cce.emitter.beckn.received` | Counter | `endpoint` (`bpp`, `bap`), `action` | Beckn messages received |
| `cce.emitter.beckn.answered` | Counter | `endpoint`, `action`, `result` (`ack`, `nack`, `error`), `code` | Beckn messages answered. `code` is the `Nack` code |
| `cce.emitter.beckn.channel.ignored` | Counter | `subscriber`, `status` (`ignored`, `skipped`) | Channel answered 200 without forwarding. Each one is a `Nack` |
| `cce.emitter.beckn.channel.latency` | Timer | — | Unwrap endpoint → channel → answer |
| `cce.emitter.beckn.onupdate.sent` | Counter | `subscriber`, `result` (`ok`, `failed`) | `on_update` sent to an HMIS |
| `cce.emitter.beckn.cce.update.sent` | Counter | `source`, `result` (`ack`, `nack`, `error`), `code` | `update` sent to CCE. `code` is CCE's `Nack` code |
| `cce.emitter.beckn.cce.onupdate.received` | Counter | `status` (`ACCEPTED`, `DUPLICATE`) | CCE's `on_update` |
| `cce.emitter.beckn.onix.latency` | Timer | `container` (`bap`, `bpp`), `action` | Round trip to `onix-bap` and `onix-bpp` |

Alert proposals are in [Monitoring & Alerting §8](monitoring-alerting.md#8-beckn-metrics-and-alerts-proposal).

---

## 12. Logging

| Where | Level | Logged |
|---|---|---|
| Unwrap endpoint, on receipt | INFO | `action`, `bapId`, `transactionId`, `messageId`, `performance[0].id`, `eventType` |
| Unwrap endpoint, on answer | INFO | `messageId`, result (`Ack`, `Nack`), `Nack` code and text, channel status |
| `on_update` to the HMIS | INFO / WARN on failure | `messageId`, subscriber, status |
| Beckn output | INFO / WARN | CloudEvent `id`, outbound `messageId`, CCE's answer (`ACK`, or `NACK` with code and text), time taken |
| Callback endpoint | INFO | `action`, `bppId`, `messageId`, `performance[0].id`, status |

New MDC keys, alongside the existing `correlationId`, `source`, `eventType`, `subject`: `becknMessageId`, `becknTransactionId`, `becknAction`, `subscriber`. The resource body is never logged, as today. `openhim-onix` should also not store message bodies (`storeBody: "false"`).

---

## 13. Security

- The Beckn endpoints sit next to `/inbound` and have no token check, like `/inbound` today. `onix-bpp` and `onix-bap` have checked the Beckn signature before calling them. They must be reachable only from the `openhim-onix` containers, on the internal network.
- The unwrap endpoint posts to OpenHIM with its own client, so OpenHIM still authenticates and logs every event.
- The Beckn output sends no Bearer token. `onix-bap` signs the message with OpenHIM's key, and `cce-onix` checks it.

---

## 14. Risks

- **The adapter's behaviour is untested.** The whole design needs `onix-bap` and `onix-bpp` to pass the far side's `Ack` or `Nack` back in the same HTTP call.
- **The route is long and synchronous.** About nine hops, no queue. Wrong timeouts lead to resends and errors seen by the HMIS (§8.3).
- **A wrong source mapping loses events quietly on the old path.** On the Beckn path, an `ignored` answer becomes a `Nack`, so the HMIS sees it. The `cce.emitter.beckn.channel.ignored` alert must be watched.
- **The unwrap client must not match a source's client id** (§3.2), or all Beckn events are credited to one source.
- **CCE sees one sender for all facilities.** CCE must link events by patient and facility, not by `source`.
- **The demo skips `confirm`.** A network rule or a later adapter version may require it.
- **The specification is still moving.** Several Beckn 2.0 documents are drafts. The `Ack` and `Nack` bodies and codes here are the ones `cce-beckn-service` uses. They were tested against a stub, not yet through real onix containers. Whether both adapters accept the fixed commitment is also still to be tested.

---

## 15. Open points

1. **Facility filter "skipped".** Proposal: `Nack` (400) `EVENT_REJECTED`, like "ignored". The alternative is `Ack`, if a skipped facility is not the HMIS's problem.
2. **OpenHIM 401, 403 or 404** (bad client setup). Decided: `Nack` (500) `HIE_ERROR`, so the HMIS resends once the HIE fixes its setup. The same code covers CCE's `BAD_REQUEST` and `NOT_SUPPORTED` for this adaptor's own `update`.
3. **Correlation id** (§9.1).
4. **`on_update` `DUPLICATE` to the HMIS.** With `output: collector`, the channel's body can say `eventsDuplicate: 1`. The adaptor could send `DUPLICATE` instead of `ACCEPTED` then. Not needed for the demo.
5. **One OpenHIM client per HMIS** instead of one shared client. Then `X-OpenHIM-ClientID` would select the source and `X-Source-System` would not be needed. More OpenHIM setup, cleaner transaction log.
6. **Timeout budget** (§8.3), to agree with the HIE, CCE and HMIS teams. The `responseHeaderTimeout` of both `openhim-onix` containers must be raised from 5 s.
7. **`facilityName`** (§9.3).
8. **`eventType` values.** This adaptor sends the FHIR resource type (`Encounter`). CCE accepts any non-empty value but asks that the values be agreed with the CCE team.
9. **HMIS resend policy** (§8.4). CCE advises a growing wait (1 min, 5 min, 30 min). The OpenMRS emitter retries seconds apart and then drops the resource.

---

## 16. Implementation notes (differences from the proposal)

The build follows §1–§15 except for these points.

| Topic | Proposal | Built |
|---|---|---|
| Beckn JSON classes (§10) | `BecknMessage`, `BecknContext`, `BecknContract`, `BecknPerformance` | Not built. Incoming messages are read as `JsonNode` (the resource must pass through unchanged) and outgoing ones are built as `ObjectNode` by `BecknMessageFactory`, as `cce-beckn-service` does. Typed: `BecknAck`, `CceClinicalEvent`, plus `BecknAnswer` (code, status, text) and `HttpAnswer` (status/body or no answer) |
| Extra classes | — | `config.BecknCommitments` (reads the commitment files at startup), `service.BecknMessageFactory`, `service.BecknOnixClient` (both onix callers, latency timer), `service.ChannelAnswerMapper` (§3.3), `service.CceAnswerMapper` (§5.4), `service.CceCallbackService` (the `/beckn/bap-webhook` logic; `BecknCallbackService` sends the HMIS's `on_update`) |
| `ProcessedEventsResponse` (§3.3) | `eventsProcessed` ≥ 1 | The real field is `eventsForwarded`; the unwrap endpoint takes `"status":"processed"` (or HTTP 202) as success |
| Answer text for CCE's refusals (§3.3) | "CCE's text" | `EVENT_REJECTED`: `CCE: <text>` (as in the §3.3 example). `HIE_ERROR`: `CCE <code>: <text>`, so the HIE team sees CCE's code |
| Other channel answers | Not covered | 408 and 429 → `UNAVAILABLE`. A 2xx that is not `processed`, `ignored` or `skipped` → `HIE_ERROR`. A 4xx without the pipeline's `error.code` body is treated as OpenHIM's own refusal → `HIE_ERROR` |
| `context.action` | Not covered | When set and different from the path's action → `Nack` (400) `BAD_REQUEST`, as `cce-beckn-service` does |
| Answer `messageId` | Always the request's | The request's when it is a UUID; otherwise a new UUID (same rule as `cce-beckn-service`'s `Ids.uuidOrRandom`). A body that is not JSON gets a new UUID |
| CCE's answer in a 2xx with a `NACK` body | Not covered | Treated as a refusal (or as a failure when its code is `COLLECTOR_UNAVAILABLE`, `INTERNAL_ERROR` or `UNAVAILABLE`), like `cce-beckn-service`'s `AdapterResult` |
| Beckn output refusals | `cce.emitter.events.rejected` | Same metric, tags `source=beckn`, `reason=client_error` (the collector path keeps `source=collector`) |
| `beckn.cce.retry.max-attempts` > 1 | — | Supported: a new `messageId` per try, no back-off. Default 1 |
| Settings (§7) | Literal example ids | `application.yml` reads OpenHIM's and CCE's ids from env vars with empty defaults (`OPENHIM_EMITTER_BECKN_SELF_BAP_ID`, `OPENHIM_EMITTER_BECKN_SELF_BAP_URI`, `OPENHIM_EMITTER_BECKN_SELF_BPP_ID`, `OPENHIM_EMITTER_BECKN_SELF_BPP_URI`, `OPENHIM_EMITTER_BECKN_CCE_BPP_ID`, `OPENHIM_EMITTER_BECKN_CCE_BPP_URI`, `OPENHIM_EMITTER_BECKN_CCE_CONTRACT_ID`, `OPENHIM_EMITTER_BECKN_CCE_TRANSACTION_ID`, `OPENHIM_EMITTER_BECKN_CCE_COMMITMENT_FILE`). `beckn.subscribers` is empty by default (set it in a profile). A subscriber without `commitment-file` uses `classpath:beckn/commitment-hie-feed.json` |
| Startup checks (§7) | The six listed | Also: every required id/URL is set when enabled, no `subscriber-id` twice, `max-attempts` ≥ 1. All errors are reported in one message |
| `on_update` to the HMIS (§3.4) | After the `Ack` | Scheduled on a bounded pool (2–4 threads, queue 500) when the `Ack` is decided, so it can reach the HMIS just before the `Ack`. A full queue drops it and counts `result=failed` |
| Metric tag `action` | The path's action | Unknown action names are tagged `other`, to bound the number of series |
| Logging | MDC keys | The keys are also in the log patterns: dev console prints `[beckn <action> <messageId> <subscriber>]` when set; prod JSON has `becknMessageId`, `becknTransactionId`, `becknAction`, `subscriber` |
