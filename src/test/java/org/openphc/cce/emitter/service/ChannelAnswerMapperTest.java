package org.openphc.cce.emitter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openphc.cce.emitter.model.beckn.BecknAnswer;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;
import org.springframework.http.HttpStatus;

/** The channel answer → HMIS answer table of spec §3.3. */
class ChannelAnswerMapperTest {

    private final ChannelAnswerMapper mapper = new ChannelAnswerMapper(new ObjectMapper());

    private BecknAnswer map(int status, String body) {
        return mapper.map(HttpAnswer.of(status, "application/json", body));
    }

    private static String error(String code, String message) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"},\"timestamp\":\"t\"}";
    }

    private static void assertNack(BecknAnswer a, HttpStatus status, String code, String text) {
        assertThat(a.isAck()).isFalse();
        assertThat(a.status()).isEqualTo(status);
        assertThat(a.code()).isEqualTo(code);
        if (text != null) {
            assertThat(a.text()).isEqualTo(text);
        }
    }

    @Test
    @DisplayName("202 processed → Ack")
    void processedIsAck() {
        BecknAnswer a = map(202, "{\"status\":\"processed\",\"eventsForwarded\":1,\"events\":[]}");
        assertThat(a.isAck()).isTrue();
        assertThat(a.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("200 ignored → Nack 400 EVENT_REJECTED 'ignored: …'")
    void ignored() {
        assertNack(map(200, "{\"status\":\"ignored\",\"message\":\"No matching source or non-processable payload\"}"),
                HttpStatus.BAD_REQUEST, "EVENT_REJECTED", "ignored: No matching source or non-processable payload");
    }

    @Test
    @DisplayName("200 skipped → Nack 400 EVENT_REJECTED 'skipped: …'")
    void skipped() {
        assertNack(map(200, "{\"status\":\"skipped\",\"message\":\"Event skipped by facility filter\"}"),
                HttpStatus.BAD_REQUEST, "EVENT_REJECTED", "skipped: Event skipped by facility filter");
    }

    @Test
    @DisplayName("other 200 → Nack 500 HIE_ERROR")
    void unexpected200() {
        assertNack(map(200, "{\"message\":\"Non-POST request ignored\"}"), HttpStatus.INTERNAL_SERVER_ERROR,
                "HIE_ERROR", null);
    }

    @Test
    @DisplayName("400 PATIENT_ID_NOT_FOUND → EVENT_REJECTED with code and message")
    void patientIdNotFound() {
        assertNack(map(400, error("PATIENT_ID_NOT_FOUND", "No subject")), HttpStatus.BAD_REQUEST,
                "EVENT_REJECTED", "PATIENT_ID_NOT_FOUND: No subject");
    }

    @Test
    @DisplayName("422 FHIR_MAPPING_ERROR → EVENT_REJECTED")
    void fhirMappingError() {
        assertNack(map(422, error("FHIR_MAPPING_ERROR", "bad json")), HttpStatus.BAD_REQUEST,
                "EVENT_REJECTED", "FHIR_MAPPING_ERROR: bad json");
    }

    @Test
    @DisplayName("collector 4xx → EVENT_REJECTED with the collector's message")
    void collectorRefusal() {
        assertNack(map(422, error("COLLECTOR_CLIENT_ERROR", "Collector returned 422: invalid")),
                HttpStatus.BAD_REQUEST, "EVENT_REJECTED", "Collector returned 422: invalid");
    }

    @Test
    @DisplayName("CCE EVENT_REJECTED → EVENT_REJECTED with CCE's text")
    void cceEventRejected() {
        assertNack(map(400, error("COLLECTOR_CLIENT_ERROR", "CCE EVENT_REJECTED: Payload validation failed")),
                HttpStatus.BAD_REQUEST, "EVENT_REJECTED", "CCE: Payload validation failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BAD_REQUEST", "NOT_SUPPORTED"})
    @DisplayName("CCE BAD_REQUEST / NOT_SUPPORTED → Nack 500 HIE_ERROR")
    void cceRefusedOwnUpdate(String code) {
        assertNack(map(400, error("COLLECTOR_CLIENT_ERROR", "CCE " + code + ": performance[0].id is missing")),
                HttpStatus.INTERNAL_SERVER_ERROR, "HIE_ERROR", "CCE " + code + ": performance[0].id is missing");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    @DisplayName("OpenHIM 401 / 403 / 404 → Nack 500 HIE_ERROR")
    void openHimRefused(int status) {
        BecknAnswer a = map(status, "Unauthorized");
        assertNack(a, HttpStatus.INTERNAL_SERVER_ERROR, "HIE_ERROR", null);
        assertThat(a.text()).contains("OpenHIM answered HTTP " + status);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 408, 429})
    @DisplayName("5xx, 408, 429 → Nack 500 UNAVAILABLE")
    void serverErrors(int status) {
        assertNack(map(status, error("COLLECTOR_FORWARDING_ERROR", "down")), HttpStatus.INTERNAL_SERVER_ERROR,
                "UNAVAILABLE", null);
    }

    @Test
    @DisplayName("no answer → Nack 500 UNAVAILABLE")
    void noAnswer() {
        BecknAnswer a = mapper.map(HttpAnswer.unreachable("SocketTimeoutException: Read timed out"));
        assertNack(a, HttpStatus.INTERNAL_SERVER_ERROR, "UNAVAILABLE", null);
        assertThat(a.text()).contains("Read timed out");
    }
}
