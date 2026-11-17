package org.openphc.cce.emitter.model.beckn;

import org.springframework.http.HttpStatus;

/**
 * What a Beckn endpoint answers: {@code Ack} (200) or {@code Nack} with a code, HTTP status and text.
 */
public record BecknAnswer(HttpStatus status, String code, String text) {

    public static BecknAnswer ack() {
        return new BecknAnswer(HttpStatus.OK, null, null);
    }

    public static BecknAnswer nack(HttpStatus status, String code, String text) {
        return new BecknAnswer(status, code, text);
    }

    public boolean isAck() {
        return code == null;
    }

    /** The body for the request with this {@code messageId}. */
    public BecknAck body(String requestMessageId) {
        return isAck() ? BecknAck.ack(requestMessageId) : BecknAck.nack(requestMessageId, code, text);
    }
}
