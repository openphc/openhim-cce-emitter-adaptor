package org.openphc.cce.emitter.model.beckn;

import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code Ack} / {@code Nack} body of a Beckn answer, in the shape {@code cce-beckn-service}
 * uses (its {@code MessageFactory}):
 * <pre>
 * { "message": { "status": "ACK", "messageId": "…" } }
 * { "message": { "status": "NACK", "messageId": "…", "error": { "code": "…", "message": "…" } } }
 * </pre>
 * Written by both Beckn endpoints of this adaptor and read from CCE's answer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BecknAck(Message message) {

    public static final String ACK = "ACK";
    public static final String NACK = "NACK";

    // UUID.fromString() accepts short forms such as "1-1-1-1-1". The Beckn schema does not.
    private static final Pattern UUID_FORM =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String status, String messageId, Error error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(String code, String message) {}

    /** An {@code Ack} for the request with this {@code messageId}. */
    public static BecknAck ack(String messageId) {
        return new BecknAck(new Message(ACK, answerId(messageId), null));
    }

    /** A {@code Nack} for the request with this {@code messageId}. */
    public static BecknAck nack(String messageId, String code, String text) {
        return new BecknAck(new Message(NACK, answerId(messageId), new Error(code, text)));
    }

    /**
     * The {@code messageId} of an answer: the request's when it is a UUID (the Beckn schema wants
     * one), otherwise a new one. The same rule as {@code cce-beckn-service}'s {@code Ids.uuidOrRandom}.
     */
    public static String answerId(String requestMessageId) {
        return requestMessageId != null && UUID_FORM.matcher(requestMessageId).matches()
                ? requestMessageId : UUID.randomUUID().toString();
    }
}
