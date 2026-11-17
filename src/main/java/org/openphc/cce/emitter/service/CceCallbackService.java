package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.exception.BecknNackException;
import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.model.beckn.BecknActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Handles callbacks from CCE on {@code /beckn/bap-webhook/{action}} (spec §6). CCE's
 * {@code on_update} is logged and answered {@code Ack}; a {@code DUPLICATE} also counts
 * {@code cce.emitter.events.duplicate}. Nothing is stored and nothing changes what {@code /inbound}
 * or the unwrap endpoint answered: the {@code on_update} may come before the {@code Ack}, or not at all.
 */
@Service
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CceCallbackService {

    private static final Logger log = LoggerFactory.getLogger(CceCallbackService.class);

    private final BecknProperties props;
    private final MeterRegistry meterRegistry;

    public CceCallbackService(BecknProperties props, MeterRegistry meterRegistry) {
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    public ResponseEntity<BecknAck> handle(String action, JsonNode body) {
        JsonNode context = body.path("context");
        String messageId = context.path("messageId").asText("");
        String bppId = context.path("bppId").asText("");
        JsonNode contract = body.path("message").path("contract");
        JsonNode entry = contract.path("performance").path(0);
        String performanceId = entry.path("id").asText("");
        String status = entry.path("status").path("code").asText("");

        MDC.put("becknMessageId", messageId);
        MDC.put("becknTransactionId", context.path("transactionId").asText(""));
        MDC.put("becknAction", action);
        Counter.builder("cce.emitter.beckn.received").description("Beckn messages received")
                .tag("endpoint", "bap").tag("action", BecknActions.tag(action)).register(meterRegistry).increment();
        try {
            log.info("Beckn callback {} from bppId={} messageId={} performanceId={} status={}",
                    action, bppId, messageId, performanceId, status);
            if (!"on_update".equals(action)) {
                return nack(action, messageId, BecknNackException.NOT_SUPPORTED,
                        "The action '" + action + "' is not supported");
            }
            if (!bppId.equals(props.cce().bppId())) {
                return nack(action, messageId, BecknNackException.BAD_REQUEST,
                        "context.bppId '" + bppId + "' is not CCE");
            }
            String contractId = contract.path("id").asText("");
            if (!contractId.equals(props.cce().contractId())) {
                return nack(action, messageId, BecknNackException.BAD_REQUEST,
                        "message.contract.id '" + contractId + "' is not the HIE's contract with CCE");
            }
            if ("ACCEPTED".equals(status) || "DUPLICATE".equals(status)) {
                Counter.builder("cce.emitter.beckn.cce.onupdate.received").description("CCE's on_update")
                        .tag("status", status).register(meterRegistry).increment();
            }
            if ("DUPLICATE".equals(status)) {
                Counter.builder("cce.emitter.events.duplicate").register(meterRegistry).increment();
            }
            count(action, "ack", "none");
            return ResponseEntity.ok(BecknAck.ack(messageId));
        } finally {
            MDC.remove("becknMessageId");
            MDC.remove("becknTransactionId");
            MDC.remove("becknAction");
        }
    }

    private ResponseEntity<BecknAck> nack(String action, String messageId, String code, String text) {
        log.info("Beckn callback {} messageId={} answered Nack {}: {}", action, messageId, code, text);
        count(action, "nack", code);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(BecknAck.nack(messageId, code, text));
    }

    private void count(String action, String result, String code) {
        Counter.builder("cce.emitter.beckn.answered").description("Beckn messages answered")
                .tag("endpoint", "bap").tag("action", BecknActions.tag(action)).tag("result", result).tag("code", code)
                .register(meterRegistry).increment();
    }
}
