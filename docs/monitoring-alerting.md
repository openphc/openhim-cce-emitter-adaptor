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
