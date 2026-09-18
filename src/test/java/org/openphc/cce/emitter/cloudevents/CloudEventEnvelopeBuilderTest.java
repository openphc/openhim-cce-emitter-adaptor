package org.openphc.cce.emitter.cloudevents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.exception.FhirMappingException;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.SourceMetadata;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CloudEventEnvelopeBuilder}.
 */
class CloudEventEnvelopeBuilderTest {

    /**
     * Production-equivalent redactor: these tests exercise envelope building and adaptor routing,
     * both of which must behave identically with redaction switched on (as it is in production).
     */
    private static org.openphc.cce.emitter.redaction.ClinicalDataRedactor testRedactor() {
        return new org.openphc.cce.emitter.redaction.ClinicalDataRedactor(
                new org.openphc.cce.emitter.redaction.ClinicalDataRedactionProperties(true, null),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private CloudEventEnvelopeBuilder builder;
    private ObjectMapper objectMapper;

    private static final String FHIR_ENCOUNTER_JSON = """
            {
              "resourceType": "Encounter",
              "id": "enc-001",
              "status": "in-progress",
              "class": {
                "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                "code": "AMB"
              },
              "subject": {
                "reference": "Patient/260225-0002-5501"
              }
            }
            """;

    private static final OffsetDateTime EVENT_TIME =
            OffsetDateTime.of(2026, 2, 25, 8, 0, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        EventIdGenerator idGenerator = new EventIdGenerator();
        builder = new CloudEventEnvelopeBuilder(idGenerator, objectMapper, testRedactor());
    }

    // --- Full envelope construction ---

    @Test
    void build_withAllFields_populatesAllCloudEventFields() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", "0002", "enc-visit-001", "corr-123",
                EVENT_TIME, "/inbound");

        CloudEventDto event = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);

        assertThat(event.getSpecversion()).isEqualTo("1.0");
        assertThat(event.getId()).isNotNull().isNotBlank();
        assertThat(event.getSource()).isEqualTo("ebuzima");
        assertThat(event.getType()).isEqualTo("Encounter");
        assertThat(event.getSubject()).isEqualTo("260225-0002-5501");
        assertThat(event.getTime()).isEqualTo("2026-02-25T08:00:00Z");
        assertThat(event.getDatacontenttype()).isEqualTo("application/fhir+json");
        assertThat(event.getFacilityid()).isEqualTo("0002");
        assertThat(event.getSourceeventid()).isEqualTo("enc-visit-001");
        assertThat(event.getCorrelationid()).isEqualTo("corr-123");
        assertThat(event.getData()).isNotNull();
    }

    // --- Type field: resourceType as-is ---

    @Test
    void build_setsTypeToResourceTypeAsIs() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", null, null, null,
                EVENT_TIME, "/inbound");

        CloudEventDto event = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);

        assertThat(event.getType()).isEqualTo("Encounter");
    }

    @Test
    void build_observationType_setsTypeToObservation() {
        String observationJson = """
                {
                  "resourceType": "Observation",
                  "id": "obs-001",
                  "status": "final",
                  "code": {"coding": [{"system": "http://loinc.org", "code": "8867-4"}]},
                  "subject": {"reference": "Patient/260225-0002-5501"}
                }
                """;
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", null, null, null,
                EVENT_TIME, "/inbound");

        CloudEventDto event = builder.build(observationJson, "260225-0002-5501", "Observation", meta);

        assertThat(event.getType()).isEqualTo("Observation");
    }

    // --- Data field ---

    @Test
    void build_dataFieldContainsFhirResource() throws Exception {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", null, null, null,
                EVENT_TIME, "/inbound");

        CloudEventDto event = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);

        JsonNode data = event.getData();
        assertThat(data.get("resourceType").asText()).isEqualTo("Encounter");
        assertThat(data.get("id").asText()).isEqualTo("enc-001");
        assertThat(data.get("status").asText()).isEqualTo("in-progress");
        assertThat(data.get("subject").get("reference").asText()).isEqualTo("Patient/260225-0002-5501");
    }

    // --- Deterministic ID with sourceEventId ---

    @Test
    void build_withSourceEventId_generatesDeterministicId() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", "0002", "enc-visit-001", "corr-123",
                EVENT_TIME, "/inbound");

        CloudEventDto event1 = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);
        CloudEventDto event2 = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);

        assertThat(event1.getId()).isEqualTo(event2.getId());
    }

    // --- Random ID without sourceEventId ---

    @Test
    void build_withoutSourceEventId_generatesRandomId() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", "0002", null, "corr-123",
                EVENT_TIME, "/inbound");

        CloudEventDto event1 = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);
        CloudEventDto event2 = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);

        assertThat(event1.getId()).isNotEqualTo(event2.getId());
    }

    // --- Nullable fields ---

    @Test
    void build_withNullOptionalFields_leavesFieldsNull() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", null, null, null,
                EVENT_TIME, "/inbound");

        CloudEventDto event = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);

        assertThat(event.getFacilityid()).isNull();
        assertThat(event.getSourceeventid()).isNull();
        assertThat(event.getCorrelationid()).isNull();
        // Protocol fields should always be null (set by Compliance Service)
        assertThat(event.getProtocolinstanceid()).isNull();
        assertThat(event.getProtocoldefinitionid()).isNull();
        assertThat(event.getActionid()).isNull();
    }

    @Test
    void build_withNullEventTime_setsTimeToNull() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", null, null, null,
                null, "/inbound");

        CloudEventDto event = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);

        assertThat(event.getTime()).isNull();
    }

    // --- Error cases ---

    @Test
    void build_invalidJson_throwsFhirMappingException() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", null, null, null,
                EVENT_TIME, "/inbound");

        assertThatThrownBy(() -> builder.build("not valid json", "260225-0002-5501", "Encounter", meta))
                .isInstanceOf(FhirMappingException.class)
                .hasMessageContaining("Failed to parse FHIR JSON");
    }

    // --- Specversion is always 1.0 ---

    @Test
    void build_alwaysSetsSpecversion() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", null, null, null,
                EVENT_TIME, "/inbound");

        CloudEventDto event = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);

        assertThat(event.getSpecversion()).isEqualTo("1.0");
    }

    // --- Datacontenttype is always application/fhir+json ---

    @Test
    void build_alwaysSetsDataContentType() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", null, null, null,
                EVENT_TIME, "/inbound");

        CloudEventDto event = builder.build(FHIR_ENCOUNTER_JSON, "260225-0002-5501", "Encounter", meta);

        assertThat(event.getDatacontenttype()).isEqualTo("application/fhir+json");
    }
}
