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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @DisplayName("only the required fields: no @context, revision, granularity, from or sourceEventId is needed")
    void requiredFieldsOnly() throws Exception {
        String e = """
                {
                  "@type": "sygnal:CoordinationEvent",
                  "id": "%s",
                  "eventType": "DELIVERY_RECORDED",
                  "occurredAt": "%s",
                  "patientId": "1198503150001234",
                  "fhirVersion": "4.0.1",
                  "resource": %s
                }
                """.formatted(EVENT_ID, OCCURRED_AT, ENCOUNTER);
        BecknInboundUnwrapper.UnwrappedEvent event = unwrapper.unwrap(json(reportWith(e)));

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.resource()).isEqualTo(json(ENCOUNTER));
        assertThat(event.headers()).containsEntry("X-Source-Event-Id", EVENT_ID);
    }

    @Test
    @DisplayName("the type is open: a field the HIE does not know, and an @context, are ignored")
    void unknownFieldsAreIgnored() throws Exception {
        String e = event(EVENT_ID, ENCOUNTER).replace("\"revision\": 1,",
                "\"@context\": \"https://example.org/context.jsonld\", \"revision\": 1, \"somethingNew\": { \"a\": [1, 2] },"
                        + " \"subjectType\": \"PATIENT\",");
        assertThat(e).contains("somethingNew");
        BecknInboundUnwrapper.UnwrappedEvent event = unwrapper.unwrap(json(reportWith(e)));

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.resource()).isEqualTo(json(ENCOUNTER));
        assertThat(event.headers()).containsEntry("X-Source-Event-Id", SOURCE_EVENT_ID);
    }

    @Test
    @DisplayName("the cce:CceClinicalEvent of profile v2.0 is refused as a wrong type")
    void cceClinicalEventOfProfile2() {
        assertBadRequest(reportWith(cceClinicalEvent(EVENT_ID, ENCOUNTER)), "SCH_TYPE_NOT_SUPPORTED",
                "events[0].@type must be sygnal:CoordinationEvent");
    }

    @Test
    @DisplayName("the bag event of profile v1.0 has no resource of its own, so it is refused for the missing resource")
    void bagEventOfProfile1() {
        assertBadRequest(reportWith(bagEvent(EVENT_ID, ENCOUNTER)), "SCH_SCHEMA_VALIDATION_FAILED",
                "events[0].resource must be a FHIR resource with a resourceType");
    }

    @Test
    @DisplayName("an eventAttributes bag next to the event's own resource is an unknown field: ignored, never read")
    void bagNextToTheResourceIsIgnored() throws Exception {
        String bagResource = "{\"resourceType\":\"Observation\",\"id\":\"from-the-bag\"}";
        String e = bagEvent(EVENT_ID, bagResource).replace("\"eventAttributes\":",
                "\"patientId\": \"1198503150001234\", \"fhirVersion\": \"4.0.1\", \"resource\": " + ENCOUNTER
                        + ", \"eventAttributes\":");
        BecknInboundUnwrapper.UnwrappedEvent event = unwrapper.unwrap(json(reportWith(e)));

        assertThat(event.resource()).isEqualTo(json(ENCOUNTER));
        // sourceEventId is read from the event itself, not from the bag
        assertThat(event.headers()).containsEntry("X-Source-Event-Id", EVENT_ID);
    }

    @Test
    void noEventType() {
        assertBadRequest(reportWith(event(EVENT_ID, ENCOUNTER).replace("\"@type\": \"sygnal:CoordinationEvent\",", "")),
                "SCH_TYPE_NOT_SUPPORTED", "@type");
    }

    @Test
    void wrongEventType() {
        assertBadRequest(reportWith(event(EVENT_ID, ENCOUNTER).replace("sygnal:CoordinationEvent", "sygnal:Signal")),
                "SCH_TYPE_NOT_SUPPORTED", "@type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "0", "1.5", "\"1\"", "null"})
    @DisplayName("a revision other than the number 1 is refused: CCE never replaces an event")
    void revisionNotOne(String revision) {
        assertBadRequest(reportWith(event(EVENT_ID, ENCOUNTER).replace("\"revision\": 1,", "\"revision\": " + revision + ",")),
                "SCH_SCHEMA_VALIDATION_FAILED", "events[0].revision must be 1");
    }

    @Test
    @DisplayName("from.id, when sent, must be the sender")
    void fromIsNotTheSender() {
        assertBadRequest(reportWith(event(EVENT_ID, ENCOUNTER).replace(
                        "\"from\": { \"id\": \"" + OPENMRS_ID + "\"", "\"from\": { \"id\": \"bap.other.example.org\"")),
                "SCH_SCHEMA_VALIDATION_FAILED", "events[0].from.id must equal context.senderId");
    }

    @Test
    @DisplayName("revision and from may be left out")
    void noRevisionAndNoFrom() throws Exception {
        String e = event(EVENT_ID, ENCOUNTER).replace("\"revision\": 1,", "")
                .replace("\"from\": { \"id\": \"" + OPENMRS_ID + "\", \"role\": \"PROVIDER\" },", "");
        assertThat(e).doesNotContain("revision", "\"from\"");
        assertThat(unwrapper.unwrap(json(reportWith(e))).eventId()).isEqualTo(EVENT_ID);
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
    void missingResource() {
        String e = event(EVENT_ID, ENCOUNTER);
        e = e.substring(0, e.indexOf("\"resource\":")).stripTrailing();
        e = e.substring(0, e.length() - 1) + "}";
        assertBadRequest(reportWith(e), "SCH_SCHEMA_VALIDATION_FAILED", "events[0].resource must be a FHIR resource");
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
