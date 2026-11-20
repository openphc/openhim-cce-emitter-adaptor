package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openphc.cce.emitter.model.beckn.BecknAnswer;
import org.openphc.cce.emitter.model.beckn.ErrorCodes;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;
import org.springframework.http.HttpStatus;

/**
 * Maps the OpenHIM channel's answer (that is, {@code /inbound}'s answer, which carries CCE's answer
 * to the HIE's own {@code sygnal/event/report}) to what the HMIS gets (spec §3.3).
 *
 * <table>
 *   <tr><th>Channel answers</th><th>HMIS gets</th></tr>
 *   <tr><td>2xx, {@code "status":"processed"} (or 202)</td><td>{@code Ack} 202</td></tr>
 *   <tr><td>2xx, {@code "status":"ignored"} / {@code "skipped"}</td><td>{@code Nack} 400 {@code BIZ_GENERIC_ERROR}, {@code ignored: …} / {@code skipped: …}</td></tr>
 *   <tr><td>4xx {@code COLLECTOR_CLIENT_ERROR}, message {@code CCE BIZ_GENERIC_ERROR: <text>}</td><td>{@code Nack} 400 {@code BIZ_GENERIC_ERROR}, {@code CCE: <text>}</td></tr>
 *   <tr><td>4xx {@code COLLECTOR_CLIENT_ERROR}, message {@code CCE CTX_…: <text>} (CCE refused the HIE's own context)</td><td>{@code Nack} 500 {@code NET_INTERNAL_ERROR}</td></tr>
 *   <tr><td>4xx {@code COLLECTOR_CLIENT_ERROR}, message {@code CCE <other code>: <text>}</td><td>{@code Nack} 400 {@code BIZ_GENERIC_ERROR}, {@code CCE <code>: <text>}</td></tr>
 *   <tr><td>4xx with a pipeline error ({@code PATIENT_ID_NOT_FOUND}, {@code FHIR_MAPPING_ERROR}, collector refusal)</td><td>{@code Nack} 400 {@code BIZ_GENERIC_ERROR}</td></tr>
 *   <tr><td>408, 429, 5xx, no answer</td><td>{@code Nack} 500 {@code NET_DOWNSTREAM_UNAVAILABLE}</td></tr>
 *   <tr><td>other 4xx without a pipeline error (OpenHIM 401, 403, 404), other 2xx</td><td>{@code Nack} 500 {@code NET_INTERNAL_ERROR}</td></tr>
 * </table>
 */
public final class ChannelAnswerMapper {

    static final String CCE_PREFIX = "CCE ";
    static final String CCE_REFUSED_EVENT_PREFIX = "CCE " + ErrorCodes.BIZ_GENERIC_ERROR + ": ";
    static final String CCE_CONTEXT_PREFIX = "CCE CTX_";
    static final String COLLECTOR_CLIENT_ERROR = "COLLECTOR_CLIENT_ERROR";

    private final ObjectMapper mapper;

    public ChannelAnswerMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public BecknAnswer map(HttpAnswer answer) {
        if (answer.unreachable()) {
            return unavailable("The OpenHIM channel gave no answer: " + answer.detail());
        }
        int status = answer.status();
        JsonNode body = parse(answer.body());

        if (status >= 200 && status < 300) {
            String bodyStatus = body != null ? body.path("status").asText("") : "";
            String message = body != null ? body.path("message").asText("") : "";
            if ("ignored".equals(bodyStatus) || "skipped".equals(bodyStatus)) {
                return refused(bodyStatus + ": " + (message.isBlank() ? "the event was not forwarded" : message));
            }
            if ("processed".equals(bodyStatus) || status == 202) {
                return BecknAnswer.ack();
            }
            return hieError("Unexpected answer from the OpenHIM channel: HTTP " + status + snippet(answer.body()));
        }

        if (status == 408 || status == 429 || status >= 500) {
            return unavailable("The OpenHIM channel answered HTTP " + status + errorText(body, answer.body()));
        }

        if (status >= 400) {
            String code = body != null ? body.path("error").path("code").asText("") : "";
            String message = body != null ? body.path("error").path("message").asText("") : "";
            if (code.isBlank()) {
                // No pipeline error body: OpenHIM itself refused (client auth, no channel matched)
                return hieError("OpenHIM answered HTTP " + status + snippet(answer.body()));
            }
            if (COLLECTOR_CLIENT_ERROR.equals(code) && message.startsWith(CCE_PREFIX)) {
                if (message.startsWith(CCE_REFUSED_EVENT_PREFIX)) {
                    return refused("CCE: " + message.substring(CCE_REFUSED_EVENT_PREFIX.length()));
                }
                if (message.startsWith(CCE_CONTEXT_PREFIX)) {
                    // CCE refused the HIE's own context (its ids or network): the HIE's settings, not the event
                    return hieError(message);
                }
                return refused(message);
            }
            if (COLLECTOR_CLIENT_ERROR.equals(code)) {
                return refused(message);
            }
            return refused(message.isBlank() ? code : code + ": " + message);
        }

        return hieError("Unexpected answer from the OpenHIM channel: HTTP " + status);
    }

    private static BecknAnswer refused(String text) {
        return BecknAnswer.nack(HttpStatus.BAD_REQUEST, ErrorCodes.BIZ_GENERIC_ERROR, text);
    }

    private static BecknAnswer unavailable(String text) {
        return BecknAnswer.nack(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.NET_DOWNSTREAM_UNAVAILABLE,
                text + ". Send the event again later.");
    }

    private static BecknAnswer hieError(String text) {
        return BecknAnswer.nack(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.NET_INTERNAL_ERROR, text);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorText(JsonNode body, String raw) {
        String code = body != null ? body.path("error").path("code").asText("") : "";
        String message = body != null ? body.path("error").path("message").asText("") : "";
        if (!code.isBlank()) {
            return " " + code + (message.isBlank() ? "" : ": " + message);
        }
        return snippet(raw);
    }

    private static String snippet(String raw) {
        String b = raw == null ? "" : raw.strip();
        if (b.length() > 200) {
            b = b.substring(0, 200) + "…";
        }
        return b.isEmpty() ? "" : ": " + b;
    }
}
