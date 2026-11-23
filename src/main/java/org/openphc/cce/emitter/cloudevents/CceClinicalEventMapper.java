package org.openphc.cce.emitter.cloudevents;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.beckn.CceClinicalEvent;
import org.springframework.stereotype.Component;

/**
 * Maps a built CloudEvent to the {@code cce:CceClinicalEvent} that a {@code sygnal/event/report}
 * carries (spec §5.2).
 *
 * <ul>
 *   <li>{@code id} ← {@code id}: for an event that came in over Sygnal, the id the HMIS sent</li>
 *   <li>{@code occurredAt} ← {@code time}</li>
 *   <li>{@code patientId} ← {@code subject}</li>
 *   <li>{@code fhirVersion} — always {@code 4.0.1}</li>
 *   <li>{@code facilityId} ← {@code facilityid}, left out when blank</li>
 *   <li>{@code facilityName} — left out: the CloudEvent has none (spec §9.3)</li>
 *   <li>{@code sourceEventId} ← {@code sourceeventid}, left out when blank</li>
 *   <li>{@code resource} ← {@code data}, the redacted FHIR resource</li>
 * </ul>
 *
 * The CloudEvent {@code type} (the FHIR resource type) has no field of its own: CCE reads it from
 * {@code resource.resourceType}.
 */
@Component
public class CceClinicalEventMapper {

    private final BecknProperties props;

    public CceClinicalEventMapper(BecknProperties props) {
        this.props = props;
    }

    public CceClinicalEvent toClinicalEvent(CloudEventDto event) {
        return new CceClinicalEvent(
                contextUrl(),
                CceClinicalEvent.TYPE,
                event.getId(),
                blankToNull(event.getTime()),
                event.getSubject(),
                CceClinicalEvent.FHIR_VERSION,
                blankToNull(event.getFacilityid()),
                null,
                blankToNull(event.getSourceeventid()),
                event.getData());
    }

    String contextUrl() {
        String base = props.schemaBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + CceClinicalEvent.CONTEXT_PATH;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
