package org.openphc.cce.emitter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;

/** CCE's answer to an update, spec §5.4. */
class CceAnswerMapperTest {

    private final CceAnswerMapper mapper = new CceAnswerMapper(new ObjectMapper());

    private static String nack(String code, String text) {
        return "{\"message\":{\"status\":\"NACK\",\"messageId\":\"m\",\"error\":{\"code\":\"" + code
                + "\",\"message\":\"" + text + "\"}}}";
    }

    private CceAnswerMapper.Outcome map(int status, String body) {
        return mapper.map(HttpAnswer.of(status, "application/json", body));
    }

    @Test
    @DisplayName("200 ACK → Accepted")
    void ack() {
        assertThat(map(200, "{\"message\":{\"status\":\"ACK\",\"messageId\":\"m\"}}"))
                .isInstanceOf(CceAnswerMapper.Accepted.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVENT_REJECTED", "BAD_REQUEST", "NOT_SUPPORTED"})
    @DisplayName("400 NACK → Refused with CCE's code and text")
    void refused(String code) {
        assertThat(map(400, nack(code, "the text"))).isEqualTo(new CceAnswerMapper.Refused(400, code, "the text"));
    }

    @Test
    @DisplayName("400 without a Nack body (cce-onix schema check) → Refused BAD_REQUEST")
    void schemaRefusal() {
        CceAnswerMapper.Outcome o = map(400, "schema validation failed");
        assertThat(o).isInstanceOf(CceAnswerMapper.Refused.class);
        assertThat(((CceAnswerMapper.Refused) o).code()).isEqualTo("BAD_REQUEST");
        assertThat(((CceAnswerMapper.Refused) o).text()).contains("schema validation failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COLLECTOR_UNAVAILABLE", "INTERNAL_ERROR"})
    @DisplayName("500 NACK → Failed")
    void serverNack(String code) {
        CceAnswerMapper.Outcome o = map(500, nack(code, "later"));
        assertThat(o).isInstanceOf(CceAnswerMapper.Failed.class);
        assertThat(((CceAnswerMapper.Failed) o).code()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 502, 503})
    @DisplayName("408, 429, other 5xx → Failed")
    void mayPass(int status) {
        assertThat(map(status, "")).isInstanceOf(CceAnswerMapper.Failed.class);
    }

    @Test
    @DisplayName("no answer → Failed")
    void unreachable() {
        assertThat(mapper.map(HttpAnswer.unreachable("Connection refused")))
                .isEqualTo(new CceAnswerMapper.Failed(0, null, "Connection refused"));
    }

    @Test
    @DisplayName("2xx with a NACK body → Refused, or Failed for a code that may pass")
    void nackInside2xx() {
        assertThat(map(200, nack("EVENT_REJECTED", "x"))).isInstanceOf(CceAnswerMapper.Refused.class);
        assertThat(map(200, nack("COLLECTOR_UNAVAILABLE", "x"))).isInstanceOf(CceAnswerMapper.Failed.class);
    }
}
