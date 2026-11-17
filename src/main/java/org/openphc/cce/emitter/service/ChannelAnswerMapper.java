package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openphc.cce.emitter.exception.BecknNackException;
import org.openphc.cce.emitter.model.beckn.BecknAnswer;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;
import org.springframework.http.HttpStatus;

/**
 * Maps the OpenHIM channel's answer (that is, {@code /inbound}'s answer) to what the HMIS gets
 * (spec §3.3).
 *
 * <table>
 *   <tr><th>Channel answers</th><th>HMIS gets</th></tr>
 *   <tr><td>2xx, {@code "status":"processed"} (or 202)</td><td>{@code Ack}</td></tr>
 *   <tr><td>2xx, {@code "status":"ignored"} / {@code "skipped"}</td><td>{@code Nack} 400 {@code EVENT_REJECTED}, {@code ignored: …} / {@code skipped: …}</td></tr>
 *   <tr><td>4xx {@code COLLECTOR_CLIENT_ERROR}, message {@code CCE EVENT_REJECTED: <text>}</td><td>{@code Nack} 400 {@code EVENT_REJECTED}, {@code CCE: <text>}</td></tr>
 *   <tr><td>4xx {@code COLLECTOR_CLIENT_ERROR}, message {@code CCE <other code>: <text>}</td><td>{@code Nack} 500 {@code HIE_ERROR}</td></tr>
 *   <tr><td>4xx with a pipeline error ({@code PATIENT_ID_NOT_FOUND}, {@code FHIR_MAPPING_ERROR}, collector refusal)</td><td>{@code Nack} 400 {@code EVENT_REJECTED}</td></tr>
 *   <tr><td>408, 429, 5xx, no answer</td><td>{@code Nack} 500 {@code UNAVAILABLE}</td></tr>
 *   <tr><td>other 4xx without a pipeline error (OpenHIM 401, 403, 404), other 2xx</td><td>{@code Nack} 500 {@code HIE_ERROR}</td></tr>
 * </table>
 */
public final class ChannelAnswerMapper {

    static final String CCE_PREFIX = "CCE ";
    static final String CCE_EVENT_REJECTED_PREFIX = "CCE " + BecknNackException.EVENT_REJECTED + ": ";
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
                return BecknAnswer.nack(HttpStatus.BAD_REQUEST, BecknNackException.EVENT_REJECTED,
                        bodyStatus + ": " + (message.isBlank() ? "the event was not forwarded" : message));
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
                if (message.startsWith(CCE_EVENT_REJECTED_PREFIX)) {
                    return BecknAnswer.nack(HttpStatus.BAD_REQUEST, BecknNackException.EVENT_REJECTED,
                            "CCE: " + message.substring(CCE_EVENT_REJECTED_PREFIX.length()));
                }
                // CCE refused this adaptor's own update (BAD_REQUEST, NOT_SUPPORTED, schema): not the HMIS's fault
                return hieError(message);
            }
            if (COLLECTOR_CLIENT_ERROR.equals(code)) {
                return BecknAnswer.nack(HttpStatus.BAD_REQUEST, BecknNackException.EVENT_REJECTED, message);
            }
            return BecknAnswer.nack(HttpStatus.BAD_REQUEST, BecknNackException.EVENT_REJECTED,
                    message.isBlank() ? code : code + ": " + message);
        }

        return hieError("Unexpected answer from the OpenHIM channel: HTTP " + status);
    }

    private static BecknAnswer unavailable(String text) {
        return BecknAnswer.nack(HttpStatus.INTERNAL_SERVER_ERROR, BecknNackException.UNAVAILABLE,
                text + ". Send the event again later.");
    }

    private static BecknAnswer hieError(String text) {
        return BecknAnswer.nack(HttpStatus.INTERNAL_SERVER_ERROR, BecknNackException.HIE_ERROR, text);
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
