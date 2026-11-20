# CCE Emitter Adaptor — Monitoring & Alerting Guide

Prometheus alert rules, Grafana dashboard recommendations, and log-based monitoring for the CCE Emitter Adaptor in production.

---

## 1. Metrics Inventory

All custom metrics are exposed at `GET /actuator/prometheus` and use the `cce_emitter_` prefix (Prometheus naming convention: dots → underscores).

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `cce_emitter_events_received_total` | Counter | `source`, `path` | Total inbound events received |
| `cce_emitter_events_forwarded_total` | Counter | `source` | Events successfully forwarded to Collector |
| `cce_emitter_events_duplicate_total` | Counter | — | Duplicate events (Collector returned 200) |
| `cce_emitter_events_rejected_total` | Counter | — | Events rejected by Collector (4xx) |
| `cce_emitter_collector_latency_seconds` | Timer | — | Collector forwarding round-trip latency (count, sum, max, percentiles) |
| `cce_emitter_collector_retries_total` | Counter | — | Retry attempts exhausted (all retries failed) |
| `cce_emitter_events_filtered_total` | Counter | `source`, `facility`, `reason` | Events skipped by facility filter (not forwarded; response is 200 OK). `reason`: `NOT_IN_ALLOWLIST`. Events with no facility ID pass through and are not counted. |
| `cce_emitter_events_redacted_total` | Counter | `resource_type` | Events from which at least one clinical field was removed before forwarding (once per event, not per field). |
| `cce_emitter_redaction_path_mismatch_total` | Counter | `resource_type`, `path` | A configured nested redaction path matched nothing (array found where an object was expected). Non-zero means that path is redacting nothing. |

### JVM & Spring Boot Metrics (auto-registered)

| Metric | Description |
|--------|-------------|
| `jvm_memory_used_bytes` | JVM heap/non-heap memory |
| `jvm_threads_live_threads` | Active thread count |
| `http_server_requests_seconds` | Inbound HTTP request latency (by status, method, URI) |
| `process_cpu_usage` | Process CPU utilization |
| `system_cpu_usage` | System CPU utilization |

## 2. Prometheus Scrape Configuration

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'cce-emitter-adaptor'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['<emitter-adaptor-host>:8082']
        labels:
          service: 'cce-emitter-adaptor'
          environment: 'production'
```

## 3. Alert Rules

### 3.1 Critical Alerts

```yaml
# prometheus-alerts.yml
groups:
  - name: cce-emitter-adaptor-critical
    rules:

      # Service is down — liveness probe failing
      - alert: CceEmitterDown
        expr: up{job="cce-emitter-adaptor"} == 0
        for: 1m
        labels:
          severity: critical
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter Adaptor is down"
          description: "Prometheus cannot scrape the emitter adaptor for >1 minute. Check container health."
          runbook: "Check `docker ps`, restart container, check logs for startup errors."

      # All retries exhausted — Collector unreachable or failing
      - alert: CceEmitterRetriesExhausted
        expr: rate(cce_emitter_collector_retries_total[5m]) > 0
        for: 2m
        labels:
          severity: critical
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter Adaptor retries exhausted"
          description: "Collector forwarding retries are being exhausted (rate > 0 for 2+ minutes). Events are being dropped."
          runbook: "Check CCE Collector health, network connectivity, and CCE_COLLECTOR_URL configuration."
```

### 3.2 Warning Alerts

```yaml
      # High rejection rate — Collector returning 4xx
      - alert: CceEmitterHighRejections
        expr: rate(cce_emitter_events_rejected_total[5m]) / rate(cce_emitter_events_received_total[5m]) > 0.05
        for: 5m
        labels:
          severity: warning
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter >5% event rejection rate"
          description: "More than 5% of events are being rejected by the Collector (4xx) for >5 minutes."
          runbook: "Check Collector logs for validation errors. Likely a payload format issue."

      # High duplicate rate
      - alert: CceEmitterHighDuplicates
        expr: rate(cce_emitter_events_duplicate_total[5m]) / rate(cce_emitter_events_received_total[5m]) > 0.20
        for: 10m
        labels:
          severity: warning
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter >20% duplicate event rate"
          description: "More than 20% of events are duplicates (Collector returned 200). May indicate a retry storm or repeated source submissions."
          runbook: "Check if source system is resending events. Check for network-level retries at the OpenHIM layer."

      # High latency — Collector response time degraded
      - alert: CceEmitterHighLatency
        expr: histogram_quantile(0.95, rate(cce_emitter_collector_latency_seconds_bucket[5m])) > 2.0
        for: 5m
        labels:
          severity: warning
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter Collector latency p95 > 2s"
          description: "95th percentile Collector forwarding latency exceeds 2 seconds for >5 minutes."
          runbook: "Check Collector health, Kafka broker connectivity, network latency between adaptor and Collector."

      # High facility filter denial rate (may indicate misconfigured allowlist)
      - alert: CceEmitterHighFilterDenialRate
        expr: rate(cce_emitter_events_filtered_total[5m]) / rate(cce_emitter_events_received_total[5m]) > 0.50
        for: 5m
        labels:
          severity: warning
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter facility filter denying >50% of events"
          description: "More than 50% of events are being skipped by the facility filter for >5 minutes. May indicate a misconfigured allowlist or missing facility IDs."
          runbook: "Check FACILITY_FILTER_IDS env var. Use topk Prometheus query to identify which facilities are being skipped. Verify source systems are sending X-Facility-Id header."

      # Clinical-data redaction has stopped taking effect.
      # This is a compliance control, not a performance one: if it silently stops firing while
      # events keep arriving, clinical findings are being persisted downstream again.
      - alert: CceEmitterRedactionNotApplied
        expr: increase(cce_emitter_events_received_total[30m]) > 0
              and increase(cce_emitter_events_redacted_total[30m]) == 0
        for: 30m
        labels:
          severity: critical
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter is forwarding events without redacting clinical data"
          description: "Events are being received but no clinical fields have been removed for 30 minutes. Clinical findings may now be persisting in inbound_event_log, compliance_event_log and ClickHouse."
          runbook: "Check REDACTION_ENABLED (must be true) and the startup log line 'Clinical-data redaction ACTIVE'. If redaction is on, the inbound payload shape may have changed — compare a live payload against the field list in docs/data-dictionary.md §3.6."

      # A configured redaction path is matching nothing.
      # Distinct from the alert above: redaction IS running, but one nested path was written
      # without the `[]` array marker, so it removes nothing while looking correctly configured.
      # Whoever added it almost certainly believes that content is being stripped.
      - alert: CceEmitterRedactionPathMatchesNothing
        expr: increase(cce_emitter_redaction_path_mismatch_total[15m]) > 0
        for: 5m
        labels:
          severity: critical
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter redaction path '{{ $labels.path }}' is redacting nothing ({{ $labels.resource_type }})"
          description: "A configured redaction path expects an object but the payload holds an array, so the path matches nothing and that content is being forwarded."
          runbook: "Find the WARN line 'Redaction path ... does not match' in the emitter log — it names the offending segment and the corrected form. Add '[]' to that segment in application.yml (e.g. reaction.manifestation -> reaction[].manifestation) and redeploy. See docs/data-dictionary.md §3.6 'Nested paths'."

      # No events received for extended period (during business hours)
      - alert: CceEmitterNoEventsReceived
        expr: increase(cce_emitter_events_received_total[30m]) == 0
        for: 30m
        labels:
          severity: warning
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter no events received for 30 minutes"
          description: "No inbound events received for 30+ minutes. May indicate an OpenHIM routing issue or source system outage."
          runbook: "Check OpenHIM channel configuration, eBUZIMA EMR status, and secondary route health in OpenHIM Console."
```

### 3.3 Informational Alerts

```yaml
      # High memory usage
      - alert: CceEmitterHighMemory
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
        for: 10m
        labels:
          severity: info
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE Emitter heap usage > 85%"
          description: "JVM heap usage exceeds 85% for >10 minutes. Consider increasing container memory limits."
```

## 4. Grafana Dashboard

### 4.1 Recommended Dashboard Panels

#### Row 1: Overview

| Panel | Type | Query | Description |
|-------|------|-------|-------------|
| **Events Received Rate** | Stat | `rate(cce_emitter_events_received_total[5m])` | Current inbound event rate (events/sec) |
| **Events Forwarded Rate** | Stat | `rate(cce_emitter_events_forwarded_total[5m])` | Current forwarding rate |
| **Success Rate** | Gauge | `rate(cce_emitter_events_forwarded_total[5m]) / rate(cce_emitter_events_received_total[5m]) * 100` | % of events successfully forwarded |
| **Service Status** | Stat | `up{job="cce-emitter-adaptor"}` | 1 = UP, 0 = DOWN |

#### Row 2: Event Throughput (Time Series)

| Panel | Type | Queries |
|-------|------|---------|
| **Event Throughput** | Time series (stacked) | `rate(cce_emitter_events_forwarded_total[5m])` — Forwarded |
| | | `rate(cce_emitter_events_duplicate_total[5m])` — Duplicates |
| | | `rate(cce_emitter_events_rejected_total[5m])` — Rejected |
| | | `rate(cce_emitter_collector_retries_total[5m])` — Retries Exhausted |

#### Row 3: Latency

| Panel | Type | Query | Description |
|-------|------|-------|-------------|
| **Collector Latency p50** | Time series | `histogram_quantile(0.50, rate(cce_emitter_collector_latency_seconds_bucket[5m]))` | Median latency |
| **Collector Latency p95** | Time series | `histogram_quantile(0.95, rate(cce_emitter_collector_latency_seconds_bucket[5m]))` | 95th percentile |
| **Collector Latency p99** | Time series | `histogram_quantile(0.99, rate(cce_emitter_collector_latency_seconds_bucket[5m]))` | 99th percentile |

#### Row 4: JVM Health

| Panel | Type | Query |
|-------|------|-------|
| **Heap Usage** | Time series + threshold | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}` |
| **CPU Usage** | Time series | `process_cpu_usage{job="cce-emitter-adaptor"}` |
| **Live Threads** | Time series | `jvm_threads_live_threads{job="cce-emitter-adaptor"}` |

#### Row 5: HTTP Server

| Panel | Type | Query |
|-------|------|-------|
| **HTTP Request Rate** | Time series by status | `rate(http_server_requests_seconds_count{uri="/inbound"}[5m])` |
| **HTTP Latency p95** | Time series | `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{uri="/inbound"}[5m]))` |
| **HTTP Error Rate** | Time series | `rate(http_server_requests_seconds_count{uri="/inbound",status=~"4..|5.."}[5m])` |

### 4.2 Dashboard Variables

| Variable | Label | Query |
|----------|-------|-------|
| `$source` | Source | `label_values(cce_emitter_events_received_total, source)` |
| `$instance` | Instance | `label_values(up{job="cce-emitter-adaptor"}, instance)` |

## 5. Log-Based Monitoring

### 5.1 Production Log Format

Production profile outputs structured JSON to stdout:

```json
{"timestamp":"2026-03-09T10:00:00.000Z","level":"INFO","logger":"o.o.c.e.service.InboundEventService","thread":"http-nio-8082-exec-1","correlationId":"corr-abc-123","source":"ebuzima","eventType":"Encounter","subject":"260225-0002-5501","message":"Event forwarded to Collector"}
```

### 5.2 Key Log Queries

For log aggregation tools (ELK, Loki, CloudWatch):

| Query Purpose | Filter |
|--------------|--------|
| All errors | `level: "ERROR"` |
| Collector failures | `message: *"retry"* OR message: *"exhausted"*` |
| Events for a patient | `subject: "260225-0002-5501"` |
| Events by source | `source: "ebuzima"` |
| Trace a request | `correlationId: "corr-abc-123"` |
| Rejected events | `message: *"rejected"* OR message: *"ClientException"*` |
| Registration failures | `logger: *"MediatorRegistrar"* AND level: "WARN"` |
| Heartbeat issues | `logger: *"HeartbeatScheduler"* AND level: "WARN"` |

### 5.3 MDC Fields for Filtering

Every log line during event processing includes these MDC fields:

| Field | Example | Use Case |
|-------|---------|----------|
| `correlationId` | `corr-abc-123` | Trace a single request across services |
| `source` | `ebuzima` | Filter by source system |
| `eventType` | `Encounter` | Filter by FHIR resource type |
| `subject` | `260225-0002-5501` | Filter by patient UPID |

## 6. Health Check Monitoring

### Endpoints

| Endpoint | Expected | When to Alert |
|----------|----------|---------------|
| `GET /actuator/health/liveness` | `{"status":"UP"}` | Alert if returns non-200 for >30s |
| `GET /actuator/health/readiness` | `{"status":"UP"}` | Alert if returns non-200 for >1m |

### Docker HEALTHCHECK

The Dockerfile includes a built-in health check:

```
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3
    CMD curl -f http://localhost:8082/actuator/health/liveness || exit 1
```

Docker marks the container as `unhealthy` after 3 consecutive failures. Container orchestrators (Kubernetes, ECS) should be configured to restart unhealthy containers.

## 7. Recommended Thresholds Summary

| Metric | Warning | Critical |
|--------|---------|----------|
| Service down | — | >1 minute |
| Retries exhausted rate | — | Any sustained rate > 0 for 2 minutes |
| Rejection rate | >5% for 5 minutes | >20% for 5 minutes |
| Duplicate rate | >20% for 10 minutes | >50% for 10 minutes |
| Collector latency p95 | >2 seconds for 5 minutes | >5 seconds for 5 minutes |
| No events received | >30 minutes (business hours) | >60 minutes (business hours) |
| Heap usage | >85% for 10 minutes | >95% for 5 minutes |

## 8. Beckn Metrics and Alerts (Proposal)

> **Status:** Built on branch `sygnal-actions`, on by default (`beckn.enabled=true`; set `OPENHIM_EMITTER_BECKN_ENABLED=false` to turn it off). Full design: [Beckn Integration](beckn-integration.md). The metrics and alerts in §1–§7 are unchanged. With `beckn.enabled=false` the metrics below are not registered. The feature is called Sygnal; the metric names keep `beckn`.

On the Sygnal output, the existing metrics keep their meaning: CCE's `ACK` counts as `cce_emitter_events_forwarded_total`, a refusal (4xx `NACK`: `BIZ_GENERIC_ERROR`, `CTX_*`, `SCH_*`) as `cce_emitter_events_rejected_total` (`source="beckn"`, `reason="client_error"`), and an answer that may pass (`NET_DOWNSTREAM_UNAVAILABLE` or `NET_INTERNAL_ERROR`, 408, 429, 5xx, timeout) as `cce_emitter_collector_retries_total`. So the alerts in §3 also cover the Sygnal output. `cce_emitter_events_duplicate_total` is not counted for Sygnal: CCE answers `202 ACK` for a new and for a duplicate event alike, and there is no callback. Note that `cce_emitter_events_received_total` tags all Sygnal traffic with the Sygnal endpoint's OpenHIM client id.

### 8.1 New Metrics

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `cce_emitter_beckn_received_total` | Counter | `action` (`sygnal/event/report`) | Sygnal messages received on `/sygnal/event/report` |
| `cce_emitter_beckn_answered_total` | Counter | `action`, `result` (`ack`; `nack` for a 4xx `Nack`; `error` for a 5xx `Nack`), `code` (`CTX_MISSING_FIELD`, `CTX_INVALID_FIELD`, `CTX_ACTION_MISMATCH`, `SCH_INVALID_JSON`, `SCH_REQUIRED_FIELD_MISSING`, `SCH_SCHEMA_VALIDATION_FAILED`, `SCH_TYPE_NOT_SUPPORTED`, `BIZ_GENERIC_ERROR`, `NET_DOWNSTREAM_UNAVAILABLE`, `NET_INTERNAL_ERROR`; `none` on `ack`) | Sygnal messages answered |
| `cce_emitter_beckn_channel_ignored_total` | Counter | `subscriber`, `status` (`ignored`, `skipped`) | OpenHIM channel answered 2xx without forwarding. Each one becomes a `Nack` 400 `BIZ_GENERIC_ERROR` to the HMIS |
| `cce_emitter_beckn_channel_latency_seconds` | Timer | — | Sygnal endpoint → OpenHIM channel → answer. Includes the whole inner route, up to CCE's answer |
| `cce_emitter_beckn_cce_report_sent_total` | Counter | `source`, `result` (`ack`, `nack`, `error`), `code` (CCE's `NACK` code; `none` when there is none) | `sygnal/event/report` sent to CCE. Was `cce_emitter_beckn_cce_update_sent_total` |
| `cce_emitter_beckn_onix_latency_seconds` | Timer | `action` (`sygnal/event/report`) | Round trip to `onix-openhim`'s caller |

Removed with the switch to Sygnal: `cce_emitter_beckn_onupdate_sent_total`, `cce_emitter_beckn_cce_onupdate_received_total` (there are no callbacks), the `endpoint` tag on `received` / `answered`, and the `container` tag on `onix_latency`. Dashboards and alerts that used them must be changed as below.

### 8.2 Alert Rules

```yaml
  - name: cce-emitter-adaptor-beckn
    rules:

      # Events from an HMIS that reached no output — "ignored" or "skipped" from the channel
      - alert: CceEmitterBecknChannelIgnored
        expr: increase(cce_emitter_beckn_channel_ignored_total[10m]) > 0
        for: 0m
        labels:
          severity: warning
          service: cce-emitter-adaptor
        annotations:
          summary: "Sygnal events answered 'ignored' or 'skipped' by the OpenHIM channel"
          description: "The HMIS got a Nack 400 BIZ_GENERIC_ERROR. With status=ignored the subscriber's source-system matches no source, or the Sygnal endpoint's client id matches a source's client-id."
          runbook: "Check beckn.subscribers[].source-system against cce.emitter.sources, and the facility filter for status=skipped."

      # CCE unreachable over Sygnal
      - alert: CceEmitterBecknCceErrors
        expr: rate(cce_emitter_beckn_cce_report_sent_total{result="error"}[5m]) > 0
        for: 2m
        labels:
          severity: critical
          service: cce-emitter-adaptor
        annotations:
          summary: "sygnal/event/report to CCE failing (5xx, 408, 429 or timeout)"
          description: "onix-openhim, onix-cce, cce-beckn-service or the CCE collector is down or slow (CCE code NET_DOWNSTREAM_UNAVAILABLE or NET_INTERNAL_ERROR). HMIS systems get Nack 500 NET_DOWNSTREAM_UNAVAILABLE and should resend with a growing wait."
          runbook: "Check onix-openhim logs, then the CCE side. Check the timeout budget: CCE's Ack can take 17 s, and the onix responseHeaderTimeout default of 5 s is too short."

      # CCE refuses the HIE's own context: HIE settings wrong
      - alert: CceEmitterBecknHieContextRefused
        expr: increase(cce_emitter_beckn_cce_report_sent_total{result="nack",code=~"CTX_.*"}[10m]) > 0
        for: 0m
        labels:
          severity: critical
          service: cce-emitter-adaptor
        annotations:
          summary: "CCE refuses the HIE's sygnal/event/report context"
          description: "HMIS systems get Nack 500 NET_INTERNAL_ERROR and will resend, but every resend fails the same way."
          runbook: "Check beckn.self.id, beckn.self.network-id, beckn.cce.id and beckn.cce.uri against CCE's settings and the registry."

      # Many Nacks to HMIS systems
      - alert: CceEmitterBecknHighNackRate
        expr: sum(rate(cce_emitter_beckn_answered_total{result=~"nack|error"}[5m])) / sum(rate(cce_emitter_beckn_received_total[5m])) > 0.05
        for: 5m
        labels:
          severity: warning
          service: cce-emitter-adaptor
        annotations:
          summary: "More than 5% of Sygnal event reports answered Nack"
          description: "Group by code: CTX_* / SCH_* (the HMIS's message broke a rule: unknown subscriber, wrong receiverId, not exactly one event, id not a UUID), BIZ_GENERIC_ERROR (ignored, skipped, a pipeline 4xx, or CCE refused the event), NET_DOWNSTREAM_UNAVAILABLE (OpenHIM or CCE down or slow), NET_INTERNAL_ERROR (HIE settings or OpenHIM client wrong)."

      # onix close to its timeout
      - alert: CceEmitterBecknOnixSlow
        expr: histogram_quantile(0.95, rate(cce_emitter_beckn_onix_latency_seconds_bucket{action="sygnal/event/report"}[5m])) > 0.8 * 20
        for: 5m
        labels:
          severity: warning
          service: cce-emitter-adaptor
        annotations:
          summary: "onix-openhim p95 close to the Sygnal timeout (beckn.onix.timeout, 20 s)"
          description: "Outer hops may give up first and the HMIS will resend."
```

### 8.3 Dashboard and Logs

| Panel | Query |
|-------|-------|
| Sygnal answers by result | `sum by (result, code) (rate(cce_emitter_beckn_answered_total[5m]))` |
| Ignored / skipped by subscriber | `sum by (subscriber, status) (increase(cce_emitter_beckn_channel_ignored_total[1h]))` |
| `sygnal/event/report` to CCE by result | `sum by (result, code) (rate(cce_emitter_beckn_cce_report_sent_total[5m]))` |
| onix latency p95 | `histogram_quantile(0.95, rate(cce_emitter_beckn_onix_latency_seconds_bucket[5m]))` |
| HTTP on the Sygnal endpoint | `rate(http_server_requests_seconds_count{uri="/sygnal/event/report"}[5m])` |

| Log query | Filter |
|-----------|--------|
| Trace one Sygnal message | `becknMessageId: "<context.messageId>"` (`becknAction` is `sygnal/event/report`). The outbound `sygnal/event/report` to CCE has its own `messageId` but the same event id. To ask the CCE team about a refusal, give them the event id, that `messageId` and the `transactionId` |
| All messages of one HMIS | `subscriber: "bap.openmrs.nextsteps.mdtlabs.org"` (the `senderId`) |
| Nacks | `message: *"Sygnal sygnal/event/report"* AND message: *"Nack"*` |

| Metric | Warning | Critical |
|--------|---------|----------|
| Channel "ignored" / "skipped" | Any in 10 minutes | — |
| `sygnal/event/report` to CCE errors | — | Any sustained rate for 2 minutes |
| CCE refuses the HIE's context (`CTX_*`) | — | Any in 10 minutes |
| Nack rate on `/sygnal/event/report` | >5% for 5 minutes | >20% for 5 minutes |
| onix-openhim latency p95 | >80% of `beckn.onix.timeout` (20 s). CCE's `Ack` alone can take 17 s | — |
