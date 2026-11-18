package org.openphc.cce.emitter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openphc.cce.emitter.beckn.BecknTestSupport.*;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.BecknCommitments;
import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.model.beckn.CceClinicalEvent;
import org.springframework.core.io.DefaultResourceLoader;

/** The update to CCE (spec §5.3), the on_update to an HMIS (§3.4) and the Ack/Nack bodies (§3.3). */
class BecknMessageFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BecknProperties props = properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber()));
    private final BecknMessageFactory factory = new BecknMessageFactory(mapper, props,
            new BecknCommitments(props, new DefaultResourceLoader(), mapper));

    @Test
    @DisplayName("update to CCE: context, contract, CCE's exact commitment, one performance entry")
    void updateToCce() throws Exception {
        CceClinicalEvent box = new CceClinicalEvent("ctx", CceClinicalEvent.TYPE, "Encounter", "p1", "0002", null,
                "4.0.1", "src-1", mapper.readTree("{\"resourceType\":\"Encounter\"}"));
        JsonNode u = factory.update("msg-1", "perf-1", box);

        JsonNode c = u.path("context");
        assertThat(c.path("action").asText()).isEqualTo("update");
        assertThat(c.path("version").asText()).isEqualTo("2.0.0");
        assertThat(c.has("networkId")).isFalse();
        assertThat(c.path("bapId").asText()).isEqualTo("bap.openhim.example.org");
        assertThat(c.path("bppId").asText()).isEqualTo(CCE_BPP_ID);
        assertThat(c.path("transactionId").asText()).isEqualTo(CCE_TXN);
        assertThat(c.path("messageId").asText()).isEqualTo("msg-1");
        assertThat(c.path("timestamp").asText()).isNotBlank();

        JsonNode contract = u.path("message").path("contract");
        assertThat(contract.path("id").asText()).isEqualTo(CCE_CONTRACT);
        JsonNode expectedCommitment = mapper.readTree(
                getClass().getResourceAsStream("/beckn/commitment-cce.json"));
        assertThat(contract.path("commitments")).hasSize(1);
        assertThat(contract.path("commitments").get(0)).isEqualTo(expectedCommitment);
        assertThat(expectedCommitment.path("id").asText()).isEqualTo("cce-care-coordination");
        assertThat(contract.path("performance")).hasSize(1);
        assertThat(contract.path("performance").get(0).path("id").asText()).isEqualTo("perf-1");
        assertThat(contract.path("performance").get(0).path("performanceAttributes").path("@type").asText())
                .isEqualTo("cce:CceClinicalEvent");
    }

    @Test
    @DisplayName("update carries beckn.self.network-id when set")
    void networkId() {
        BecknProperties withNetwork = new BecknProperties(true, props.schemaBaseUrl(), props.onix(),
                new BecknProperties.Self("net-1", "bap", "u", "bpp", "u"), props.cce(), props.openhimChannel(),
                props.subscribers());
        BecknMessageFactory f = new BecknMessageFactory(mapper, withNetwork,
                new BecknCommitments(withNetwork, new DefaultResourceLoader(), mapper));
        assertThat(f.update("m", "p", null).path("context").path("networkId").asText()).isEqualTo("net-1");
    }

    @Test
    @DisplayName("on_update ACCEPTED to the HMIS answers its update")
    void onUpdate() throws Exception {
        JsonNode requestContext = mapper.readTree(update(ENCOUNTER)).path("context");
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestContext).put("networkId", "hmis-net");
        JsonNode m = factory.onUpdateAccepted(openmrsSubscriber(), requestContext, OPENMRS_CONTRACT, PERFORMANCE_ID);

        JsonNode c = m.path("context");
        assertThat(c.path("action").asText()).isEqualTo("on_update");
        assertThat(c.path("networkId").asText()).isEqualTo("hmis-net");
        assertThat(c.path("bapId").asText()).isEqualTo(OPENMRS_BAP_ID);
        assertThat(c.path("bppId").asText()).isEqualTo("bpp.openhim.example.org");
        assertThat(c.path("transactionId").asText()).isEqualTo(OPENMRS_TXN);
        assertThat(c.path("messageId").asText()).isEqualTo(MESSAGE_ID);
        JsonNode contract = m.path("message").path("contract");
        assertThat(contract.path("id").asText()).isEqualTo(OPENMRS_CONTRACT);
        assertThat(contract.path("status").path("code").asText()).isEqualTo("ACTIVE");
        assertThat(contract.path("commitments").get(0).path("id").asText()).isEqualTo("hie-event-feed");
        assertThat(contract.path("performance").get(0).path("id").asText()).isEqualTo(PERFORMANCE_ID);
        assertThat(contract.path("performance").get(0).path("status").path("code").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("Ack / Nack bodies keep the request's messageId, in CCE's shape")
    void ackNackBodies() {
        JsonNode ack = mapper.valueToTree(BecknAck.ack(MESSAGE_ID));
        assertThat(ack.toString()).isEqualTo("{\"message\":{\"status\":\"ACK\",\"messageId\":\"" + MESSAGE_ID + "\"}}");
        JsonNode nack = mapper.valueToTree(BecknAck.nack(MESSAGE_ID, "HIE_ERROR", "text"));
        assertThat(nack.path("message").path("status").asText()).isEqualTo("NACK");
        assertThat(nack.path("message").path("messageId").asText()).isEqualTo(MESSAGE_ID);
        assertThat(nack.path("message").path("error").path("code").asText()).isEqualTo("HIE_ERROR");
        // Not a UUID: a new one, as cce-beckn-service does
        assertThat(BecknAck.answerId("not-a-uuid")).isNotEqualTo("not-a-uuid").hasSize(36);
    }
}
