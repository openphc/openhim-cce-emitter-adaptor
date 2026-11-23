package org.openphc.cce.emitter.beckn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.openphc.cce.emitter.beckn.BecknTestSupport.*;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.adaptor.BecknInboundUnwrapper;
import org.openphc.cce.emitter.exception.BecknNackException;
import org.springframework.http.HttpStatus;

/** The checks of spec §3.1 and the headers of §3.2. */
class BecknInboundUnwrapperTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BecknInboundUnwrapper unwrapper =
            new BecknInboundUnwrapper(properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber())));

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private void assertBadRequest(String body, String code, String textPart) {
        assertThatThrownBy(() -> unwrapper.unwrap(json(body)))
                .isInstanceOf(BecknNackException.class)
                .satisfies(e -> {
                    BecknNackException n = (BecknNackException) e;
                    assertThat(n.getCode()).isEqualTo(code);
                    assertThat(n.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                })
                .hasMessageContaining(textPart);
    }

    private static String reportWith(String event) {
        return report(OPENMRS_ID, SELF_ID, event);
    }

    @Test
    @DisplayName("unwraps a valid report: the resource as it is, and the channel headers")
    void unwrapsValidReport() throws Exception {
        BecknInboundUnwrapper.UnwrappedEvent event = unwrapper.unwrap(json(report(ENCOUNTER)));

        assertThat(event.subscriber().subscriberId()).isEqualTo(OPENMRS_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(event.transactionId()).isEqualTo(OPENMRS_TXN);
        assertThat(event.resource()).isEqualTo(json(ENCOUNTER));
        assertThat(event.headers()).containsExactly(
                entry("X-Source-System", OPENMRS_SOURCE),
                entry("X-Source-Event-Id", SOURCE_EVENT_ID),
                entry("X-Sygnal-Event-Id", EVENT_ID),
                entry("X-Sygnal-Occurred-At", OCCURRED_AT),
                entry("X-Correlation-Id", MESSAGE_ID));
        assertThat(event.headers()).doesNotContainKey("X-Facility-Id");
    }

    @Test
    @DisplayName("without sourceEventId the event id is the source event id")
    void noSourceEventId() throws Exception {
        String e = event(EVENT_ID, ENCOUNTER).replace("\"sourceEventId\": \"" + SOURCE_EVENT_ID + "\",", "");
        assertThat(unwrapper.unwrap(json(reportWith(e))).headers()).containsEntry("X-Source-Event-Id", EVENT_ID);
    }

    @Test
    @DisplayName("the sygnal:CoordinationEvent of profile v1.0 is refused as a wrong type")
    void oldCoordinationEvent() {
        assertBadRequest(reportWith(oldEvent(EVENT_ID, ENCOUNTER)), "SCH_TYPE_NOT_SUPPORTED",
                "events[0].@type must be cce:CceClinicalEvent");
    }

    @Test
    void noEventType() {
        assertBadRequest(reportWith(event(EVENT_ID, ENCOUNTER).replace("\"@type\": \"cce:CceClinicalEvent\",", "")),
                "SCH_TYPE_NOT_SUPPORTED", "@type");
    }

    @Test
    void unknownSender() {
        assertBadRequest(report("bap.unknown.example.org", SELF_ID, event(EVENT_ID, ENCOUNTER)),
                "CTX_INVALID_FIELD", "is not a subscriber of this HIE");
    }

    @Test
    void missingSender() {
        assertBadRequest(report("", SELF_ID, event(EVENT_ID, ENCOUNTER)), "CTX_MISSING_FIELD", "context.senderId");
    }

    @Test
    void reportForAnotherReceiver() {
        assertBadRequest(report(OPENMRS_ID, "bap.other-hie.example.org", event(EVENT_ID, ENCOUNTER)),
                "CTX_INVALID_FIELD", "context.receiverId");
    }

    @Test
    void noEvent() {
        assertBadRequest(reportWith(""), "SCH_SCHEMA_VALIDATION_FAILED", "exactly one event");
    }

    @Test
    void twoEvents() {
        assertBadRequest(reportWith(event(EVENT_ID, ENCOUNTER) + "," + event("4a6c1c1e-8a53-4b0e-9d7a-2b6f4e1a9c10", ENCOUNTER)),
                "SCH_SCHEMA_VALIDATION_FAILED", "exactly one event");
    }

    @Test
    void missingEventId() {
        assertBadRequest(reportWith(event("", ENCOUNTER)), "SCH_REQUIRED_FIELD_MISSING", "events[0].id");
    }

    @Test
    void eventIdNotAUuid() {
        assertBadRequest(reportWith(event("enc-1", ENCOUNTER)), "SCH_SCHEMA_VALIDATION_FAILED", "UUID");
    }

    @Test
    void missingOccurredAt() {
        assertBadRequest(reportWith(event(EVENT_ID, ENCOUNTER).replace("\"occurredAt\": \"" + OCCURRED_AT + "\",", "")),
                "SCH_REQUIRED_FIELD_MISSING", "events[0].occurredAt");
    }

    @Test
    void occurredAtNotATime() {
        assertBadRequest(reportWith(event(EVENT_ID, ENCOUNTER).replace(OCCURRED_AT, "yesterday")),
                "SCH_SCHEMA_VALIDATION_FAILED", "occurredAt");
    }

    @Test
    void wrongEventType() {
        assertBadRequest(reportWith(event(EVENT_ID, ENCOUNTER).replace("cce:CceClinicalEvent", "cce:Other")),
                "SCH_TYPE_NOT_SUPPORTED", "@type");
    }

    @Test
    void resourceWithoutResourceType() {
        assertBadRequest(reportWith(event(EVENT_ID, "{\"id\":\"x\"}")), "SCH_SCHEMA_VALIDATION_FAILED", "resourceType");
    }

    @Test
    void resourceNotAnObject() {
        assertBadRequest(reportWith(event(EVENT_ID, "\"text\"")), "SCH_SCHEMA_VALIDATION_FAILED", "resource");
    }

    @Test
    @DisplayName("a sourceEventId that cannot be a header value is replaced by the event id")
    void unsafeSourceEventId() throws Exception {
        String e = event(EVENT_ID, ENCOUNTER).replace(SOURCE_EVENT_ID, "a\\nb");
        assertThat(unwrapper.unwrap(json(reportWith(e))).headers()).containsEntry("X-Source-Event-Id", EVENT_ID);
    }
}
