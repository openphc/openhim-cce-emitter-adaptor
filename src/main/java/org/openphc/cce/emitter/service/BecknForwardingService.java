package org.openphc.cce.emitter.service;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.openphc.cce.emitter.cloudevents.CceClinicalEventMapper;
import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.exception.CollectorClientException;
import org.openphc.cce.emitter.exception.CollectorForwardingException;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.CollectorResponse;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The Beckn output (spec §5): wraps a CloudEvent in the {@code CceClinicalEvent} box, sends it to
 * CCE as an {@code update} through {@code onix-bap}, and maps CCE's answer to what the collector
 * path gives {@link InboundEventService} (spec §5.4), so {@code /inbound}'s answer, the OpenHIM
 * transaction and the existing metrics behave the same:
 *
 * <ul>
 *   <li>{@code ACK} → a {@link CollectorResponse} with status {@code accepted}</li>
 *   <li>{@code NACK} 400 (any code) → {@link CollectorClientException} (400), message
 *       {@code CCE <code>: <text>}. Not retried</li>
 *   <li>5xx, 408, 429, timeout, no answer → {@link CollectorForwardingException} (502)</li>
 * </ul>
 *
 * Tries {@code beckn.cce.retry.max-attempts} times (default 1, no retry, spec §8.4), each with a
 * new {@code messageId}.
 */
@Service
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknForwardingService {

    private static final Logger log = LoggerFactory.getLogger(BecknForwardingService.class);

    private final BecknOnixClient onix;
    private final BecknMessageFactory messages;
    private final CceClinicalEventMapper boxMapper;
    private final CceAnswerMapper answerMapper;
    private final BecknProperties props;
    private final MeterRegistry meterRegistry;
    private final Counter retriesCounter;
    private final Counter rejectedCounter;

    public BecknForwardingService(BecknOnixClient onix, BecknMessageFactory messages,
                                  CceClinicalEventMapper boxMapper, BecknProperties props,
                                  ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.onix = onix;
        this.messages = messages;
        this.boxMapper = boxMapper;
        this.answerMapper = new CceAnswerMapper(objectMapper);
        this.props = props;
        this.meterRegistry = meterRegistry;
        // The same metrics the collector path uses for the same outcomes (spec §5.4)
        this.retriesCounter = Counter.builder("cce.emitter.collector.retries")
                .description("Collector retry attempts")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("cce.emitter.events.rejected")
                .description("Events rejected by Collector")
                .tag("source", "beckn")
                .tag("reason", "client_error")
                .register(meterRegistry);
    }

    /**
     * Sends the CloudEvent to CCE as an {@code update}.
     *
     * @return a collector-shaped {@code accepted} response on CCE's {@code ACK}
     * @throws CollectorClientException     when CCE refused the {@code update} (400)
     * @throws CollectorForwardingException when CCE gave no answer, or one that may pass
     */
    public CollectorResponse forward(CloudEventDto event) {
        int maxAttempts = Math.max(1, props.cce().retry().maxAttempts());
        CceAnswerMapper.Failed lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String messageId = BecknMessageFactory.newMessageId();
            ObjectNode update = messages.update(messageId, event.getId(), boxMapper.toBox(event));
            long started = System.nanoTime();
            HttpAnswer answer = onix.postToBap("update", update);
            long tookMs = (System.nanoTime() - started) / 1_000_000;
            CceAnswerMapper.Outcome outcome = answerMapper.map(answer);

            switch (outcome) {
                case CceAnswerMapper.Accepted a -> {
                    log.info("CCE ACK for CloudEvent id={} messageId={} in {} ms", event.getId(), messageId, tookMs);
                    count(event, "ack", "none");
                    return new CollectorResponse(new CollectorResponse.DataPayload(
                            event.getId(), "accepted", messageId, Instant.now().toString()), null);
                }
                case CceAnswerMapper.Refused r -> {
                    log.warn("CCE NACK for CloudEvent id={} messageId={} in {} ms: HTTP {} {}: {}",
                            event.getId(), messageId, tookMs, r.status(), r.code(), r.text());
                    count(event, "nack", r.code());
                    rejectedCounter.increment();
                    throw new CollectorClientException("CCE " + r.code() + ": " + r.text(), 400);
                }
                case CceAnswerMapper.Failed f -> {
                    log.warn("CCE unavailable for CloudEvent id={} messageId={} (try {}/{}) in {} ms: {}",
                            event.getId(), messageId, attempt, maxAttempts, tookMs, f.reason());
                    count(event, "error", f.code() != null ? f.code() : "none");
                    lastFailure = f;
                }
            }
        }
        retriesCounter.increment();
        throw new CollectorForwardingException("CCE over Beckn unavailable after " + maxAttempts + " attempt(s): "
                + (lastFailure != null ? lastFailure.reason() : "no answer"));
    }

    private void count(CloudEventDto event, String result, String code) {
        Counter.builder("cce.emitter.beckn.cce.update.sent")
                .description("update sent to CCE")
                .tag("source", event.getSource() != null ? event.getSource() : "unknown")
                .tag("result", result)
                .tag("code", code)
                .register(meterRegistry).increment();
    }
}
