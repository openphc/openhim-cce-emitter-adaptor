package org.openphc.cce.emitter.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openphc.cce.emitter.config.BecknCommitments;
import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.beckn.CceClinicalEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Builds the Beckn messages this adaptor sends: the {@code update} to CCE (spec §5.3) and the
 * {@code on_update} to an HMIS (spec §3.4).
 */
@Component
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknMessageFactory {

    public static final String VERSION = "2.0.0";

    private final ObjectMapper mapper;
    private final BecknProperties props;
    private final BecknCommitments commitments;

    public BecknMessageFactory(ObjectMapper mapper, BecknProperties props, BecknCommitments commitments) {
        this.mapper = mapper;
        this.props = props;
        this.commitments = commitments;
    }

    /**
     * The {@code update} to CCE on the HIE's contract with CCE: OpenHIM as consumer, CCE as provider,
     * exactly one performance entry.
     *
     * @param messageId     a new UUID per message (and per try)
     * @param performanceId the CloudEvent {@code id}
     * @param box           the {@code CceClinicalEvent} box
     */
    public ObjectNode update(String messageId, String performanceId, CceClinicalEvent box) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode context = root.putObject("context");
        context.put("version", VERSION);
        context.put("action", "update");
        String networkId = props.self().networkId();
        if (networkId != null && !networkId.isBlank()) {
            context.put("networkId", networkId);
        }
        context.put("bapId", props.self().bapId());
        context.put("bapUri", props.self().bapUri());
        context.put("bppId", props.cce().bppId());
        context.put("bppUri", props.cce().bppUri());
        context.put("transactionId", props.cce().transactionId());
        context.put("messageId", messageId);
        context.put("timestamp", Instant.now().toString());

        ObjectNode contract = root.putObject("message").putObject("contract");
        contract.put("id", props.cce().contractId());
        contract.putArray("commitments").add(commitments.cce());
        ObjectNode performance = contract.putArray("performance").addObject();
        performance.put("id", performanceId);
        performance.set("performanceAttributes", mapper.valueToTree(box));
        return root;
    }

    /**
     * The {@code on_update} with {@code ACCEPTED} to an HMIS, answering its {@code update}: same
     * {@code transactionId} and {@code messageId}, {@code networkId} copied when the update had one.
     */
    public ObjectNode onUpdateAccepted(BecknProperties.Subscriber subscriber, JsonNode requestContext,
                                       String performanceId) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode context = root.putObject("context");
        context.put("version", VERSION);
        context.put("action", "on_update");
        String networkId = requestContext.path("networkId").asText("");
        if (!networkId.isBlank()) {
            context.put("networkId", networkId);
        }
        context.put("bapId", subscriber.subscriberId());
        context.put("bapUri", subscriber.subscriberUri());
        context.put("bppId", props.self().bppId());
        context.put("bppUri", props.self().bppUri());
        context.put("transactionId", requestContext.path("transactionId").asText(""));
        context.put("messageId", requestContext.path("messageId").asText(""));
        context.put("timestamp", Instant.now().toString());

        ObjectNode contract = root.putObject("message").putObject("contract");
        contract.put("id", subscriber.contractId());
        contract.putObject("status").put("code", "ACTIVE");
        contract.putArray("commitments").add(commitments.forSubscriber(subscriber.subscriberId()));
        ObjectNode performance = contract.putArray("performance").addObject();
        performance.put("id", performanceId);
        performance.putObject("status").put("code", "ACCEPTED");
        return root;
    }

    public static String newMessageId() {
        return UUID.randomUUID().toString();
    }
}
