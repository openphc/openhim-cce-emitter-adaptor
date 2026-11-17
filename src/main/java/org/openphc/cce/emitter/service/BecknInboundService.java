package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.openphc.cce.emitter.adaptor.BecknInboundUnwrapper;
import org.openphc.cce.emitter.adaptor.BecknInboundUnwrapper.UnwrappedEvent;
import org.openphc.cce.emitter.exception.BecknNackException;
import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.model.beckn.BecknActions;
import org.openphc.cce.emitter.model.beckn.BecknAnswer;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Handles one request from {@code onix-bpp} on {@code /beckn/bpp-webhook/{action}} (spec §3):
 * unwrap → OpenHIM channel → answer mapping → {@code on_update} after an {@code Ack}.
 * Every answer keeps the request's {@code messageId}.
 */
@Service
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknInboundService {

    private static final Logger log = LoggerFactory.getLogger(BecknInboundService.class);

    private final BecknInboundUnwrapper unwrapper;
    private final OpenHimChannelClient channel;
    private final ChannelAnswerMapper answerMapper;
    private final BecknCallbackService callbacks;
    private final MeterRegistry meterRegistry;

    public BecknInboundService(BecknInboundUnwrapper unwrapper, OpenHimChannelClient channel,
                               BecknCallbackService callbacks, ObjectMapper objectMapper,
                               MeterRegistry meterRegistry) {
        this.unwrapper = unwrapper;
        this.channel = channel;
        this.answerMapper = new ChannelAnswerMapper(objectMapper);
        this.callbacks = callbacks;
        this.meterRegistry = meterRegistry;
    }

    public ResponseEntity<BecknAck> handle(String action, JsonNode body) {
        JsonNode context = body.path("context");
        String messageId = context.path("messageId").asText("");
        String transactionId = context.path("transactionId").asText("");
        String bapId = context.path("bapId").asText("");
        MDC.put("becknMessageId", messageId);
        MDC.put("becknTransactionId", transactionId);
        MDC.put("becknAction", action);
        MDC.put("subscriber", bapId);
        Counter.builder("cce.emitter.beckn.received").description("Beckn messages received")
                .tag("endpoint", "bpp").tag("action", BecknActions.tag(action)).register(meterRegistry).increment();
        try {
            BecknAnswer answer = answer(action, body, context);
            log.info("Beckn {} messageId={} answered {}{} (HTTP {})", action, messageId,
                    answer.isAck() ? "Ack" : "Nack " + answer.code(),
                    answer.isAck() ? "" : ": " + answer.text(), answer.status().value());
            countAnswer(action, answer.isAck() ? "ack" : answer.status().is5xxServerError() ? "error" : "nack",
                    answer.isAck() ? "none" : answer.code());
            return ResponseEntity.status(answer.status()).body(answer.body(messageId));
        } catch (RuntimeException e) {
            // Not expected. Handled here so the answer keeps the request's messageId.
            log.error("Beckn {} messageId={} could not be handled", action, messageId, e);
            countAnswer(action, "error", BecknNackException.UNAVAILABLE);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(BecknAck.nack(messageId,
                    BecknNackException.UNAVAILABLE, "The request could not be handled. Send it again later."));
        } finally {
            MDC.remove("becknMessageId");
            MDC.remove("becknTransactionId");
            MDC.remove("becknAction");
            MDC.remove("subscriber");
        }
    }

    private BecknAnswer answer(String action, JsonNode body, JsonNode context) {
        try {
            String claimed = context.path("action").asText("");
            if (!claimed.isBlank() && !claimed.equals(action)) {
                throw BecknNackException.badRequest(
                        "context.action '" + claimed + "' does not match the request path '" + action + "'");
            }
            if (!"update".equals(action)) {
                throw BecknNackException.notSupported("The action '" + action + "' is not supported");
            }
            return update(body);
        } catch (BecknNackException e) {
            return BecknAnswer.nack(e.getStatus(), e.getCode(), e.getMessage());
        }
    }

    private BecknAnswer update(JsonNode body) {
        UnwrappedEvent event = unwrapper.unwrap(body);
        log.info("Beckn update received: bapId={} transactionId={} messageId={} performanceId={} eventType={}",
                event.subscriber().subscriberId(), event.transactionId(), event.messageId(),
                event.performanceId(), event.eventType());

        HttpAnswer channelAnswer = channel.post(event.resource(), event.headers());
        BecknAnswer answer = answerMapper.map(channelAnswer);
        log.info("OpenHIM channel answered messageId={}: {}", event.messageId(),
                channelAnswer.unreachable() ? "no answer (" + channelAnswer.detail() + ")" : "HTTP " + channelAnswer.status());

        if (!answer.isAck() && BecknNackException.EVENT_REJECTED.equals(answer.code())
                && answer.text() != null && (answer.text().startsWith("ignored:") || answer.text().startsWith("skipped:"))) {
            Counter.builder("cce.emitter.beckn.channel.ignored")
                    .description("Channel answered 200 without forwarding")
                    .tag("subscriber", event.subscriber().subscriberId())
                    .tag("status", answer.text().startsWith("ignored:") ? "ignored" : "skipped")
                    .register(meterRegistry).increment();
        }
        if (answer.isAck()) {
            callbacks.sendOnUpdateAccepted(event.subscriber(), event.context(), event.performanceId());
        }
        return answer;
    }

    private void countAnswer(String action, String result, String code) {
        Counter.builder("cce.emitter.beckn.answered").description("Beckn messages answered")
                .tag("endpoint", "bpp").tag("action", BecknActions.tag(action)).tag("result", result).tag("code", code)
                .register(meterRegistry).increment();
    }
}
