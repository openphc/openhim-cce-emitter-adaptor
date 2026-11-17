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

/** CloudEvent → CceClinicalEvent box, spec §5.2. */
class CceClinicalEventMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CceClinicalEventMapper boxMapper =
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
    @DisplayName("maps every box field from the CloudEvent")
    void mapsFields() throws Exception {
        CloudEventDto ce = event("0002", "3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10");
        CceClinicalEvent box = boxMapper.toBox(ce);

        assertThat(box.context()).isEqualTo(
                "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v1.0/context.jsonld");
        assertThat(box.type()).isEqualTo("cce:CceClinicalEvent");
        assertThat(box.eventType()).isEqualTo("Encounter");
        assertThat(box.patientId()).isEqualTo("1198503150001234");
        assertThat(box.facilityId()).isEqualTo("0002");
        assertThat(box.facilityName()).isNull();
        assertThat(box.fhirVersion()).isEqualTo("4.0.1");
        assertThat(box.sourceEventId()).isEqualTo("3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10");
        assertThat(box.resource()).isSameAs(ce.getData());
    }

    @Test
    @DisplayName("serializes with @context/@type and leaves out null fields")
    void serialization() throws Exception {
        JsonNode json = mapper.valueToTree(boxMapper.toBox(event(null, null)));

        assertThat(json.has("@context")).isTrue();
        assertThat(json.path("@type").asText()).isEqualTo("cce:CceClinicalEvent");
        assertThat(json.has("facilityId")).isFalse();
        assertThat(json.has("facilityName")).isFalse();
        assertThat(json.has("sourceEventId")).isFalse();
        assertThat(json.path("resource").path("resourceType").asText()).isEqualTo("Encounter");
        // Fields of the CloudEvent that the box does not carry
        assertThat(json.has("source")).isFalse();
        assertThat(json.has("time")).isFalse();
        assertThat(json.has("correlationid")).isFalse();
    }
}
