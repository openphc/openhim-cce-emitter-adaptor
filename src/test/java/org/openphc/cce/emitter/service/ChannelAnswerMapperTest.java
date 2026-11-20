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
    @DisplayName("202 processed → Ack 202")
    void processedIsAck() {
        BecknAnswer a = map(202, "{\"status\":\"processed\",\"eventsForwarded\":1,\"events\":[]}");
        assertThat(a.isAck()).isTrue();
        assertThat(a.status()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("200 ignored → Nack 400 BIZ_GENERIC_ERROR 'ignored: …'")
    void ignored() {
        assertNack(map(200, "{\"status\":\"ignored\",\"message\":\"No matching source or non-processable payload\"}"),
                HttpStatus.BAD_REQUEST, "BIZ_GENERIC_ERROR", "ignored: No matching source or non-processable payload");
    }

    @Test
    @DisplayName("200 skipped → Nack 400 BIZ_GENERIC_ERROR 'skipped: …'")
    void skipped() {
        assertNack(map(200, "{\"status\":\"skipped\",\"message\":\"Event skipped by facility filter\"}"),
                HttpStatus.BAD_REQUEST, "BIZ_GENERIC_ERROR", "skipped: Event skipped by facility filter");
    }

    @Test
    @DisplayName("other 200 → Nack 500 NET_INTERNAL_ERROR")
    void unexpected200() {
        assertNack(map(200, "{\"message\":\"Non-POST request ignored\"}"), HttpStatus.INTERNAL_SERVER_ERROR,
                "NET_INTERNAL_ERROR", null);
    }

    @Test
    @DisplayName("400 PATIENT_ID_NOT_FOUND → BIZ_GENERIC_ERROR with code and message")
    void patientIdNotFound() {
        assertNack(map(400, error("PATIENT_ID_NOT_FOUND", "No subject")), HttpStatus.BAD_REQUEST,
                "BIZ_GENERIC_ERROR", "PATIENT_ID_NOT_FOUND: No subject");
    }

    @Test
    @DisplayName("422 FHIR_MAPPING_ERROR → BIZ_GENERIC_ERROR")
    void fhirMappingError() {
        assertNack(map(422, error("FHIR_MAPPING_ERROR", "bad json")), HttpStatus.BAD_REQUEST,
                "BIZ_GENERIC_ERROR", "FHIR_MAPPING_ERROR: bad json");
    }

    @Test
    @DisplayName("collector 4xx → BIZ_GENERIC_ERROR with the collector's message")
    void collectorRefusal() {
        assertNack(map(422, error("COLLECTOR_CLIENT_ERROR", "Collector returned 422: invalid")),
                HttpStatus.BAD_REQUEST, "BIZ_GENERIC_ERROR", "Collector returned 422: invalid");
    }

    @Test
    @DisplayName("CCE BIZ_GENERIC_ERROR → Nack 400 BIZ_GENERIC_ERROR with CCE's text")
    void cceRefusedEvent() {
        assertNack(map(400, error("COLLECTOR_CLIENT_ERROR", "CCE BIZ_GENERIC_ERROR: Payload validation failed")),
                HttpStatus.BAD_REQUEST, "BIZ_GENERIC_ERROR", "CCE: Payload validation failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCH_SCHEMA_VALIDATION_FAILED", "SCH_TYPE_NOT_SUPPORTED"})
    @DisplayName("CCE's other 4xx Nack → Nack 400 BIZ_GENERIC_ERROR naming CCE's code")
    void cceOtherRefusal(String code) {
        assertNack(map(400, error("COLLECTOR_CLIENT_ERROR", "CCE " + code + ": eventAttributes.patientId is missing")),
                HttpStatus.BAD_REQUEST, "BIZ_GENERIC_ERROR", "CCE " + code + ": eventAttributes.patientId is missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CTX_INVALID_FIELD", "CTX_MISSING_FIELD"})
    @DisplayName("CCE refused the HIE's own context → Nack 500 NET_INTERNAL_ERROR")
    void cceRefusedHieContext(String code) {
        assertNack(map(400, error("COLLECTOR_CLIENT_ERROR", "CCE " + code + ": context.receiverId is 'x'")),
                HttpStatus.INTERNAL_SERVER_ERROR, "NET_INTERNAL_ERROR", "CCE " + code + ": context.receiverId is 'x'");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    @DisplayName("OpenHIM 401 / 403 / 404 → Nack 500 NET_INTERNAL_ERROR")
    void openHimRefused(int status) {
        BecknAnswer a = map(status, "Unauthorized");
        assertNack(a, HttpStatus.INTERNAL_SERVER_ERROR, "NET_INTERNAL_ERROR", null);
        assertThat(a.text()).contains("OpenHIM answered HTTP " + status);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 408, 429})
    @DisplayName("5xx, 408, 429 → Nack 500 NET_DOWNSTREAM_UNAVAILABLE")
    void serverErrors(int status) {
        assertNack(map(status, error("COLLECTOR_FORWARDING_ERROR", "down")), HttpStatus.INTERNAL_SERVER_ERROR,
                "NET_DOWNSTREAM_UNAVAILABLE", null);
    }

    @Test
    @DisplayName("no answer → Nack 500 NET_DOWNSTREAM_UNAVAILABLE")
    void noAnswer() {
        BecknAnswer a = mapper.map(HttpAnswer.unreachable("SocketTimeoutException: Read timed out"));
        assertNack(a, HttpStatus.INTERNAL_SERVER_ERROR, "NET_DOWNSTREAM_UNAVAILABLE", null);
        assertThat(a.text()).contains("Read timed out");
    }
}
