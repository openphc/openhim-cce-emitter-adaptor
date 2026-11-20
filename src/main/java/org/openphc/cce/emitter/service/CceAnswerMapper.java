package org.openphc.cce.emitter.service;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.model.beckn.ErrorCodes;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;

/**
 * Reads CCE's answer to a {@code sygnal/event/report}, as {@code onix-openhim} passes it back
 * (spec §5.4). CCE answers {@code 202 ACK} both for a new event and for one it already has.
 *
 * <ul>
 *   <li>2xx, body not a {@code NACK} → {@link Accepted}</li>
 *   <li>408, 429, 5xx, any other non-4xx, no answer → {@link Failed} (may pass)</li>
 *   <li>other 4xx → {@link Refused} with CCE's code ({@code BIZ_GENERIC_ERROR} when the body has
 *       none) and text</li>
 *   <li>2xx with a {@code NACK} body → {@link Refused}, or {@link Failed} when the code is one that
 *       may pass ({@code NET_DOWNSTREAM_UNAVAILABLE}, {@code NET_INTERNAL_ERROR})</li>
 * </ul>
 */
public final class CceAnswerMapper {

    /** CCE's codes for an answer that may pass. */
    static final Set<String> MAY_PASS_CODES =
            Set.of(ErrorCodes.NET_DOWNSTREAM_UNAVAILABLE, ErrorCodes.NET_INTERNAL_ERROR);

    public sealed interface Outcome permits Accepted, Refused, Failed {}

    /** CCE has the event. */
    public record Accepted(int status) implements Outcome {}

    /** CCE (or its onix) refused the report. Not retried. */
    public record Refused(int status, String code, String text) implements Outcome {}

    /** No answer, or one that may pass. */
    public record Failed(int status, String code, String reason) implements Outcome {}

    private final ObjectMapper mapper;

    public CceAnswerMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Outcome map(HttpAnswer answer) {
        if (answer.unreachable()) {
            return new Failed(0, null, answer.detail());
        }
        int status = answer.status();
        BecknAck.Message message = parse(answer.body());
        boolean nack = message != null && BecknAck.NACK.equals(message.status());
        String code = nack && message.error() != null ? blankToNull(message.error().code()) : null;
        String text = nack && message.error() != null ? blankToNull(message.error().message()) : null;

        if (status >= 200 && status < 300) {
            if (!nack) {
                return new Accepted(status);
            }
            if (code != null && MAY_PASS_CODES.contains(code)) {
                return new Failed(status, code, describe(status, code, text));
            }
            return new Refused(status, code != null ? code : ErrorCodes.BIZ_GENERIC_ERROR, text != null ? text : "HTTP " + status);
        }
        if (status >= 400 && status < 500 && status != 408 && status != 429) {
            return new Refused(status, code != null ? code : ErrorCodes.BIZ_GENERIC_ERROR,
                    text != null ? text : fallbackText(status, answer.body()));
        }
        return new Failed(status, code, describe(status, code, text));
    }

    private BecknAck.Message parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.path("message").isObject()) {
                return null;
            }
            return mapper.treeToValue(node.path("message"), BecknAck.Message.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(int status, String code, String text) {
        StringBuilder sb = new StringBuilder("HTTP ").append(status);
        if (code != null) {
            sb.append(' ').append(code);
        }
        if (text != null) {
            sb.append(": ").append(text);
        }
        return sb.toString();
    }

    private static String fallbackText(int status, String body) {
        String b = body == null ? "" : body.strip();
        if (b.length() > 300) {
            b = b.substring(0, 300) + "…";
        }
        return b.isEmpty() ? "HTTP " + status : "HTTP " + status + ": " + b;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
