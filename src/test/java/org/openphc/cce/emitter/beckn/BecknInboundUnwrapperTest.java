package org.openphc.cce.emitter.beckn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.openphc.cce.emitter.beckn.BecknTestSupport.*;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.adaptor.BecknInboundUnwrapper;
import org.openphc.cce.emitter.exception.BecknNackException;
import org.springframework.http.HttpStatus;

/** Steps 1–4 of spec §3.1 and the headers of §3.2. */
class BecknInboundUnwrapperTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BecknInboundUnwrapper unwrapper =
            new BecknInboundUnwrapper(properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber())));

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private void assertBadRequest(String body, String textPart) {
        assertThatThrownBy(() -> unwrapper.unwrap(json(body)))
                .isInstanceOf(BecknNackException.class)
                .satisfies(e -> {
                    BecknNackException n = (BecknNackException) e;
                    assertThat(n.getCode()).isEqualTo("BAD_REQUEST");
                    assertThat(n.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                })
                .hasMessageContaining(textPart);
    }

    @Test
    @DisplayName("unwraps a valid update: the resource as it is, and the channel headers")
    void unwrapsValidUpdate() throws Exception {
        BecknInboundUnwrapper.UnwrappedEvent event = unwrapper.unwrap(json(update(ENCOUNTER)));

        assertThat(event.subscriber().subscriberId()).isEqualTo(OPENMRS_BAP_ID);
        assertThat(event.performanceId()).isEqualTo(PERFORMANCE_ID);
        assertThat(event.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(event.transactionId()).isEqualTo(OPENMRS_TXN);
        assertThat(event.eventType()).isEqualTo("Encounter");
        assertThat(event.resource()).isEqualTo(json(ENCOUNTER));
        assertThat(event.headers()).containsExactly(
                org.assertj.core.api.Assertions.entry("X-Source-System", OPENMRS_SOURCE),
                org.assertj.core.api.Assertions.entry("X-Source-Event-Id", PERFORMANCE_ID),
                org.assertj.core.api.Assertions.entry("X-Correlation-Id", MESSAGE_ID));
        assertThat(event.headers()).doesNotContainKey("X-Facility-Id");
    }

    @Test
    void unknownSubscriber() {
        assertBadRequest(update("bap.unknown.example.org", OPENMRS_CONTRACT, entry(PERFORMANCE_ID, ENCOUNTER)),
                "Unknown subscriber");
    }

    @Test
    void missingBapId() {
        assertBadRequest(update("", OPENMRS_CONTRACT, entry(PERFORMANCE_ID, ENCOUNTER)), "context.bapId");
    }

    @Test
    void anyContractIdIsAccepted() throws Exception {
        var event = unwrapper.unwrap(json(update(OPENMRS_BAP_ID, "hmis-chosen-contract", entry(PERFORMANCE_ID, ENCOUNTER))));
        assertThat(event.contractId()).isEqualTo("hmis-chosen-contract");
    }

    @Test
    void missingContractId() {
        assertBadRequest(update(OPENMRS_BAP_ID, "", entry(PERFORMANCE_ID, ENCOUNTER)), "message.contract.id is missing");
    }

    @Test
    void noPerformanceEntry() {
        assertBadRequest(update(OPENMRS_BAP_ID, OPENMRS_CONTRACT, ""), "exactly one entry");
    }

    @Test
    void twoPerformanceEntries() {
        assertBadRequest(update(OPENMRS_BAP_ID, OPENMRS_CONTRACT,
                entry(PERFORMANCE_ID, ENCOUNTER) + "," + entry("second", ENCOUNTER)), "exactly one entry");
    }

    @Test
    void missingPerformanceId() {
        assertBadRequest(update(OPENMRS_BAP_ID, OPENMRS_CONTRACT, entry("", ENCOUNTER)), "performance[0].id");
    }

    @Test
    void wrongBoxType() {
        assertBadRequest(update(OPENMRS_BAP_ID, OPENMRS_CONTRACT,
                entry(PERFORMANCE_ID, ENCOUNTER).replace("cce:CceClinicalEvent", "cce:Other")), "@type");
    }

    @Test
    void resourceWithoutResourceType() {
        assertBadRequest(update(OPENMRS_BAP_ID, OPENMRS_CONTRACT, entry(PERFORMANCE_ID, "{\"id\":\"x\"}")),
                "resourceType");
    }

    @Test
    void resourceNotAnObject() {
        assertBadRequest(update(OPENMRS_BAP_ID, OPENMRS_CONTRACT, entry(PERFORMANCE_ID, "\"text\"")), "resource");
    }
}
