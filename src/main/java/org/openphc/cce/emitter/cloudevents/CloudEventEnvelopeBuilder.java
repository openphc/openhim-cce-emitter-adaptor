package org.openphc.cce.emitter.cloudevents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openphc.cce.emitter.exception.FhirMappingException;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.SourceMetadata;
import org.openphc.cce.emitter.redaction.ClinicalDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Builds CloudEvents v1.0 envelopes from FHIR resource data and source metadata.
 *
 * <p>Populates all required, recommended, and extension fields per the CCE CloudEvents contract.
 * The {@code type} field is set to the FHIR {@code resourceType} value as-is (no normalization).
 */
@Component
public class CloudEventEnvelopeBuilder {

    private static final Logger log = LoggerFactory.getLogger(CloudEventEnvelopeBuilder.class);

    private static final String SPEC_VERSION = "1.0";
    private static final String DATA_CONTENT_TYPE = "application/fhir+json";

    private final EventIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ClinicalDataRedactor clinicalDataRedactor;

    public CloudEventEnvelopeBuilder(EventIdGenerator idGenerator,
                                     ObjectMapper objectMapper,
                                     ClinicalDataRedactor clinicalDataRedactor) {
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.clinicalDataRedactor = clinicalDataRedactor;
    }

    /**
     * Builds a CloudEvents v1.0 envelope for the given FHIR resource.
     *
     * @param fhirJson      the raw FHIR resource JSON string
     * @param patientUpid   the extracted patient UPID (CloudEvent {@code subject})
     * @param resourceType  the FHIR resource type (e.g., {@code "Encounter"}) — used as CloudEvent {@code type}
     * @param meta          the source metadata from inbound request processing
     * @return a fully populated {@link CloudEventDto}
     * @throws FhirMappingException if the FHIR JSON cannot be parsed into a JsonNode
     */
    public CloudEventDto build(String fhirJson, String patientUpid, String resourceType, SourceMetadata meta) {
        // Redaction happens here, at the point the payload is turned into the outbound event —
        // deliberately AFTER patient UPID, facility ID and clinical time have been extracted from
        // the complete resource upstream, so stripping clinical content cannot affect routing,
        // facility attribution or SLA timing. Everything persisted downstream is the minimised form.
        JsonNode data = clinicalDataRedactor.redact(parseToJsonNode(fhirJson));
        String eventId = idGenerator.generate(meta);
        String eventTime = meta.eventTime() != null
                ? meta.eventTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                : null;

        CloudEventDto event = CloudEventDto.builder()
                .specversion(SPEC_VERSION)
                .id(eventId)
                .source(meta.sourceIdentifier())
                .type(resourceType)
                .subject(patientUpid)
                .time(eventTime)
                .datacontenttype(DATA_CONTENT_TYPE)
                .facilityid(meta.facilityId())
                .sourceeventid(meta.sourceEventId())
                .correlationid(meta.correlationId())
                .data(data)
                .build();

        log.debug("Built CloudEvent: id={}, type={}, source={}, subject={}",
                eventId, resourceType, meta.sourceIdentifier(), patientUpid);

        return event;
    }

    /**
     * Parses the raw FHIR JSON string into a {@link JsonNode} for the CloudEvent {@code data} field.
     */
    private JsonNode parseToJsonNode(String fhirJson) {
        try {
            return objectMapper.readTree(fhirJson);
        } catch (JsonProcessingException e) {
            throw new FhirMappingException("Failed to parse FHIR JSON for CloudEvent data field: " + e.getMessage(), e);
        }
    }
}
