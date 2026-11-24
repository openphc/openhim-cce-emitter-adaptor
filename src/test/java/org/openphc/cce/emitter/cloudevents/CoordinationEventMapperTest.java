package org.openphc.cce.emitter.cloudevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openphc.cce.emitter.beckn.BecknTestSupport.SELF_ID;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.beckn.BecknTestSupport;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.beckn.CoordinationEvent;

/** CloudEvent → the extended sygnal:CoordinationEvent of profile v3.0, spec §5.2. */
class CoordinationEventMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CoordinationEventMapper eventMapper =
            new CoordinationEventMapper(BecknTestSupport.properties(true, "c", List.of()));

    private CloudEventDto event(String facilityId, String sourceEventId) throws Exception {
        return CloudEventDto.builder()
                .specversion("1.0").id("b2d6e8f0-1a3c-5e7f-9b1d-3f5a7c9e1b3d").source("openmrs-kicukiro")
                .type("Encounter").subject("1198503150001234").time("2026-11-13T08:00:36Z")
                .datacontenttype("application/fhir+json").facilityid(facilityId).sourceeventid(sourceEventId)
                .correlationid("corr").data(mapper.readTree("{\"resourceType\":\"Encounter\",\"id\":\"e1\"}"))
                .build();
    }

    @Test
    @DisplayName("maps every field of the event from the CloudEvent, the HIE's own id and the fixed values")
    void mapsFields() throws Exception {
        CloudEventDto ce = event("0002", "3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10");
        CoordinationEvent e = eventMapper.toCoordinationEvent(ce);

        assertThat(e.type()).isEqualTo("sygnal:CoordinationEvent");
        assertThat(e.id()).isEqualTo("b2d6e8f0-1a3c-5e7f-9b1d-3f5a7c9e1b3d");
        assertThat(e.revision()).isEqualTo(1);
        assertThat(e.eventType()).isEqualTo("DELIVERY_RECORDED");
        assertThat(e.granularity()).isEqualTo("INDIVIDUAL");
        assertThat(e.from()).isEqualTo(new CoordinationEvent.From(SELF_ID, "PROVIDER"));
        assertThat(e.occurredAt()).isEqualTo("2026-11-13T08:00:36Z");
        assertThat(e.patientId()).isEqualTo("1198503150001234");
        assertThat(e.fhirVersion()).isEqualTo("4.0.1");
        assertThat(e.facilityId()).isEqualTo("0002");
        assertThat(e.facilityName()).isNull();
        assertThat(e.sourceEventId()).isEqualTo("3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10");
        assertThat(e.resource()).isSameAs(ce.getData());
    }

    @Test
    @DisplayName("serializes to exactly the event of the contract: these fields, in this order, and no other")
    void serialization() throws Exception {
        JsonNode json = mapper.valueToTree(eventMapper.toCoordinationEvent(event("0002", "src-1")));

        assertThat(json).isEqualTo(mapper.readTree("""
                {
                  "@type": "sygnal:CoordinationEvent",
                  "id": "b2d6e8f0-1a3c-5e7f-9b1d-3f5a7c9e1b3d",
                  "revision": 1,
                  "eventType": "DELIVERY_RECORDED",
                  "granularity": "INDIVIDUAL",
                  "from": { "id": "bap.openhim.example.org", "role": "PROVIDER" },
                  "occurredAt": "2026-11-13T08:00:36Z",
                  "patientId": "1198503150001234",
                  "fhirVersion": "4.0.1",
                  "facilityId": "0002",
                  "sourceEventId": "src-1",
                  "resource": { "resourceType": "Encounter", "id": "e1" }
                }
                """));
        assertThat(json.fieldNames()).toIterable().containsExactly("@type", "id", "revision", "eventType",
                "granularity", "from", "occurredAt", "patientId", "fhirVersion", "facilityId", "sourceEventId",
                "resource");
        assertThat(json.path("from").fieldNames()).toIterable().containsExactly("id", "role");
        assertThat(json.path("revision").isInt()).isTrue();
    }

    @Test
    @DisplayName("optional fields that are null or blank are left out, not sent as null or \"\"")
    void optionalFieldsLeftOut() throws Exception {
        for (String blank : new String[] {null, "", "  "}) {
            JsonNode json = mapper.valueToTree(eventMapper.toCoordinationEvent(event(blank, blank)));
            assertThat(json.fieldNames()).toIterable().containsExactly("@type", "id", "revision", "eventType",
                    "granularity", "from", "occurredAt", "patientId", "fhirVersion", "resource");
            assertThat(json.toString()).doesNotContain("null", "\"\"");
        }
    }

    @Test
    @DisplayName("nothing of profile v1.0 or v2.0: no @context, no eventAttributes bag, no cce:CceClinicalEvent")
    void nothingOfTheOlderProfiles() throws Exception {
        String json = mapper.writeValueAsString(eventMapper.toCoordinationEvent(event("0002", "src-1")));
        assertThat(json).doesNotContain("@context", "eventAttributes", "CceClinicalEvent", "cce:");
    }
}
