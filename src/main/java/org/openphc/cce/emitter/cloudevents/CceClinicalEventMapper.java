package org.openphc.cce.emitter.cloudevents;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.beckn.CceClinicalEvent;
import org.springframework.stereotype.Component;

/**
 * Maps a built CloudEvent to the {@code CceClinicalEvent} box (spec §5.2). The event id of the
 * {@code sygnal/event/report} is the CloudEvent {@code id} and its {@code occurredAt} the CloudEvent
 * {@code time}; the box carries the rest.
 *
 * <ul>
 *   <li>{@code eventType} ← {@code type} (the FHIR resource type)</li>
 *   <li>{@code patientId} ← {@code subject}</li>
 *   <li>{@code facilityId} ← {@code facilityid}, left out when null</li>
 *   <li>{@code facilityName} — left out: the CloudEvent has none (spec §9.3)</li>
 *   <li>{@code sourceEventId} ← {@code sourceeventid}, left out when null</li>
 *   <li>{@code fhirVersion} — always {@code 4.0.1}</li>
 *   <li>{@code resource} ← {@code data}, the redacted FHIR resource</li>
 * </ul>
 */
@Component
public class CceClinicalEventMapper {

    private final BecknProperties props;

    public CceClinicalEventMapper(BecknProperties props) {
        this.props = props;
    }

    public CceClinicalEvent toBox(CloudEventDto event) {
        return new CceClinicalEvent(
                contextUrl(),
                CceClinicalEvent.TYPE,
                event.getType(),
                event.getSubject(),
                blankToNull(event.getFacilityid()),
                null,
                CceClinicalEvent.FHIR_VERSION,
                blankToNull(event.getSourceeventid()),
                event.getData());
    }

    String contextUrl() {
        String base = props.schemaBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/CceClinicalEvent/v1.0/context.jsonld";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
