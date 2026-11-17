package org.openphc.cce.emitter.model.beckn;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code CceClinicalEvent} box: {@code performance[0].performanceAttributes} of an
 * {@code update} (schema in {@code cce-beckn-service}'s {@code schema/CceClinicalEvent/v1.0}).
 * Optional fields are left out when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"@context", "@type", "eventType", "patientId", "facilityId", "facilityName",
        "fhirVersion", "sourceEventId", "resource"})
public record CceClinicalEvent(
        @JsonProperty("@context") String context,
        @JsonProperty("@type") String type,
        String eventType,
        String patientId,
        String facilityId,
        String facilityName,
        String fhirVersion,
        String sourceEventId,
        JsonNode resource
) {
    public static final String TYPE = "cce:CceClinicalEvent";
    public static final String FHIR_VERSION = "4.0.1";
}
