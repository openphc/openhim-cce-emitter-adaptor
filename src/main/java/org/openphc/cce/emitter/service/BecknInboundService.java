package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.openphc.cce.emitter.adaptor.BecknInboundUnwrapper;
import org.openphc.cce.emitter.adaptor.BecknInboundUnwrapper.UnwrappedEvent;
import org.openphc.cce.emitter.exception.BecknNackException;
import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.model.beckn.BecknAnswer;
import org.openphc.cce.emitter.model.beckn.ErrorCodes;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Handles one {@code sygnal/event/report} from an HMIS, as {@code onix-openhim} forwards it to
 * {@code /sygnal/event/report} (spec §3): unwrap → OpenHIM channel → {@code /inbound} → CCE →
 * answer mapping. Sygnal sends no callback for a report, so the answer waits until the event has
 * been handed on and carries the outcome: {@code 202 Ack} when CCE took it (or already had it),
 * {@code 400 Nack} when the event is wrong, {@code 5xx Nack} when OpenHIM or CCE is unavailable so
 * the HMIS sends it again. Every answer keeps the request's {@code messageId}.
 */
@Service
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknInboundService {

    private static final Logger log = LoggerFactory.getLogger(BecknInboundService.class);
    private static final String ACTION = BecknMessageFactory.EVENT_REPORT;

    private final BecknInboundUnwrapper unwrapper;
    private final OpenHimChannelClient channel;
    private final ChannelAnswerMapper answerMapper;
    private final MeterRegistry meterRegistry;

    public BecknInboundService(BecknInboundUnwrapper unwrapper, OpenHimChannelClient channel,
                               ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.unwrapper = unwrapper;
        this.channel = channel;
        this.answerMapper = new ChannelAnswerMapper(objectMapper);
        this.meterRegistry = meterRegistry;
    }

    public ResponseEntity<BecknAck> handle(JsonNode body) {
        JsonNode context = body.path("context");
        String messageId = context.path("messageId").asText("");
        String transactionId = context.path("transactionId").asText("");
        String senderId = context.path("senderId").asText("");
        MDC.put("becknMessageId", messageId);
        MDC.put("becknTransactionId", transactionId);
        MDC.put("becknAction", ACTION);
        MDC.put("subscriber", senderId);
        Counter.builder("cce.emitter.beckn.received").description("Sygnal messages received")
                .tag("action", ACTION).register(meterRegistry).increment();
        try {
            BecknAnswer answer = answer(body, context);
            log.info("Sygnal {} messageId={} answered {}{} (HTTP {})", ACTION, messageId,
                    answer.isAck() ? "Ack" : "Nack " + answer.code(),
                    answer.isAck() ? "" : ": " + answer.text(), answer.status().value());
            countAnswer(answer.isAck() ? "ack" : answer.status().is5xxServerError() ? "error" : "nack",
                    answer.isAck() ? "none" : answer.code());
            return ResponseEntity.status(answer.status()).body(answer.body(messageId));
        } catch (RuntimeException e) {
            // Not expected. Handled here so the answer keeps the request's messageId.
            log.error("Sygnal {} messageId={} could not be handled", ACTION, messageId, e);
            countAnswer("error", ErrorCodes.NET_INTERNAL_ERROR);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(BecknAck.nack(messageId,
                    ErrorCodes.NET_INTERNAL_ERROR, "The request could not be handled. Send it again later."));
        } finally {
            MDC.remove("becknMessageId");
            MDC.remove("becknTransactionId");
            MDC.remove("becknAction");
            MDC.remove("subscriber");
        }
    }

    private BecknAnswer answer(JsonNode body, JsonNode context) {
        try {
            String claimed = context.path("action").asText("");
            if (!ACTION.equals(claimed)) {
                throw BecknNackException.badRequest(ErrorCodes.CTX_ACTION_MISMATCH,
                        "context.action is '" + claimed + "', but this endpoint takes '" + ACTION + "'");
            }
            return report(body);
        } catch (BecknNackException e) {
            return BecknAnswer.nack(e.getStatus(), e.getCode(), e.getMessage());
        }
    }

    private BecknAnswer report(JsonNode body) {
        UnwrappedEvent event = unwrapper.unwrap(body);
        log.info("Sygnal report received: senderId={} transactionId={} messageId={} eventId={} resourceType={}",
                event.subscriber().subscriberId(), event.transactionId(), event.messageId(),
                event.eventId(), event.resource().path("resourceType").asText());

        HttpAnswer channelAnswer = channel.post(event.resource(), event.headers());
        BecknAnswer answer = answerMapper.map(channelAnswer);
        log.info("OpenHIM channel answered messageId={}: {}", event.messageId(),
                channelAnswer.unreachable() ? "no answer (" + channelAnswer.detail() + ")" : "HTTP " + channelAnswer.status());

        if (!answer.isAck() && ErrorCodes.BIZ_GENERIC_ERROR.equals(answer.code())
                && answer.text() != null && (answer.text().startsWith("ignored:") || answer.text().startsWith("skipped:"))) {
            Counter.builder("cce.emitter.beckn.channel.ignored")
                    .description("Channel answered 200 without forwarding")
                    .tag("subscriber", event.subscriber().subscriberId())
                    .tag("status", answer.text().startsWith("ignored:") ? "ignored" : "skipped")
                    .register(meterRegistry).increment();
        }
        return answer;
    }

    private void countAnswer(String result, String code) {
        Counter.builder("cce.emitter.beckn.answered").description("Sygnal messages answered")
                .tag("action", ACTION).tag("result", result).tag("code", code)
                .register(meterRegistry).increment();
    }
}
