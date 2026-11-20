package org.openphc.cce.emitter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openphc.cce.emitter.beckn.BecknTestSupport.*;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.model.beckn.CceClinicalEvent;

/** The sygnal/event/report to CCE (spec §5.3) and the Ack/Nack bodies (§3.3). */
class BecknMessageFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BecknProperties props = properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber()));
    private final BecknMessageFactory factory = new BecknMessageFactory(mapper, props);

    @Test
    @DisplayName("report to CCE: closed Sygnal context, exactly one CoordinationEvent with the given id")
    void reportToCce() throws Exception {
        CceClinicalEvent box = new CceClinicalEvent("ctx", CceClinicalEvent.TYPE, "Encounter", "p1", "0002", null,
                "4.0.1", "src-1", mapper.readTree("{\"resourceType\":\"Encounter\"}"));
        JsonNode r = factory.eventReport("txn-1", "msg-1", EVENT_ID, OCCURRED_AT, box);

        JsonNode c = r.path("context");
        assertThat(c.path("version").asText()).isEqualTo("2.0.0");
        assertThat(c.path("action").asText()).isEqualTo("sygnal/event/report");
        assertThat(c.path("networkId").asText()).isEqualTo(NETWORK_ID);
        assertThat(c.path("senderId").asText()).isEqualTo(SELF_ID);
        assertThat(c.path("receiverId").asText()).isEqualTo(CCE_ID);
        assertThat(c.path("receiverUri").asText()).isEqualTo(CCE_URI);
        assertThat(c.path("transactionId").asText()).isEqualTo("txn-1");
        assertThat(c.path("messageId").asText()).isEqualTo("msg-1");
        assertThat(c.path("timestamp").asText()).isNotBlank();
        // Closed object: nothing else, in particular no Beckn bap/bpp fields
        assertThat(c.size()).isEqualTo(9);

        JsonNode events = r.path("message").path("events");
        assertThat(r.path("message").size()).isEqualTo(1);
        assertThat(events).hasSize(1);
        JsonNode e = events.get(0);
        assertThat(e.has("@context")).isFalse();
        assertThat(e.path("@type").asText()).isEqualTo("sygnal:CoordinationEvent");
        assertThat(e.path("id").asText()).isEqualTo(EVENT_ID);
        assertThat(e.path("revision").asInt()).isEqualTo(1);
        assertThat(e.path("eventType").asText()).isEqualTo("DELIVERY_RECORDED");
        assertThat(e.path("granularity").asText()).isEqualTo("INDIVIDUAL");
        assertThat(e.path("from").path("id").asText()).isEqualTo(SELF_ID);
        assertThat(e.path("from").path("role").asText()).isEqualTo("PROVIDER");
        assertThat(e.path("occurredAt").asText()).isEqualTo(OCCURRED_AT);
        assertThat(e.path("eventAttributes").path("@type").asText()).isEqualTo("cce:CceClinicalEvent");
        assertThat(e.path("eventAttributes").path("@context").asText()).isEqualTo("ctx");
        assertThat(e.path("eventAttributes").path("sourceEventId").asText()).isEqualTo("src-1");
    }

    @Test
    @DisplayName("no occurredAt → the field is left out")
    void noOccurredAt() {
        JsonNode e = factory.eventReport("t", "m", EVENT_ID, null, null).at("/message/events/0");
        assertThat(e.has("occurredAt")).isFalse();
    }

    @Test
    @DisplayName("Ack / Nack bodies keep the request's messageId, in CCE's shape")
    void ackNackBodies() {
        JsonNode ack = mapper.valueToTree(BecknAck.ack(MESSAGE_ID));
        assertThat(ack.toString()).isEqualTo("{\"message\":{\"status\":\"ACK\",\"messageId\":\"" + MESSAGE_ID + "\"}}");
        JsonNode nack = mapper.valueToTree(BecknAck.nack(MESSAGE_ID, "NET_INTERNAL_ERROR", "text"));
        assertThat(nack.path("message").path("status").asText()).isEqualTo("NACK");
        assertThat(nack.path("message").path("messageId").asText()).isEqualTo(MESSAGE_ID);
        assertThat(nack.path("message").path("error").path("code").asText()).isEqualTo("NET_INTERNAL_ERROR");
        // Not a UUID: a new one, as cce-beckn-service does
        assertThat(BecknAck.answerId("not-a-uuid")).isNotEqualTo("not-a-uuid").hasSize(36);
    }
}
