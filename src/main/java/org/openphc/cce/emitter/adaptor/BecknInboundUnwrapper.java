package org.openphc.cce.emitter.adaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.exception.BecknNackException;
import org.openphc.cce.emitter.model.beckn.CceClinicalEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Takes an HMIS's {@code update} apart (spec §3.1, steps 1–4): finds the subscriber by
 * {@code context.bapId}, checks the contract and that there is exactly one performance entry with
 * an id, a {@code cce:CceClinicalEvent} box and a FHIR resource, and builds the headers for the
 * OpenHIM channel (spec §3.2). Every failed check is a {@code Nack} (400) {@code BAD_REQUEST}
 * whose text names the field.
 */
@Component
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknInboundUnwrapper {

    public static final String HEADER_SOURCE_SYSTEM = "X-Source-System";
    public static final String HEADER_SOURCE_EVENT_ID = "X-Source-Event-Id";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    private final BecknProperties props;

    public BecknInboundUnwrapper(BecknProperties props) {
        this.props = props;
    }

    /**
     * An unwrapped event.
     *
     * @param subscriber    the HMIS that sent it
     * @param context       the {@code update}'s context, as received
     * @param messageId     {@code context.messageId}
     * @param transactionId {@code context.transactionId}
     * @param performanceId {@code performance[0].id}
     * @param eventType     the box's {@code eventType} (logged only)
     * @param resource      the FHIR resource, as it is
     * @param headers       the headers for the channel, without {@code Authorization} and {@code Content-Type}
     */
    public record UnwrappedEvent(
            BecknProperties.Subscriber subscriber,
            JsonNode context,
            String messageId,
            String transactionId,
            String performanceId,
            String eventType,
            JsonNode resource,
            Map<String, String> headers
    ) {}

    public UnwrappedEvent unwrap(JsonNode body) {
        JsonNode context = body.path("context");
        String bapId = text(context, "bapId");
        if (bapId.isBlank()) {
            throw BecknNackException.badRequest("context.bapId is missing");
        }
        // Step 1: the subscriber
        BecknProperties.Subscriber subscriber = props.subscriber(bapId)
                .orElseThrow(() -> BecknNackException.badRequest(
                        "Unknown subscriber: context.bapId '" + bapId + "' is not a subscriber of this HIE"));

        // Step 2: the contract
        JsonNode contract = body.path("message").path("contract");
        String contractId = text(contract, "id");
        if (contractId.isBlank()) {
            throw BecknNackException.badRequest("message.contract.id is missing");
        }
        if (!contractId.equals(subscriber.contractId())) {
            throw BecknNackException.badRequest("Unknown contract: message.contract.id '" + contractId
                    + "' is not the HIE feed contract of subscriber '" + bapId + "'");
        }

        // Step 3: exactly one performance entry with an id, the box and a resource
        JsonNode performance = contract.path("performance");
        if (!performance.isArray() || performance.size() != 1) {
            throw BecknNackException.badRequest(
                    "message.contract.performance must hold exactly one entry, one event per message");
        }
        JsonNode entry = performance.get(0);
        String performanceId = text(entry, "id");
        if (performanceId.isBlank()) {
            throw BecknNackException.badRequest("message.contract.performance[0].id is missing");
        }
        JsonNode attrs = entry.path("performanceAttributes");
        if (!CceClinicalEvent.TYPE.equals(text(attrs, "@type"))) {
            throw BecknNackException.badRequest(
                    "message.contract.performance[0].performanceAttributes.@type must be " + CceClinicalEvent.TYPE);
        }
        JsonNode resource = attrs.path("resource");
        if (!resource.isObject() || text(resource, "resourceType").isBlank()) {
            throw BecknNackException.badRequest(
                    "message.contract.performance[0].performanceAttributes.resource must be a FHIR resource with a resourceType");
        }

        String messageId = text(context, "messageId");
        return new UnwrappedEvent(subscriber, context, messageId, text(context, "transactionId"),
                performanceId, text(attrs, "eventType"), resource,
                headers(subscriber, performanceId, messageId));
    }

    /** The headers of spec §3.2 (the channel client adds {@code Authorization} and {@code Content-Type}). */
    static Map<String, String> headers(BecknProperties.Subscriber subscriber, String performanceId, String messageId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_SOURCE_SYSTEM, subscriber.sourceSystem());
        headers.put(HEADER_SOURCE_EVENT_ID, performanceId);
        if (messageId != null && !messageId.isBlank()) {
            headers.put(HEADER_CORRELATION_ID, messageId);
        }
        return headers;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isValueNode() ? v.asText("") : "";
    }
}
