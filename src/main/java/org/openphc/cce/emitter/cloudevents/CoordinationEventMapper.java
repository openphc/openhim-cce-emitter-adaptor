package org.openphc.cce.emitter.cloudevents;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.beckn.CoordinationEvent;
import org.springframework.stereotype.Component;

/**
 * Maps a built CloudEvent to the extended {@code sygnal:CoordinationEvent} that a
 * {@code sygnal/event/report} carries (spec §5.2).
 *
 * <ul>
 *   <li>{@code id} ← {@code id}: for an event that came in over Sygnal, the id the HMIS sent</li>
 *   <li>{@code from.id} ← {@code beckn.self.id}, the HIE's own subscriber id (= {@code context.senderId})</li>
 *   <li>{@code occurredAt} ← {@code time}</li>
 *   <li>{@code patientId} ← {@code subject}</li>
 *   <li>{@code facilityId} ← {@code facilityid}, left out when blank</li>
 *   <li>{@code facilityName} — left out: the CloudEvent has none (spec §9.3)</li>
 *   <li>{@code sourceEventId} ← {@code sourceeventid}, left out when blank</li>
 *   <li>{@code resource} ← {@code data}, the redacted FHIR resource</li>
 * </ul>
 *
 * {@code @type}, {@code revision}, {@code eventType}, {@code granularity}, {@code from.role} and
 * {@code fhirVersion} are fixed values (see {@link CoordinationEvent#clinical}). The CloudEvent
 * {@code type} (the FHIR resource type) has no field of its own: CCE reads it from
 * {@code resource.resourceType}.
 */
@Component
public class CoordinationEventMapper {

    private final BecknProperties props;

    public CoordinationEventMapper(BecknProperties props) {
        this.props = props;
    }

    public CoordinationEvent toCoordinationEvent(CloudEventDto event) {
        return CoordinationEvent.clinical(
                event.getId(),
                props.self().id(),
                event.getTime(),
                event.getSubject(),
                event.getFacilityid(),
                null,
                event.getSourceeventid(),
                event.getData());
    }
}
