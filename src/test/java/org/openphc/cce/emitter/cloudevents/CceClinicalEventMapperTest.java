package org.openphc.cce.emitter.cloudevents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.beckn.BecknTestSupport;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.beckn.CceClinicalEvent;

/** CloudEvent → cce:CceClinicalEvent, spec §5.2. */
class CceClinicalEventMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CceClinicalEventMapper eventMapper =
            new CceClinicalEventMapper(BecknTestSupport.properties(true, "c", List.of()));

    private CloudEventDto event(String facilityId, String sourceEventId) throws Exception {
        return CloudEventDto.builder()
                .specversion("1.0").id("b2d6e8f0-1a3c-5e7f-9b1d-3f5a7c9e1b3d").source("openmrs-kicukiro")
                .type("Encounter").subject("1198503150001234").time("2026-11-13T08:00:36Z")
                .datacontenttype("application/fhir+json").facilityid(facilityId).sourceeventid(sourceEventId)
                .correlationid("corr").data(mapper.readTree("{\"resourceType\":\"Encounter\",\"id\":\"e1\"}"))
                .build();
    }

    @Test
    @DisplayName("maps every field of the clinical event from the CloudEvent")
    void mapsFields() throws Exception {
        CloudEventDto ce = event("0002", "3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10");
        CceClinicalEvent e = eventMapper.toClinicalEvent(ce);

        assertThat(e.context()).isEqualTo(
                "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v2.0/context.jsonld");
        assertThat(e.type()).isEqualTo("cce:CceClinicalEvent");
        assertThat(e.id()).isEqualTo("b2d6e8f0-1a3c-5e7f-9b1d-3f5a7c9e1b3d");
        assertThat(e.occurredAt()).isEqualTo("2026-11-13T08:00:36Z");
        assertThat(e.patientId()).isEqualTo("1198503150001234");
        assertThat(e.fhirVersion()).isEqualTo("4.0.1");
        assertThat(e.facilityId()).isEqualTo("0002");
        assertThat(e.facilityName()).isNull();
        assertThat(e.sourceEventId()).isEqualTo("3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10");
        assertThat(e.resource()).isSameAs(ce.getData());
    }

    @Test
    @DisplayName("serializes to the closed v2.0 object: these fields, in this order, and no other")
    void serialization() throws Exception {
        JsonNode json = mapper.valueToTree(eventMapper.toClinicalEvent(event("0002", "src-1")));

        assertThat(json).isEqualTo(mapper.readTree("""
                {
                  "@context": "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v2.0/context.jsonld",
                  "@type": "cce:CceClinicalEvent",
                  "id": "b2d6e8f0-1a3c-5e7f-9b1d-3f5a7c9e1b3d",
                  "occurredAt": "2026-11-13T08:00:36Z",
                  "patientId": "1198503150001234",
                  "fhirVersion": "4.0.1",
                  "facilityId": "0002",
                  "sourceEventId": "src-1",
                  "resource": { "resourceType": "Encounter", "id": "e1" }
                }
                """));
        assertThat(json.fieldNames()).toIterable().containsExactly("@context", "@type", "id", "occurredAt",
                "patientId", "fhirVersion", "facilityId", "sourceEventId", "resource");
    }

    @Test
    @DisplayName("optional fields that are null or blank are left out, not sent as null or \"\"")
    void optionalFieldsLeftOut() throws Exception {
        for (String blank : new String[] {null, "", "  "}) {
            JsonNode json = mapper.valueToTree(eventMapper.toClinicalEvent(event(blank, blank)));
            assertThat(json.fieldNames()).toIterable().containsExactly("@context", "@type", "id", "occurredAt",
                    "patientId", "fhirVersion", "resource");
        }
    }

    @Test
    @DisplayName("the schema base URL may end with a slash")
    void contextUrlWithTrailingSlash() {
        var props = BecknTestSupport.properties(true, "c", List.of());
        var withSlash = new org.openphc.cce.emitter.config.BecknProperties(props.enabled(), props.schemaBaseUrl() + "/",
                props.onix(), props.self(), props.cce(), props.openhimChannel(), props.subscribers());
        assertThat(new CceClinicalEventMapper(withSlash).contextUrl()).isEqualTo(BecknTestSupport.EVENT_CONTEXT);
    }
}
