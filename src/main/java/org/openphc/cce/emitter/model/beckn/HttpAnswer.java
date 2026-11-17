package org.openphc.cce.emitter.model.beckn;

/**
 * The answer to an outbound HTTP call of the Beckn role: status and body, or no answer at all
 * ({@code unreachable}: timeout, connection refused) with a short reason.
 */
public record HttpAnswer(int status, String contentType, String body, boolean unreachable, String detail) {

    public static HttpAnswer of(int status, String contentType, String body) {
        return new HttpAnswer(status, contentType, body, false, null);
    }

    public static HttpAnswer unreachable(String detail) {
        return new HttpAnswer(0, null, null, true, detail);
    }
}
