package org.openphc.cce.emitter.model.beckn;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One {@code cce:CceClinicalEvent}: the single entry of {@code message.events} of a
 * {@code sygnal/event/report} (schema in {@code cce-beckn-service}'s {@code schema/CceClinicalEvent/v2.0}).
 * The object is closed: it has these fields and no other. Optional fields are left out when null.
 *
 * @param id         the event id, a UUID; the same on every hop and every retry
 * @param occurredAt when the clinical event happened (ISO-8601)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"@context", "@type", "id", "occurredAt", "patientId", "fhirVersion", "facilityId",
        "facilityName", "sourceEventId", "resource"})
public record CceClinicalEvent(
        @JsonProperty("@context") String context,
        @JsonProperty("@type") String type,
        String id,
        String occurredAt,
        String patientId,
        String fhirVersion,
        String facilityId,
        String facilityName,
        String sourceEventId,
        JsonNode resource
) {
    public static final String TYPE = "cce:CceClinicalEvent";
    public static final String FHIR_VERSION = "4.0.1";
    /** Appended to {@code beckn.schema-base-url} to give the event's {@code @context}. */
    public static final String CONTEXT_PATH = "/CceClinicalEvent/v2.0/context.jsonld";
}
