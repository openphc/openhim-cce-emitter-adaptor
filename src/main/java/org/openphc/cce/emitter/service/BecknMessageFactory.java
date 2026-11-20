package org.openphc.cce.emitter.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.beckn.CceClinicalEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Builds the Sygnal message this adaptor sends: the {@code sygnal/event/report} to CCE (spec §5.3),
 * in the CCE profile of Sygnal ({@code cce-beckn-service}'s
 * {@code schema/sygnal-profile/v1.0/cce-sygnal-apis.yaml}).
 */
@Component
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknMessageFactory {

    public static final String VERSION = "2.0.0";
    public static final String EVENT_REPORT = "sygnal/event/report";
    public static final String COORDINATION_EVENT = "sygnal:CoordinationEvent";
    public static final String EVENT_TYPE = "DELIVERY_RECORDED";
    public static final String GRANULARITY = "INDIVIDUAL";
    public static final String ROLE = "PROVIDER";

    private final ObjectMapper mapper;
    private final BecknProperties props;

    public BecknMessageFactory(ObjectMapper mapper, BecknProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * The {@code sygnal/event/report} to CCE: the HIE as sender, CCE as receiver, exactly one event.
     *
     * @param transactionId a new UUID per event
     * @param messageId     a new UUID per message (and per try)
     * @param eventId       the event id: the one the HMIS sent, so CCE de-duplicates on it
     * @param occurredAt    when the clinical event happened (ISO-8601); left out when blank
     * @param box           the {@code CceClinicalEvent} box
     */
    public ObjectNode eventReport(String transactionId, String messageId, String eventId, String occurredAt,
                                  CceClinicalEvent box) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode context = root.putObject("context");
        context.put("version", VERSION);
        context.put("action", EVENT_REPORT);
        context.put("networkId", props.self().networkId());
        context.put("senderId", props.self().id());
        context.put("receiverId", props.cce().id());
        context.put("receiverUri", props.cce().uri());
        context.put("transactionId", transactionId);
        context.put("messageId", messageId);
        context.put("timestamp", Instant.now().toString());

        // No "@context" on the event: onix would refuse it.
        ObjectNode event = root.putObject("message").putArray("events").addObject();
        event.put("@type", COORDINATION_EVENT);
        event.put("id", eventId);
        event.put("revision", 1);
        event.put("eventType", EVENT_TYPE);
        event.put("granularity", GRANULARITY);
        ObjectNode from = event.putObject("from");
        from.put("id", props.self().id());
        from.put("role", ROLE);
        if (occurredAt != null && !occurredAt.isBlank()) {
            event.put("occurredAt", occurredAt);
        }
        event.set("eventAttributes", mapper.valueToTree(box));
        return root;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
