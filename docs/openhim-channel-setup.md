# CCE Emitter Adaptor — OpenHIM Channel Setup Guide

Step-by-step guide for the OpenHIM administrator to configure the CCE Emitter Adaptor as a secondary route on the existing eBUZIMA channel.

---

## 1. Prerequisites

| Prerequisite | Detail |
|-------------|--------|
| OpenHIM Core | v8.4.3+ running and accessible |
| OpenHIM Console | Accessible at `https://<console-host>:9000` |
| Admin credentials | OpenHIM admin account with channel + mediator permissions |
| Emitter Adaptor | Deployed and healthy (`/actuator/health/liveness` returns `UP`) |
| Existing eBUZIMA channel | The channel that routes eBUZIMA EMR traffic to the SHR/other mediators |

## 2. Verify Mediator Registration

The Emitter Adaptor automatically registers with OpenHIM Core on startup. Verify this first.

### Steps

1. Log in to **OpenHIM Console** → navigate to **Mediators** tab
2. Look for **CCE Emitter Adaptor** in the mediator list

### Expected

| Field | Value |
|-------|-------|
| Name | `CCE Emitter Adaptor` |
| URN | `urn:mediator:cce-emitter-adaptor` |
| Version | `1.0.0` |
| Heartbeat | Active (green indicator, updating every ~10 seconds) |

### If mediator is not visible

- Check adaptor logs for registration errors:
  ```bash
  docker logs cce-emitter-adaptor 2>&1 | grep -i "mediator\|registration"
  ```
- Verify `OPENHIM_CORE_HOST`, `OPENHIM_USERNAME`, `OPENHIM_PASSWORD` env vars are correct
- Registration failure is **non-fatal** — the adaptor still functions, but won't appear in OpenHIM Console

## 3. Add Secondary Route to eBUZIMA Channel

The adaptor uses an **empty `defaultChannelConfig`** by design — it does NOT auto-provision a channel. Instead, it is added as a **secondary route** on the existing eBUZIMA channel so that OpenHIM forwards a copy of each request to both the existing primary mediator (e.g., SHR) and the CCE Emitter Adaptor simultaneously.

### Steps

1. Navigate to **Channels** in OpenHIM Console
2. Find and click the **existing eBUZIMA channel** (the one routing eBUZIMA EMR traffic)
3. Go to the **Routes** tab
4. Click **Add Route**
5. Configure the route with the following values:

| Field | Value | Notes |
|-------|-------|-------|
| **Route Name** | `CCE Emitter Adaptor` | Display name in Console |
| **Host** | `<emitter-adaptor-host>` | Docker service name (e.g., `cce-emitter-adaptor`) or IP address |
| **Port** | `8082` | Adaptor HTTP port |
| **Path** | `/inbound` | Adaptor inbound endpoint |
| **Primary** | **No** (unchecked) | **Critical** — must be secondary route |
| **Route Type** | `HTTP` | Plain HTTP (or HTTPS if TLS configured) |
| **Secured** | No | Unless adaptor has TLS configured |

6. Click **Save** on the route
7. Click **Save** on the channel

### Route JSON (equivalent)

If configuring via the OpenHIM Core API directly:

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

### Why secondary route?

| Aspect | Explanation |
|--------|-------------|
| **`primary: false`** | The adaptor does NOT return the primary response to the client. The existing primary route (SHR) remains the authoritative responder. |
| **Copy of traffic** | OpenHIM Core sends a copy of each eBUZIMA request to both the primary route and all secondary routes simultaneously. |
| **All HTTP methods forwarded** | Secondary routes receive ALL requests matching the channel URL pattern, regardless of HTTP method. The adaptor gracefully handles non-POST requests (GET, PUT, DELETE, etc.) by returning `200 OK` with a "Non-POST request ignored" message — no 405 errors in the transaction log. |
| **No disruption** | Adding a secondary route does not affect existing routing. If the adaptor is down, the primary route still functions normally. |
| **Transaction log** | OpenHIM records the secondary route response in the transaction log for auditability. |

## 4. Verify the Route

### 4.1 Send a test request through the eBUZIMA channel

```bash
# Send a FHIR Encounter through the eBUZIMA OpenHIM channel
curl -k -X POST https://<openhim-core-host>:5001/ebuzima \
  -H "Content-Type: application/json" \
  -H "Authorization: Basic <base64-encoded-credentials>" \
  -d '{
    "resourceType": "Encounter",
    "id": "channel-test-001",
    "status": "finished",
    "subject": {"reference": "Patient/260225-0002-5501"},
    "period": {"start": "2026-03-09T10:00:00Z"}
  }'
```

> **Note:** Replace the URL path (`/ebuzima`) with the actual eBUZIMA channel URL pattern.

### 4.2 Check OpenHIM Transaction Log

1. Navigate to **Transactions** in OpenHIM Console
2. Find the transaction corresponding to the test request
3. Expand the transaction to see **Routes**
4. Verify:
   - Primary route (SHR) shows its normal response
   - **CCE Emitter Adaptor** secondary route shows `202 Accepted` with `application/json+openhim` response

### 4.3 Check Adaptor Metrics

```bash
curl -s http://<adaptor-host>:8082/actuator/prometheus | grep cce_emitter_events_received
# Expected: cce_emitter_events_received_total{source="ebuzima",path="/inbound"} 1.0
```

## 5. Request Headers Forwarded by OpenHIM

OpenHIM Core automatically sets certain headers when forwarding to routes. The adaptor uses these for source resolution and metadata:

| Header | Set By | Used For |
|--------|--------|----------|
| `X-OpenHIM-ClientID` | OpenHIM Core (after client auth) | Primary source resolution — matched against `cce.emitter.sources.<key>.client-id` |
| `X-Source-System` | Source system (optional) | Fallback source resolution |
| `X-Facility-Id` | Source system (optional) | Mapped to CloudEvent `facilityid` extension |
| `X-Source-Event-Id` | Source system (optional) | Mapped to CloudEvent `sourceeventid` extension |
| `X-OpenHIM-TransactionID` | OpenHIM Core (automatic) | **Highest priority** source for CloudEvent `correlationid`. Set automatically by OpenHIM Core on every routed request. |
| `X-Correlation-Id` | Source system or OpenHIM (optional) | Mapped to CloudEvent `correlationid` if `X-OpenHIM-TransactionID` is absent; generated by adaptor if both are absent |
| `Content-Type` | Source system | Expected: `application/json` |

### Client ID Configuration

The `X-OpenHIM-ClientID` value sent by OpenHIM must match the configured client ID:

```yaml
# In the adaptor's application-prod.yml (via EBUZIMA_CLIENT_ID env var)
cce:
  emitter:
    sources:
      ebuzima:
        client-id: ebuzima-emr-client  # Must match OpenHIM client ID
```

To find the eBUZIMA client ID in OpenHIM:
1. Navigate to **Clients** in OpenHIM Console
2. Find the eBUZIMA EMR client
3. Note the **Client ID** value
4. Set `EBUZIMA_CLIENT_ID` env var to this value when deploying the adaptor

## 6. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Adaptor not receiving requests | Route not added or channel not saved | Verify route exists on the eBUZIMA channel in OpenHIM Console |
| Non-POST requests showing `200 OK` with "ignored" | Normal behavior — adaptor acknowledges GET/PUT/etc. gracefully | No action needed; only POST requests are processed |
| `200 OK` with no processing | `X-OpenHIM-ClientID` doesn't match configured source | Check `EBUZIMA_CLIENT_ID` matches the OpenHIM client ID |
| Adaptor returns `502` | CCE Collector unreachable or returning 5xx | Check `CCE_COLLECTOR_URL` and Collector health |
| Adaptor returns `500` | Unexpected internal error | Check adaptor logs for stack traces; `INTERNAL_ERROR` code in response |
| Route shows as down in Console | Adaptor container not running or port not accessible | Check `docker ps` and network connectivity on port 8082 |
| Transaction log missing secondary route | Route configured as `primary: true` | Change route to `primary: false` |
| `422 FHIR_MAPPING_ERROR` | Non-FHIR payload sent through the channel | Expected for non-FHIR requests — adaptor rejects gracefully |

## 7. Facility Filter (Phased Rollout)

The facility filter restricts which events are forwarded to the CCE Collector based on facility ID. It is configured entirely via environment variables — no code changes or redeploy needed, only a rolling restart with updated env vars.

### Configuration

| Env Var | Default | Description |
|---------|---------|-------------|
| `FACILITY_FILTER_IDS` | — | Comma-separated FOSA facility IDs to allow (e.g. `"0030,0042,0099"`). Empty or unset = all events pass. Non-empty = only listed IDs are admitted. Prefixes like `Organization/` are stripped automatically before comparison. |

### Weekly Rollout Workflow (15 → 30 → … → 350 facilities)

1. Engineer raises a PR updating the deployment manifest, adding 15 IDs to `FACILITY_FILTER_IDS`:
   ```yaml
   # Kubernetes Deployment / docker-compose environment
   FACILITY_FILTER_IDS: >-
     0030,0042,0099,0101,0203
   ```
2. PR review — diff is human-readable; add inline comments mapping IDs to facility names.
3. CI applies the manifest (`kubectl apply` / `docker compose up -d`).
4. Rolling restart; readiness probe drains old pods after new pods are ready.
5. Filter is active. Verify in Grafana: new facilities produce 0 denials, previously filtered facilities now producing forwarded events.

### Skip Behaviour

Events that do not pass the filter return `200 OK` with `status: "skipped"` — they are **not** forwarded to the Collector:

```json
{
  "status": "skipped",
  "message": "Event skipped by facility filter: facilityId='9999' source='spice'"
}
```

OpenHIM records this as a **Completed** transaction (not Failed), keeping the transaction log clean. The `cce.emitter.events.filtered.total` Micrometer counter still increments for observability.

### Troubleshooting — Filter

| Symptom | Cause | Fix |
|---------|-------|-----|
| All events skipped after setting IDs | `FACILITY_FILTER_IDS` has IDs but none match the incoming requests | Verify the IDs match what source systems send; check adaptor startup log shows correct count |
| Facility ID `0234` rejected despite `0234` in YAML allowlist | YAML coerced unquoted `0234` to integer, stripping the leading zero | Quote all IDs in YAML: `ids: ["0234", "0030"]` — without quotes YAML parses `0234` as `234` |
| Facility `Organization/1302` rejected despite `1302` in list | Source sent FHIR reference prefix — adaptor strips it automatically | Confirm you're on the latest image; verify adaptor startup log shows `Facility filter: active — N facility id(s) configured` |
| Events with no `X-Facility-Id` and no FHIR location rejected | Stale behaviour from an old image | Resources with no resolvable facility ID always pass through; rebuild/redeploy with the latest image |
| Facility filter not active despite `FACILITY_FILTER_IDS` set | Env var not passed to container | Check `docker inspect <container>` → `Env` section |

## 8. Removing the Route

To stop forwarding traffic to the CCE Emitter Adaptor:

1. Navigate to **Channels** → eBUZIMA channel → **Routes**
2. Remove or disable the `CCE Emitter Adaptor` route
3. Save the channel

No cleanup is required on the adaptor side — it is stateless. The Collector handles deduplication, so no data impact from temporarily having the route active then removing it.

## 9. Beckn Channel (Proposal)

> **Status:** Built on branch `sygnal-actions`, on by default (`beckn.enabled=true`; set `OPENHIM_EMITTER_BECKN_ENABLED=false` to turn it off). Full design: [Beckn Integration](beckn-integration.md). §1–§8 are unchanged: the adaptor stays a secondary route on the eBUZIMA channel, and sources that post FHIR through OpenHIM keep going to the CCE Collector. This section is needed only with `beckn.enabled=true`.

The adaptor's Sygnal endpoint (`POST /sygnal/event/report`) takes the FHIR resource out of a Sygnal `sygnal/event/report` from an HMIS (forwarded by `onix-openhim`) and posts it into OpenHIM, so OpenHIM still authenticates and logs every event. OpenHIM routes it back to the adaptor's existing `/inbound`. The channel and client keep their `beckn` names, as the settings do.

### 9.1 Why a separate channel

The Sygnal endpoint reads the answer of `/inbound` to decide between `Ack` (202) and `Nack` (400 `BIZ_GENERIC_ERROR`, or 500 `NET_DOWNSTREAM_UNAVAILABLE` or `NET_INTERNAL_ERROR`). There is no callback, so this answer, which carries CCE's answer, is the whole result for the HMIS. A **secondary** route's answer never reaches the client: the client gets the primary route's answer. So the Sygnal traffic needs a channel where `/inbound` is the **primary** route. The eBUZIMA channel is not changed.

### 9.2 Client

| Field | Value | Notes |
|-------|-------|-------|
| **Client ID** | `cce-beckn-endpoint` | Same as `beckn.openhim-channel.username` |
| **Name** | `CCE Emitter Adaptor — Beckn endpoint` | |
| **Roles** | `cce-beckn` | Only this channel allows it |
| **Authentication** | Basic auth | Password in `OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_PASSWORD` |

**Do not** set this client id as the `client-id` of any source under `cce.emitter.sources`. `X-OpenHIM-ClientID` is matched before `X-Source-System`; if the Sygnal endpoint's client matched a source, every Sygnal event would be credited to that source. The adaptor refuses to start in that case (`BecknStartupValidator`).

### 9.3 Channel and Route

| Field | Value | Notes |
|-------|-------|-------|
| **Channel name** | `CCE Beckn feed` | |
| **URL pattern** | `^/cce-beckn$` | `beckn.openhim-channel.url` ends with `/cce-beckn` |
| **Methods** | `POST` | |
| **Allowed** | `cce-beckn` | The role of the client above |
| **Timeout** | Between `beckn.onix.timeout` (20 s) and `beckn.openhim-channel.timeout` (25 s), e.g. 22000–23000 ms | See [Beckn Integration §8.3](beckn-integration.md#83-every-answer-returns-in-the-same-call). Longer than the onix call, which includes CCE's `Ack` (up to about 17 s); shorter than the Sygnal endpoint's wait, so the endpoint gets OpenHIM's answer and not its own timeout |
| **Route name** | `CCE Emitter Adaptor (Beckn)` | |
| **Route host / port / path** | `cce-emitter-adaptor` / `8082` / `/inbound` | The existing endpoint |
| **Primary** | **Yes** | **Critical** — the answer must reach the Sygnal endpoint |

```json
{
  "name": "CCE Beckn feed",
  "urlPattern": "^/cce-beckn$",
  "methods": ["POST"],
  "allow": ["cce-beckn"],
  "authType": "private",
  "timeout": 22000,
  "routes": [
    {
      "name": "CCE Emitter Adaptor (Beckn)",
      "host": "cce-emitter-adaptor",
      "port": 8082,
      "path": "/inbound",
      "primary": true,
      "type": "http"
    }
  ]
}
```

### 9.4 Headers on this channel

| Header | Set by | Used for |
|--------|--------|----------|
| `X-Source-System` | Sygnal endpoint: the subscriber's `source-system` | Source resolution (the client id matches no source) |
| `X-Source-Event-Id` | Sygnal endpoint: `eventAttributes.sourceEventId`, else `events[0].id` | CloudEvent `sourceeventid` |
| `X-Sygnal-Event-Id` | Sygnal endpoint: `events[0].id` | CloudEvent `id` (when it is a full UUID), so CCE de-duplicates on the HMIS's own event id |
| `X-Sygnal-Occurred-At` | Sygnal endpoint: `events[0].occurredAt`, else `context.timestamp` | CloudEvent `time` (when it parses as ISO-8601; else the time of receipt) |
| `X-Correlation-Id` | Sygnal endpoint: `context.messageId` | Not used for `correlationid` here: `X-OpenHIM-TransactionID` wins |
| `X-OpenHIM-ClientID`, `X-OpenHIM-TransactionID` | OpenHIM Core | As in §5 |

Check in the first test that OpenHIM passes the five `X-` headers through to the route. Without `X-Sygnal-Event-Id` the CloudEvent `id` falls back to a UUID v5 of the source and `X-Source-Event-Id`, which CCE would not match with the HMIS's id.

### 9.5 Verify

| Check | Expected |
|-------|----------|
| Transaction log | One transaction per Sygnal event, client `cce-beckn-endpoint`, route `CCE Emitter Adaptor (Beckn)` with `202`, orchestration `Forward to CCE over Sygnal` (path `/sygnal/caller/sygnal/event/report`) |
| Wrong `source-system` | Transaction `Completed` with `200` and `"status":"ignored"`; the HMIS gets `Nack` (400) `BIZ_GENERIC_ERROR`, text `ignored: …` |
| CCE refuses the event | Transaction with `400` `COLLECTOR_CLIENT_ERROR`, message `CCE BIZ_GENERIC_ERROR: …`; the HMIS gets `Nack` (400) `BIZ_GENERIC_ERROR`, text `CCE: <CCE's text>` |
| CCE down | Transaction `Failed` with `502`; the HMIS gets `Nack` (500) `NET_DOWNSTREAM_UNAVAILABLE` |

### 9.6 Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Every `sygnal/event/report` is answered `Nack` `BIZ_GENERIC_ERROR`, text `ignored: …` | `source-system` not under `cce.emitter.sources`, or the Sygnal endpoint's client id matches a source's `client-id` | Fix the settings; restart |
| HMIS gets `Nack` (500) `NET_INTERNAL_ERROR` after a 401 | Wrong `OPENHIM_EMITTER_BECKN_OPENHIM_CLIENT_PASSWORD`, or client not allowed on the channel | Check the client and the channel's `allow` list |
| HMIS gets `Nack` (500) `NET_INTERNAL_ERROR`, text `CCE CTX_…` | CCE refused the HIE's own context: `beckn.self.id`, `beckn.self.network-id`, `beckn.cce.id` or `beckn.cce.uri` is wrong | Fix the HIE settings; restart |
| HMIS gets `Nack` (500) `NET_DOWNSTREAM_UNAVAILABLE` although the transaction succeeded | A timeout on an outer hop is shorter than the inner one: the channel timeout not between 20 s and 25 s, or an onix `responseHeaderTimeout` left at 5 s | Fix the timeout budget. The HMIS's resend carries the same event id, so CCE answers `Ack` for the duplicate and nothing is counted twice |
| Sygnal events go to the Collector, not to CCE over Sygnal | The source has `output: collector` | Set `cce.emitter.sources.<key>.output: beckn`, or remove `output` |
