package org.openphc.cce.emitter.model.beckn;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One {@code sygnal:CoordinationEvent}, as CCE's profile of Sygnal extends it: the single entry of
 * {@code message.events} of a {@code sygnal/event/report} (schema in {@code cce-beckn-service}'s
 * {@code schema/sygnal-profile/v3.0/cce-sygnal-apis.yaml}). The fields up to {@code occurredAt} are
 * Sygnal's; the fields from {@code patientId} down are the ones CCE added to its copy of the type.
 * There is no {@code @context} and no {@code eventAttributes} bag.
 *
 * <p>The fields are written in the order below. An optional field without a value is left out: a
 * blank {@code occurredAt}, {@code facilityId}, {@code facilityName} or {@code sourceEventId} is
 * never sent as {@code null} or {@code ""}.
 *
 * @param type        always {@code sygnal:CoordinationEvent}
 * @param id          the event id, a UUID; the same on every hop and every retry
 * @param revision    always 1: CCE never replaces an event
 * @param eventType   Sygnal's kind of event, always {@code DELIVERY_RECORDED}. CCE's own kind of
 *                    event is {@code resource.resourceType}
 * @param granularity always {@code INDIVIDUAL}
 * @param from        the sender of the report
 * @param occurredAt  when the clinical event happened (ISO-8601)
 * @param patientId   the patient's UPID
 * @param fhirVersion always {@code 4.0.1}
 * @param resource    the FHIR resource
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"@type", "id", "revision", "eventType", "granularity", "from", "occurredAt", "patientId",
        "fhirVersion", "facilityId", "facilityName", "sourceEventId", "resource"})
public record CoordinationEvent(
        @JsonProperty("@type") String type,
        String id,
        int revision,
        String eventType,
        String granularity,
        From from,
        String occurredAt,
        String patientId,
        String fhirVersion,
        String facilityId,
        String facilityName,
        String sourceEventId,
        JsonNode resource
) {
    public static final String TYPE = "sygnal:CoordinationEvent";
    public static final int REVISION = 1;
    public static final String EVENT_TYPE = "DELIVERY_RECORDED";
    public static final String GRANULARITY = "INDIVIDUAL";
    public static final String ROLE = "PROVIDER";
    public static final String FHIR_VERSION = "4.0.1";

    public CoordinationEvent {
        occurredAt = blankToNull(occurredAt);
        facilityId = blankToNull(facilityId);
        facilityName = blankToNull(facilityName);
        sourceEventId = blankToNull(sourceEventId);
    }

    /**
     * The sender of the report.
     *
     * @param id   its subscriber id, equal to {@code context.senderId}
     * @param role always {@code PROVIDER}
     */
    @JsonPropertyOrder({"id", "role"})
    public record From(String id, String role) {}

    /**
     * A clinical event with the fixed values of the profile filled in: {@code @type},
     * {@code revision} 1, {@code eventType} {@code DELIVERY_RECORDED}, {@code granularity}
     * {@code INDIVIDUAL}, {@code from.role} {@code PROVIDER} and {@code fhirVersion} {@code 4.0.1}.
     *
     * @param senderId the subscriber id of the participant that sends the report ({@code from.id})
     */
    public static CoordinationEvent clinical(String id, String senderId, String occurredAt, String patientId,
                                             String facilityId, String facilityName, String sourceEventId,
                                             JsonNode resource) {
        return new CoordinationEvent(TYPE, id, REVISION, EVENT_TYPE, GRANULARITY, new From(senderId, ROLE),
                occurredAt, patientId, FHIR_VERSION, facilityId, facilityName, sourceEventId, resource);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
