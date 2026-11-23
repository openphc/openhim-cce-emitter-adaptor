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
import org.openphc.cce.emitter.model.beckn.CoordinationEvent;

/** The sygnal/event/report to CCE (spec §5.3) and the Ack/Nack bodies (§3.3). */
class BecknMessageFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BecknProperties props = properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber()));
    private final BecknMessageFactory factory = new BecknMessageFactory(mapper, props);

    private CoordinationEvent event(String facilityId, String facilityName, String sourceEventId) throws Exception {
        return CoordinationEvent.clinical(EVENT_ID, SELF_ID, OCCURRED_AT, "1198503150001234", facilityId,
                facilityName, sourceEventId, mapper.readTree("{\"resourceType\":\"Encounter\",\"id\":\"e1\"}"));
    }

    @Test
    @DisplayName("report to CCE: closed Sygnal context, and message.events = [ the extended sygnal:CoordinationEvent ]")
    void reportToCce() throws Exception {
        JsonNode r = factory.eventReport("txn-1", "msg-1", event("0002", "Kicukiro HC", "src-1"));

        assertThat(r.fieldNames()).toIterable().containsExactly("context", "message");
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

        // The exact message: one event, the extended sygnal:CoordinationEvent of profile v3.0, and nothing else
        assertThat(r.path("message")).isEqualTo(mapper.readTree("""
                {
                  "events": [ {
                    "@type": "sygnal:CoordinationEvent",
                    "id": "3f6c1c1e-8a53-4b0e-9d7a-2b6f4e1a9c10",
                    "revision": 1,
                    "eventType": "DELIVERY_RECORDED",
                    "granularity": "INDIVIDUAL",
                    "from": { "id": "bap.openhim.example.org", "role": "PROVIDER" },
                    "occurredAt": "2026-11-13T07:55:00Z",
                    "patientId": "1198503150001234",
                    "fhirVersion": "4.0.1",
                    "facilityId": "0002",
                    "facilityName": "Kicukiro HC",
                    "sourceEventId": "src-1",
                    "resource": { "resourceType": "Encounter", "id": "e1" }
                  } ]
                }
                """));
        // The same, as the text that goes on the wire: this also fixes the order of the fields
        assertThat(mapper.writeValueAsString(r.path("message"))).isEqualTo(
                "{\"events\":[{\"@type\":\"sygnal:CoordinationEvent\","
                        + "\"id\":\"3f6c1c1e-8a53-4b0e-9d7a-2b6f4e1a9c10\","
                        + "\"revision\":1,"
                        + "\"eventType\":\"DELIVERY_RECORDED\","
                        + "\"granularity\":\"INDIVIDUAL\","
                        + "\"from\":{\"id\":\"bap.openhim.example.org\",\"role\":\"PROVIDER\"},"
                        + "\"occurredAt\":\"2026-11-13T07:55:00Z\","
                        + "\"patientId\":\"1198503150001234\","
                        + "\"fhirVersion\":\"4.0.1\","
                        + "\"facilityId\":\"0002\","
                        + "\"facilityName\":\"Kicukiro HC\","
                        + "\"sourceEventId\":\"src-1\","
                        + "\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"e1\"}}]}");
        JsonNode e = r.at("/message/events/0");
        assertThat(e.fieldNames()).toIterable().containsExactly("@type", "id", "revision", "eventType",
                "granularity", "from", "occurredAt", "patientId", "fhirVersion", "facilityId", "facilityName",
                "sourceEventId", "resource");
        assertThat(e.path("from").path("id")).isEqualTo(c.path("senderId"));
        // Nothing of profile v1.0 (the bag) or v2.0 (cce:CceClinicalEvent with an @context)
        assertThat(r.toString()).doesNotContain("@context", "eventAttributes", "CceClinicalEvent", "cce:");
    }

    @Test
    @DisplayName("optional fields without a value are left out: no null, no empty string")
    void optionalFieldsLeftOut() throws Exception {
        for (String blank : new String[] {null, "", "  "}) {
            JsonNode e = factory.eventReport("t", "m", event(blank, blank, blank)).at("/message/events/0");
            assertThat(e.fieldNames()).toIterable().containsExactly("@type", "id", "revision", "eventType",
                    "granularity", "from", "occurredAt", "patientId", "fhirVersion", "resource");
            assertThat(e.toString()).doesNotContain("null", "\"\"");
        }
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
