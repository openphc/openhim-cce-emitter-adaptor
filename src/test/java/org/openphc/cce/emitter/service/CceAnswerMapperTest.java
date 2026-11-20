package org.openphc.cce.emitter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;

/** CCE's answer to a sygnal/event/report, spec §5.4. */
class CceAnswerMapperTest {

    private final CceAnswerMapper mapper = new CceAnswerMapper(new ObjectMapper());

    private static String nack(String code, String text) {
        return "{\"message\":{\"status\":\"NACK\",\"messageId\":\"m\",\"error\":{\"code\":\"" + code
                + "\",\"message\":\"" + text + "\"}}}";
    }

    private CceAnswerMapper.Outcome map(int status, String body) {
        return mapper.map(HttpAnswer.of(status, "application/json", body));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202})
    @DisplayName("2xx ACK (new event or one CCE already has) → Accepted")
    void ack(int status) {
        assertThat(map(status, "{\"message\":{\"status\":\"ACK\",\"messageId\":\"m\"}}"))
                .isInstanceOf(CceAnswerMapper.Accepted.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BIZ_GENERIC_ERROR", "CTX_INVALID_FIELD", "SCH_SCHEMA_VALIDATION_FAILED"})
    @DisplayName("400 NACK → Refused with CCE's code and text")
    void refused(String code) {
        assertThat(map(400, nack(code, "the text"))).isEqualTo(new CceAnswerMapper.Refused(400, code, "the text"));
    }

    @Test
    @DisplayName("400 without a Nack body → Refused BIZ_GENERIC_ERROR")
    void schemaRefusal() {
        CceAnswerMapper.Outcome o = map(400, "schema validation failed");
        assertThat(o).isInstanceOf(CceAnswerMapper.Refused.class);
        assertThat(((CceAnswerMapper.Refused) o).code()).isEqualTo("BIZ_GENERIC_ERROR");
        assertThat(((CceAnswerMapper.Refused) o).text()).contains("schema validation failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NET_DOWNSTREAM_UNAVAILABLE", "NET_INTERNAL_ERROR"})
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
        assertThat(map(200, nack("BIZ_GENERIC_ERROR", "x"))).isInstanceOf(CceAnswerMapper.Refused.class);
        assertThat(map(200, nack("NET_DOWNSTREAM_UNAVAILABLE", "x"))).isInstanceOf(CceAnswerMapper.Failed.class);
    }
}
