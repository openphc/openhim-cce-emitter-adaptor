package org.openphc.cce.emitter.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Sends {@code on_update} with {@code ACCEPTED} to an HMIS through {@code onix-bpp}
 * ({@code /bpp/caller/on_update}) after its {@code update} was answered {@code Ack} (spec §3.4).
 *
 * <p>Runs on a small bounded pool, after the answer has been decided. Sent once. A failure (or a
 * full queue) is logged and counted as {@code cce.emitter.beckn.onupdate.sent{result="failed"}};
 * the {@code Ack} stands.
 */
@Service
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknCallbackService {

    private static final Logger log = LoggerFactory.getLogger(BecknCallbackService.class);

    private final BecknOnixClient onix;
    private final BecknMessageFactory messages;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor;

    public BecknCallbackService(BecknOnixClient onix, BecknMessageFactory messages, MeterRegistry meterRegistry) {
        this.onix = onix;
        this.messages = messages;
        this.meterRegistry = meterRegistry;
        AtomicInteger n = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "beckn-on-update-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /** Schedules the {@code on_update}; returns at once. */
    public void sendOnUpdateAccepted(BecknProperties.Subscriber subscriber, JsonNode requestContext,
                                     String contractId, String performanceId) {
        String messageId = requestContext.path("messageId").asText("");
        String transactionId = requestContext.path("transactionId").asText("");
        JsonNode message = messages.onUpdateAccepted(subscriber, requestContext, contractId, performanceId);
        try {
            executor.execute(() -> send(subscriber, message, messageId, transactionId));
        } catch (RejectedExecutionException e) {
            log.warn("on_update to {} for messageId={} dropped: the queue is full", subscriber.subscriberId(), messageId);
            count(subscriber, "failed");
        }
    }

    private void send(BecknProperties.Subscriber subscriber, JsonNode message, String messageId, String transactionId) {
        MDC.put("becknMessageId", messageId);
        MDC.put("becknTransactionId", transactionId);
        MDC.put("becknAction", "on_update");
        MDC.put("subscriber", subscriber.subscriberId());
        try {
            HttpAnswer answer = onix.postToBpp("on_update", message);
            boolean nack = answer.body() != null && answer.body().contains("\"NACK\"");
            if (!answer.unreachable() && answer.status() >= 200 && answer.status() < 300 && !nack) {
                log.info("on_update ACCEPTED sent to {} for messageId={}", subscriber.subscriberId(), messageId);
                count(subscriber, "ok");
            } else {
                log.warn("on_update to {} for messageId={} failed: {}", subscriber.subscriberId(), messageId,
                        answer.unreachable() ? answer.detail() : "HTTP " + answer.status() + " " + answer.body());
                count(subscriber, "failed");
            }
        } catch (RuntimeException e) {
            log.warn("on_update to {} for messageId={} failed: {}", subscriber.subscriberId(), messageId, e.toString());
            count(subscriber, "failed");
        } finally {
            MDC.remove("becknMessageId");
            MDC.remove("becknTransactionId");
            MDC.remove("becknAction");
            MDC.remove("subscriber");
        }
    }

    private void count(BecknProperties.Subscriber subscriber, String result) {
        Counter.builder("cce.emitter.beckn.onupdate.sent")
                .description("on_update sent to an HMIS")
                .tag("subscriber", subscriber.subscriberId())
                .tag("result", result)
                .register(meterRegistry).increment();
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
