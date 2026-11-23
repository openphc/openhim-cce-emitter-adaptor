package org.openphc.cce.emitter.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.beckn.CoordinationEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Builds the Sygnal message this adaptor sends: the {@code sygnal/event/report} to CCE (spec §5.3),
 * in the CCE profile of Sygnal ({@code cce-beckn-service}'s
 * {@code schema/sygnal-profile/v3.0/cce-sygnal-apis.yaml}). The payload is Sygnal's own type,
 * {@code sygnal:CoordinationEvent}, which that profile opens and extends with the fields of a
 * clinical event.
 */
@Component
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknMessageFactory {

    public static final String VERSION = "2.0.0";
    public static final String EVENT_REPORT = "sygnal/event/report";

    private final ObjectMapper mapper;
    private final BecknProperties props;

    public BecknMessageFactory(ObjectMapper mapper, BecknProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * The {@code sygnal/event/report} to CCE: the HIE as sender, CCE as receiver, exactly one event.
     * The event is written as it is, in the field order of {@link CoordinationEvent}: no
     * {@code @context}, no {@code eventAttributes} bag, and no field without a value.
     *
     * @param transactionId a new UUID per event
     * @param messageId     a new UUID per message (and per try)
     * @param event         the clinical event. Its {@code id} is the one the HMIS sent, so CCE
     *                      de-duplicates on it
     */
    public ObjectNode eventReport(String transactionId, String messageId, CoordinationEvent event) {
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

        root.putObject("message").putArray("events").add(mapper.<ObjectNode>valueToTree(event));
        return root;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
