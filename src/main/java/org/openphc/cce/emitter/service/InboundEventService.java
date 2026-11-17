package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.openphc.cce.emitter.adaptor.SourceAdaptorService;
import org.openphc.cce.emitter.config.EmitterProperties.Output;
import org.openphc.cce.emitter.exception.FacilityFilterRejectedException;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.CollectorResponse;
import org.openphc.cce.emitter.model.InboundRequest;
import org.openphc.cce.emitter.model.ProcessedEventsResponse;
import org.openphc.cce.emitter.model.TransformationResult;
import org.openphc.cce.emitter.openhim.OpenHimResponseWrapper;
import org.openphc.cce.emitter.openhim.model.OpenHimResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full inbound event processing pipeline:
 * source resolution, FHIR→CloudEvent transformation, forwarding to the source's
 * output (CCE Collector or CCE over Beckn, via {@link EventForwardingRouter}),
 * and OpenHIM response envelope construction.
 *
 * <p>Keeps the controller layer thin — the controller handles only HTTP
 * concerns (request binding, content-type, serialization) and delegates
 * all business logic here.
 */
@Service
public class InboundEventService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventService.class);

    private final SourceAdaptorService sourceAdaptorService;
    private final EventForwardingRouter forwardingRouter;
    private final OpenHimResponseWrapper responseWrapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public InboundEventService(
            SourceAdaptorService sourceAdaptorService,
            EventForwardingRouter forwardingRouter,
            OpenHimResponseWrapper responseWrapper,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.sourceAdaptorService = sourceAdaptorService;
        this.forwardingRouter = forwardingRouter;
        this.responseWrapper = responseWrapper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Processes an inbound request through the full pipeline.
     *
     * @param inboundRequest the normalized inbound request
     * @return the OpenHIM response envelope (HTTP status embedded in {@code response.status})
     */
    public OpenHimResponse process(InboundRequest inboundRequest) {
        String source = inboundRequest.getHeader("x-openhim-clientid").orElse("unknown");
        String path = inboundRequest.getPath();

        Counter.builder("cce.emitter.events.received")
                .tag("source", source).tag("path", path)
                .register(meterRegistry).increment();

        // 1. Source resolution + FHIR → CloudEvent transformation
        List<CloudEventDto> events;
        try {
            events = sourceAdaptorService.adapt(inboundRequest);
        } catch (FacilityFilterRejectedException ex) {
            // Facility not in allowlist — skip silently with 200 so OpenHIM marks the transaction Completed
            log.info("Event skipped by facility filter: facilityId='{}' source='{}' reason={}",
                    ex.getFacilityId(), ex.getSourceKey(), ex.getReason());
            JsonNode responseBody = objectMapper.createObjectNode()
                    .put("status", "skipped")
                    .put("message", "Event skipped by facility filter: facilityId='" + ex.getFacilityId() + "' source='" + ex.getSourceKey() + "'");
            return responseWrapper.wrap(responseBody, HttpStatus.OK, List.of());
        }

        // 2. No events produced → silently ignore (200 OK)
        if (events.isEmpty()) {
            log.debug("No events produced — returning 200 OK (silent ignore)");
            JsonNode responseBody = objectMapper.createObjectNode()
                    .put("status", "ignored")
                    .put("message", "No matching source or non-processable payload");
            return responseWrapper.wrap(responseBody, HttpStatus.OK, List.of());
        }

        // 3. Forward each CloudEvent to its source's output and collect results
        List<TransformationResult> results = new ArrayList<>();
        List<OpenHimResponse.Orchestration> orchestrations = new ArrayList<>();

        for (CloudEventDto event : events) {
            populateMdc(event);
            try {
                String requestTimestamp = nowUtc();
                Output output = forwardingRouter.outputFor(event);
                CollectorResponse collectorResponse = forwardingRouter.forward(event);
                TransformationResult result = TransformationResult.from(event, collectorResponse);
                results.add(result);
                orchestrations.add(buildOrchestration(event, collectorResponse, requestTimestamp, output));

                trackForwardingMetrics(event, result.collectorStatus());
            } finally {
                clearMdc();
            }
        }

        // 4. Build success response
        JsonNode responseBody = objectMapper.valueToTree(ProcessedEventsResponse.from(results));
        OpenHimResponse envelope = responseWrapper.wrap(
                responseBody, HttpStatus.ACCEPTED, orchestrations);

        log.info("Inbound request processed: {} event(s) forwarded", results.size());

        return envelope;
    }

    private void trackForwardingMetrics(CloudEventDto event, String collectorStatus) {
        String eventSource = event.getSource() != null ? event.getSource() : "unknown";
        if ("duplicate".equals(collectorStatus)) {
            Counter.builder("cce.emitter.events.duplicate")
                    .register(meterRegistry).increment();
        } else {
            Counter.builder("cce.emitter.events.forwarded")
                    .tag("source", eventSource)
                    .register(meterRegistry).increment();
        }
    }

    private void populateMdc(CloudEventDto event) {
        if (event.getCorrelationid() != null) MDC.put("correlationId", event.getCorrelationid());
        if (event.getSource() != null) MDC.put("source", event.getSource());
        if (event.getType() != null) MDC.put("eventType", event.getType());
        if (event.getSubject() != null) MDC.put("subject", event.getSubject());
    }

    private void clearMdc() {
        MDC.remove("correlationId");
        MDC.remove("source");
        MDC.remove("eventType");
        MDC.remove("subject");
    }

    // ==================== Helper methods ====================

    private OpenHimResponse.Orchestration buildOrchestration(
            CloudEventDto event, CollectorResponse collectorResponse, String requestTimestamp, Output output) {

        String responseTimestamp = nowUtc();
        String collectorStatus = collectorResponse.data() != null
                ? collectorResponse.data().status() : "accepted";

        return OpenHimResponse.Orchestration.builder()
                .name(forwardingRouter.orchestrationName(output))
                .request(OpenHimResponse.Orchestration.Request.builder()
                        .method("POST")
                        .path(forwardingRouter.orchestrationPath(output))
                        .body(toJson(event))
                        .timestamp(requestTimestamp)
                        .headers(Map.of("Content-Type", "application/json"))
                        .build())
                .response(OpenHimResponse.Orchestration.OrchestrationResponse.builder()
                        .status(output == Output.BECKN || collectorStatus.equals("duplicate") ? 200 : 202)
                        .body(toJson(collectorResponse))
                        .timestamp(responseTimestamp)
                        .build())
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            log.warn("JSON serialization failed: {}", ex.getMessage());
            return "{}";
        }
    }

    private String nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

}
