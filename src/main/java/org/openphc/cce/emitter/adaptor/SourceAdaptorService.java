package org.openphc.cce.emitter.adaptor;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.openphc.cce.emitter.cloudevents.CloudEventEnvelopeBuilder;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.SourceProperties;
import org.openphc.cce.emitter.fhir.FacilityIdExtractor;
import org.openphc.cce.emitter.fhir.FhirResourceParser;
import org.openphc.cce.emitter.fhir.PatientIdExtractor;
import org.openphc.cce.emitter.filter.FacilityFilter;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.InboundRequest;
import org.openphc.cce.emitter.model.SourceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves the source system for an inbound request and transforms
 * FHIR R4 resources into CloudEvents for forwarding to the CCE Collector.
 *
 * <p>Source systems are configured in {@code cce.emitter.sources} — each entry
 * maps a source key (e.g., {@code "ebuzima"}) to its OpenHIM client ID. Since
 * all configured source systems send FHIR R4 resources, the same parsing and
 * CloudEvent construction logic applies to every source — no per-source subclass
 * is needed.
 *
 * <h3>Source matching priority</h3>
 * <ol>
 *   <li>{@code X-OpenHIM-ClientID} header matches a configured {@code clientId}</li>
 *   <li>{@code X-Source-System} header matches a source key (case-insensitive)</li>
 * </ol>
 *
 * <p>If no source matches, the request is silently ignored (returns empty).
 * Bundle resources are silently ignored (out of scope for v1.0).
 */
@Component
public class SourceAdaptorService {

    private static final Logger log = LoggerFactory.getLogger(SourceAdaptorService.class);

    private static final String HEADER_OPENHIM_CLIENT_ID = "x-openhim-clientid";
    private static final String HEADER_OPENHIM_TRANSACTION_ID = "x-openhim-transactionid";
    private static final String HEADER_SOURCE_SYSTEM = "x-source-system";
    private static final String HEADER_FACILITY_ID = "x-facility-id";
    private static final String HEADER_SOURCE_EVENT_ID = "x-source-event-id";
    private static final String HEADER_CORRELATION_ID = "x-correlation-id";
    private static final String HEADER_SYGNAL_EVENT_ID = "x-sygnal-event-id";
    private static final String HEADER_SYGNAL_OCCURRED_AT = "x-sygnal-occurred-at";

    // UUID.fromString() accepts short forms such as "1-1-1-1-1"; the Sygnal event id is a full UUID.
    private static final Pattern UUID_FORM =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final Map<String, SourceProperties> sources;
    private final FhirResourceParser fhirResourceParser;
    private final PatientIdExtractor patientIdExtractor;
    private final FacilityIdExtractor facilityIdExtractor;
    private final FacilityFilter facilityFilter;
    private final CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder;

    public SourceAdaptorService(
            EmitterProperties emitterProperties,
            FhirResourceParser fhirResourceParser,
            PatientIdExtractor patientIdExtractor,
            FacilityIdExtractor facilityIdExtractor,
            FacilityFilter facilityFilter,
            CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder) {
        this.sources = emitterProperties.sources() != null ? emitterProperties.sources() : Map.of();
        this.fhirResourceParser = fhirResourceParser;
        this.patientIdExtractor = patientIdExtractor;
        this.facilityIdExtractor = facilityIdExtractor;
        this.facilityFilter = facilityFilter;
        this.cloudEventEnvelopeBuilder = cloudEventEnvelopeBuilder;

        if (sources.isEmpty()) {
            log.warn("No source systems configured under cce.emitter.sources — "
                    + "all inbound requests will be silently ignored");
        } else {
            sources.forEach((key, props) ->
                    log.info("Configured source: key='{}', clientId='{}'", key, props.clientId()));
        }
    }

    /**
     * Resolves the source system for the given request and transforms the
     * FHIR payload into CloudEvents.
     *
     * @param request the normalized inbound request
     * @return a list of CloudEvents (single element for an individual resource,
     *         empty if no source matched, payload is non-FHIR, or it is a Bundle)
     */
    public List<CloudEventDto> adapt(InboundRequest request) {
        Optional<String> sourceKey = resolveSource(request);

        if (sourceKey.isEmpty()) {
            log.debug("No source matched — request will be silently ignored");
            return List.of();
        }

        return transform(request, sourceKey.get());
    }

    // ==================== Source resolution ====================

    /**
     * Resolves which configured source system matches the inbound request.
     *
     * <p>Priority:
     * <ol>
     *   <li>{@code X-OpenHIM-ClientID} header → match against configured client IDs</li>
     *   <li>{@code X-Source-System} header → match against source keys (case-insensitive)</li>
     * </ol>
     *
     * @param request the inbound request
     * @return the matching source key, or empty if no match
     */
    Optional<String> resolveSource(InboundRequest request) {
        // Priority 1: X-OpenHIM-ClientID header
        Optional<String> clientIdHeader = request.getHeader(HEADER_OPENHIM_CLIENT_ID);
        if (clientIdHeader.isPresent()) {
            for (Map.Entry<String, SourceProperties> entry : sources.entrySet()) {
                if (clientIdHeader.get().equals(entry.getValue().clientId())) {
                    log.debug("Source '{}' matched by X-OpenHIM-ClientID header", entry.getKey());
                    return Optional.of(entry.getKey());
                }
            }
        }

        // Priority 2: X-Source-System header
        Optional<String> sourceSystemHeader = request.getHeader(HEADER_SOURCE_SYSTEM);
        if (sourceSystemHeader.isPresent()) {
            for (String key : sources.keySet()) {
                if (sourceSystemHeader.get().equalsIgnoreCase(key)) {
                    log.debug("Source '{}' matched by X-Source-System header", key);
                    return Optional.of(key);
                }
            }
        }

        return Optional.empty();
    }

    // ==================== FHIR → CloudEvent transformation ====================

    private List<CloudEventDto> transform(InboundRequest request, String sourceKey) {
        String body = request.getBody();

        if (!request.containsFhirResource()) {
            log.debug("Request body does not contain a FHIR resource — skipping");
            return List.of();
        }

        // Parse FHIR resource
        IBaseResource resource = fhirResourceParser.parse(body);

        // Silently ignore Bundle resources (out of scope for v1.0)
        if (resource instanceof Bundle) {
            log.debug("Bundle resource received — silently ignoring (out of scope for v1.0)");
            return List.of();
        }

        String resourceType = resource.fhirType();
        String patientUpid = patientIdExtractor.extract(resource);

        // Build source metadata from headers, with FHIR location fallback for facility
        SourceMetadata metadata = buildSourceMetadata(request, sourceKey, resource);

        // Facility filter — throws FacilityFilterRejectedException (→ 403) if denied
        facilityFilter.enforceFilter(metadata.facilityId(), sourceKey);
        // Build CloudEvent
        CloudEventDto event = cloudEventEnvelopeBuilder.build(body, patientUpid, resourceType, metadata);

        log.info("Adapted {} resource from '{}' → CloudEvent id={}, subject={}",
                resourceType, sourceKey, event.getId(), event.getSubject());

        return List.of(event);
    }

    /**
     * Builds {@link SourceMetadata} from inbound request headers.
     *
     * <p>Facility ID resolution priority:
     * <ol>
     *   <li>{@code X-Facility-Id} header — explicit facility from the source system</li>
     *   <li>FHIR resource {@code location[0].location.reference} — extracted from Encounter payload</li>
     * </ol>
     *
     * <p>Correlation ID priority chain:
     * <ol>
     *   <li>{@code X-OpenHIM-TransactionID} — OpenHIM Core's transaction ID (preferred — links to OpenHIM transaction log)</li>
     *   <li>{@code X-Correlation-Id} — explicit correlation header from the source system</li>
     *   <li>Generated UUID — fallback when neither header is present</li>
     * </ol>
     *
     * <p>An event that came in over Sygnal also carries {@code X-Sygnal-Event-Id} (kept as the
     * CloudEvent {@code id}, when it is a UUID) and {@code X-Sygnal-Occurred-At} (the CloudEvent
     * {@code time}, when it parses; otherwise the time of receipt).
     */
    SourceMetadata buildSourceMetadata(InboundRequest request, String sourceKey, IBaseResource resource) {
        String facilityId = request.getHeader(HEADER_FACILITY_ID).orElse(null);

        // Fallback: extract facility from FHIR resource location when header is absent
        if (facilityId == null && resource != null) {
            facilityId = facilityIdExtractor.extract(resource);
            if (facilityId != null) {
                log.info("Facility ID '{}' extracted from FHIR resource (no X-Facility-Id header)", facilityId);
            }
        }

        String sourceEventId = request.getHeader(HEADER_SOURCE_EVENT_ID).orElse(null);
        String correlationId = request.getHeader(HEADER_OPENHIM_TRANSACTION_ID)
                .or(() -> request.getHeader(HEADER_CORRELATION_ID))
                .orElseGet(() -> UUID.randomUUID().toString());

        String eventId = request.getHeader(HEADER_SYGNAL_EVENT_ID)
                .map(String::strip)
                .filter(id -> UUID_FORM.matcher(id).matches())
                .orElse(null);
        OffsetDateTime eventTime = request.getHeader(HEADER_SYGNAL_OCCURRED_AT)
                .flatMap(SourceAdaptorService::parseTime)
                .orElseGet(() -> OffsetDateTime.now(ZoneOffset.UTC));

        return new SourceMetadata(
                sourceKey,
                facilityId,
                sourceEventId,
                correlationId,
                eventTime,
                request.getPath(),
                eventId
        );
    }

    private static Optional<OffsetDateTime> parseTime(String value) {
        try {
            return Optional.of(OffsetDateTime.parse(value.strip()));
        } catch (DateTimeParseException e) {
            log.warn("X-Sygnal-Occurred-At '{}' is not an ISO-8601 time; using the time of receipt", value);
            return Optional.empty();
        }
    }
}
