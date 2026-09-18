package org.openphc.cce.emitter.adaptor;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.cloudevents.CloudEventEnvelopeBuilder;
import org.openphc.cce.emitter.cloudevents.EventIdGenerator;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.SourceProperties;
import org.openphc.cce.emitter.exception.FacilityFilterRejectedException;
import org.openphc.cce.emitter.exception.FhirMappingException;
import org.openphc.cce.emitter.exception.PatientIdNotFoundException;
import org.openphc.cce.emitter.fhir.FacilityIdExtractor;
import org.openphc.cce.emitter.fhir.FhirResourceParser;
import org.openphc.cce.emitter.fhir.PatientIdExtractor;
import org.openphc.cce.emitter.filter.FacilityFilter;
import org.openphc.cce.emitter.filter.FacilityFilterProperties;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.InboundRequest;
import org.openphc.cce.emitter.model.SourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link SourceAdaptorService}.
 *
 * <p>Uses real {@link FhirResourceParser}, {@link PatientIdExtractor}, and
 * {@link CloudEventEnvelopeBuilder} instances (not mocks) to verify the
 * full FHIR-to-CloudEvent transformation pipeline.
 */
class SourceAdaptorServiceTest {

    /**
     * Production-equivalent redactor: these tests exercise envelope building and adaptor routing,
     * both of which must behave identically with redaction switched on (as it is in production).
     */
    private static org.openphc.cce.emitter.redaction.ClinicalDataRedactor testRedactor() {
        return new org.openphc.cce.emitter.redaction.ClinicalDataRedactor(
                new org.openphc.cce.emitter.redaction.ClinicalDataRedactionProperties(true, null),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static final String SOURCE_KEY = "ebuzima";
    private static final String CLIENT_ID = "ebuzima-emr-client";
    private static final String PATIENT_UPID = "260225-0002-5501";

    private SourceAdaptorService service;

    private String encounterJson;
    private String observationJson;
    private String bundleJson;

    @BeforeEach
    void setUp() throws IOException {
        FhirContext fhirContext = FhirContext.forR4();
        FhirResourceParser fhirResourceParser = new FhirResourceParser(fhirContext);
        FacilityIdExtractor facilityIdExtractor = new FacilityIdExtractor();
        ObjectMapper objectMapper = new ObjectMapper();
        EventIdGenerator idGenerator = new EventIdGenerator();
        CloudEventEnvelopeBuilder envelopeBuilder = new CloudEventEnvelopeBuilder(idGenerator, objectMapper, testRedactor());

        EmitterProperties emitterProperties = new EmitterProperties(
                Map.of(SOURCE_KEY, new SourceProperties(CLIENT_ID)),
                "http://openphc.org/identifier/upid");
        PatientIdExtractor patientIdExtractor = new PatientIdExtractor(emitterProperties);

        service = new SourceAdaptorService(
                emitterProperties,
                fhirResourceParser, patientIdExtractor, facilityIdExtractor,
                disabledFilter(), envelopeBuilder);

        encounterJson = loadFixture("ebuzima/fhir-encounter.json");
        observationJson = loadFixture("ebuzima/fhir-observation.json");
        bundleJson = loadFixture("ebuzima/fhir-bundle.json");
    }

    // ==================== resolveSource() ====================

    @Nested
    class ResolveSource {

        @Test
        void matchesByOpenHimClientIdHeader() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            assertThat(service.resolveSource(request)).contains(SOURCE_KEY);
        }

        @Test
        void matchesBySourceSystemHeader() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-Source-System", SOURCE_KEY),
                    "/inbound");

            assertThat(service.resolveSource(request)).contains(SOURCE_KEY);
        }

        @Test
        void sourceSystemHeaderIsCaseInsensitive() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-Source-System", "EBUZIMA"),
                    "/inbound");

            assertThat(service.resolveSource(request)).contains(SOURCE_KEY);
        }

        @Test
        void clientIdHeaderTakesPriorityOverSourceSystem() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Source-System", "wrong-source"),
                    "/inbound");

            assertThat(service.resolveSource(request)).contains(SOURCE_KEY);
        }

        @Test
        void noMatchingHeaders_returnsEmpty() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", "other-client"),
                    "/inbound");

            assertThat(service.resolveSource(request)).isEmpty();
        }

        @Test
        void noHeaders_returnsEmpty() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(),
                    "/inbound");

            assertThat(service.resolveSource(request)).isEmpty();
        }

        @Test
        void wrongClientId_fallsBackToSourceSystem() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", "wrong-client",
                            "X-Source-System", SOURCE_KEY),
                    "/inbound");

            assertThat(service.resolveSource(request)).contains(SOURCE_KEY);
        }
    }

    // ==================== resolveSource() — Multiple sources ====================

    @Nested
    class ResolveSourceMultipleSources {

        private SourceAdaptorService multiService;

        @BeforeEach
        void setUp() {
            FhirContext fhirContext = FhirContext.forR4();
            FhirResourceParser fhirResourceParser = new FhirResourceParser(fhirContext);
            ObjectMapper objectMapper = new ObjectMapper();
            EventIdGenerator idGenerator = new EventIdGenerator();
            CloudEventEnvelopeBuilder envelopeBuilder = new CloudEventEnvelopeBuilder(idGenerator, objectMapper, testRedactor());

            EmitterProperties emitterProperties = new EmitterProperties(Map.of(
                    "ebuzima", new SourceProperties("ebuzima-emr-client"),
                    "dhis2", new SourceProperties("dhis2-client")),
                    "http://openphc.org/identifier/upid");
            PatientIdExtractor patientIdExtractor = new PatientIdExtractor(emitterProperties);

            multiService = new SourceAdaptorService(
                    emitterProperties,
                    fhirResourceParser, patientIdExtractor, new FacilityIdExtractor(),
                    disabledFilter(), envelopeBuilder);
            InboundRequest request = InboundRequest.from(
                    "{}", Map.of("X-OpenHIM-ClientID", "dhis2-client"), "/inbound");

            assertThat(multiService.resolveSource(request)).contains("dhis2");
        }

        @Test
        void matchesCorrectSourceBySourceSystem() {
            InboundRequest request = InboundRequest.from(
                    "{}", Map.of("X-Source-System", "dhis2"), "/inbound");

            assertThat(multiService.resolveSource(request)).contains("dhis2");
        }

        @Test
        void noMatchAcrossAllSources_returnsEmpty() {
            InboundRequest request = InboundRequest.from(
                    "{}", Map.of("X-OpenHIM-ClientID", "unknown-client"), "/inbound");

            assertThat(multiService.resolveSource(request)).isEmpty();
        }
    }

    // ==================== resolveSource() — No sources configured ====================

    @Nested
    class ResolveSourceNoSources {

        @Test
        void emptySourcesConfig_returnsEmpty() {
            FhirContext fhirContext = FhirContext.forR4();
            FhirResourceParser fhirResourceParser = new FhirResourceParser(fhirContext);
            ObjectMapper objectMapper = new ObjectMapper();
            EventIdGenerator idGenerator = new EventIdGenerator();
            CloudEventEnvelopeBuilder envelopeBuilder = new CloudEventEnvelopeBuilder(idGenerator, objectMapper, testRedactor());

            EmitterProperties emitterProperties = new EmitterProperties(Map.of(),
                    "http://openphc.org/identifier/upid");
            PatientIdExtractor patientIdExtractor = new PatientIdExtractor(emitterProperties);
            SourceAdaptorService emptyService = new SourceAdaptorService(
                    emitterProperties,
                    fhirResourceParser, patientIdExtractor, new FacilityIdExtractor(),
                    disabledFilter(), envelopeBuilder);

            InboundRequest request = InboundRequest.from(
                    "{}", Map.of("X-OpenHIM-ClientID", CLIENT_ID), "/inbound");

            assertThat(emptyService.resolveSource(request)).isEmpty();
        }
    }

    // ==================== adapt() — Encounter ====================

    @Nested
    class AdaptEncounter {

        @Test
        void encounter_producesSingleCloudEvent() {
            InboundRequest request = buildRequest(encounterJson);

            List<CloudEventDto> events = service.adapt(request);

            assertThat(events).hasSize(1);
        }

        @Test
        void encounter_hasCorrectType() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getType()).isEqualTo("Encounter");
        }

        @Test
        void encounter_hasCorrectSubject() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getSubject()).isEqualTo(PATIENT_UPID);
        }

        @Test
        void encounter_hasCorrectSource() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getSource()).isEqualTo(SOURCE_KEY);
        }

        @Test
        void encounter_hasSpecVersion() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getSpecversion()).isEqualTo("1.0");
        }

        @Test
        void encounter_hasDataContentType() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getDatacontenttype()).isEqualTo("application/fhir+json");
        }

        @Test
        void encounter_hasEventId() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getId()).isNotNull().isNotBlank();
        }

        @Test
        void encounter_hasTimestamp() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getTime()).isNotNull().isNotBlank();
        }

        @Test
        void encounter_hasDataPayload() {
            InboundRequest request = buildRequest(encounterJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getData()).isNotNull();
            assertThat(event.getData().get("resourceType").asText()).isEqualTo("Encounter");
        }
    }

    // ==================== adapt() — Observation ====================

    @Nested
    class AdaptObservation {

        @Test
        void observation_producesSingleCloudEvent() {
            InboundRequest request = buildRequest(observationJson);

            List<CloudEventDto> events = service.adapt(request);

            assertThat(events).hasSize(1);
        }

        @Test
        void observation_hasCorrectType() {
            InboundRequest request = buildRequest(observationJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getType()).isEqualTo("Observation");
        }

        @Test
        void observation_hasCorrectSubject() {
            InboundRequest request = buildRequest(observationJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getSubject()).isEqualTo(PATIENT_UPID);
        }

        @Test
        void observation_dataContainsObservationResource() {
            InboundRequest request = buildRequest(observationJson);

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getData().get("resourceType").asText()).isEqualTo("Observation");
            assertThat(event.getData().get("id").asText()).isEqualTo("obs-lab-hb-001");
        }
    }

    // ==================== adapt() — Bundle (silently ignored) ====================

    @Nested
    class AdaptBundle {

        @Test
        void bundle_returnsEmptyList() {
            InboundRequest request = buildRequest(bundleJson);

            List<CloudEventDto> events = service.adapt(request);

            assertThat(events).isEmpty();
        }
    }

    // ==================== adapt() — Non-FHIR payload ====================

    @Nested
    class AdaptNonFhir {

        @Test
        void nonFhirPayload_returnsEmptyList() {
            InboundRequest request = InboundRequest.from(
                    "{\"message\": \"hello\"}",
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            List<CloudEventDto> events = service.adapt(request);

            assertThat(events).isEmpty();
        }

        @Test
        void nullBody_returnsEmptyList() {
            InboundRequest request = InboundRequest.from(
                    null,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            List<CloudEventDto> events = service.adapt(request);

            assertThat(events).isEmpty();
        }
    }

    // ==================== adapt() — No source match ====================

    @Nested
    class AdaptNoSourceMatch {

        @Test
        void noSourceMatch_returnsEmptyList() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", "unknown-client"),
                    "/inbound");

            List<CloudEventDto> events = service.adapt(request);

            assertThat(events).isEmpty();
        }
    }

    // ==================== adapt() — Malformed FHIR ====================

    @Nested
    class AdaptMalformedFhir {

        @Test
        void malformedFhirJson_throwsFhirMappingException() {
            String malformed = "{\"resourceType\": \"Encounter\", \"invalidField\": [}";
            InboundRequest request = InboundRequest.from(
                    malformed,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            assertThatThrownBy(() -> service.adapt(request))
                    .isInstanceOf(FhirMappingException.class);
        }
    }

    // ==================== adapt() — Missing patient reference ====================

    @Nested
    class AdaptMissingPatient {

        @Test
        void encounterWithoutSubject_throwsPatientIdNotFoundException() {
            String noSubjectEncounter = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-no-subject",
                      "status": "in-progress",
                      "class": {
                        "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                        "code": "AMB"
                      }
                    }
                    """;
            InboundRequest request = InboundRequest.from(
                    noSubjectEncounter,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            assertThatThrownBy(() -> service.adapt(request))
                    .isInstanceOf(PatientIdNotFoundException.class);
        }
    }

    // ==================== buildSourceMetadata() ====================

    @Nested
    class BuildSourceMetadataTests {

        @Test
        void extractsAllHeadersIntoMetadata() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Facility-Id", "0002",
                            "X-Source-Event-Id", "enc-visit-001",
                            "X-Correlation-Id", "corr-abc-123"),
                    "/inbound");

            SourceMetadata metadata = service.buildSourceMetadata(request, SOURCE_KEY, null);

            assertThat(metadata.sourceIdentifier()).isEqualTo(SOURCE_KEY);
            assertThat(metadata.facilityId()).isEqualTo("0002");
            assertThat(metadata.sourceEventId()).isEqualTo("enc-visit-001");
            assertThat(metadata.correlationId()).isEqualTo("corr-abc-123");
            assertThat(metadata.sourcePath()).isEqualTo("/inbound");
            assertThat(metadata.eventTime()).isNotNull();
        }

        @Test
        void openhimTransactionId_preferredOverCorrelationId() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-OpenHIM-TransactionID", "65abc123def456789012abcd",
                            "X-Correlation-Id", "corr-should-be-ignored"),
                    "/inbound");

            SourceMetadata metadata = service.buildSourceMetadata(request, SOURCE_KEY, null);

            assertThat(metadata.correlationId()).isEqualTo("65abc123def456789012abcd");
        }

        @Test
        void openhimTransactionId_usedWhenCorrelationIdAbsent() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-OpenHIM-TransactionID", "65abc123def456789012abcd"),
                    "/inbound");

            SourceMetadata metadata = service.buildSourceMetadata(request, SOURCE_KEY, null);

            assertThat(metadata.correlationId()).isEqualTo("65abc123def456789012abcd");
        }

        @Test
        void correlationId_usedWhenTransactionIdAbsent() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Correlation-Id", "corr-fallback-001"),
                    "/inbound");

            SourceMetadata metadata = service.buildSourceMetadata(request, SOURCE_KEY, null);

            assertThat(metadata.correlationId()).isEqualTo("corr-fallback-001");
        }

        @Test
        void missingBothHeaders_generatesUuid() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            SourceMetadata metadata = service.buildSourceMetadata(request, SOURCE_KEY, null);

            assertThat(metadata.correlationId()).isNotNull().isNotBlank();
            assertThat(metadata.correlationId())
                    .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        }

        @Test
        void missingOptionalHeaders_returnsNullValues() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(),
                    "/inbound");

            SourceMetadata metadata = service.buildSourceMetadata(request, SOURCE_KEY, null);

            assertThat(metadata.facilityId()).isNull();
            assertThat(metadata.sourceEventId()).isNull();
        }
    }

    // ==================== adapt() — Headers flow to CloudEvent ====================

    @Nested
    class HeadersToCloudEvent {

        @Test
        void facilityIdHeader_mappedToCloudEvent() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Facility-Id", "0002"),
                    "/inbound");

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getFacilityid()).isEqualTo("0002");
        }

        @Test
        void sourceEventIdHeader_mappedToCloudEvent() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Source-Event-Id", "enc-visit-001"),
                    "/inbound");

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getSourceeventid()).isEqualTo("enc-visit-001");
        }

        @Test
        void correlationIdHeader_mappedToCloudEvent() {
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Correlation-Id", "corr-trace-001"),
                    "/inbound");

            CloudEventDto event = service.adapt(request).get(0);

            assertThat(event.getCorrelationid()).isEqualTo("corr-trace-001");
        }

        @Test
        void withSourceEventId_generatesDeterministicEventId() {
            InboundRequest request1 = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Source-Event-Id", "enc-visit-001"),
                    "/inbound");

            InboundRequest request2 = InboundRequest.from(
                    encounterJson,
                    Map.of(
                            "X-OpenHIM-ClientID", CLIENT_ID,
                            "X-Source-Event-Id", "enc-visit-001"),
                    "/inbound");

            CloudEventDto event1 = service.adapt(request1).get(0);
            CloudEventDto event2 = service.adapt(request2).get(0);

            assertThat(event1.getId()).isEqualTo(event2.getId());
        }
    }

    // ==================== adapt() — Facility filter ====================

    @Nested
    class FacilityFilterScenarios {

        private SourceAdaptorService buildServiceWithFilter(FacilityFilter filter) {
            FhirContext fhirContext = FhirContext.forR4();
            FhirResourceParser parser = new FhirResourceParser(fhirContext);
            ObjectMapper objectMapper = new ObjectMapper();
            CloudEventEnvelopeBuilder envelopeBuilder =
                    new CloudEventEnvelopeBuilder(new EventIdGenerator(), objectMapper, testRedactor());
            EmitterProperties props = new EmitterProperties(
                    Map.of(SOURCE_KEY, new SourceProperties(CLIENT_ID)),
                    "http://openphc.org/identifier/upid");
            return new SourceAdaptorService(props, parser,
                    new PatientIdExtractor(props), new FacilityIdExtractor(),
                    filter, envelopeBuilder);
        }

        private FacilityFilter filterWith(String... ids) {
            return new FacilityFilter(
                    new FacilityFilterProperties(List.of(ids)),
                    new SimpleMeterRegistry());
        }

        @Test
        void allowedFacility_producesSingleCloudEvent() {
            SourceAdaptorService svc = buildServiceWithFilter(filterWith("0002"));
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID, "X-Facility-Id", "0002"),
                    "/inbound");

            assertThat(svc.adapt(request)).hasSize(1);
        }

        @Test
        void facilityNotInAllowlist_throwsFacilityFilterRejectedException() {
            SourceAdaptorService svc = buildServiceWithFilter(filterWith("0002"));
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID, "X-Facility-Id", "9999"),
                    "/inbound");

            assertThatThrownBy(() -> svc.adapt(request))
                    .isInstanceOf(FacilityFilterRejectedException.class)
                    .hasMessageContaining("NOT_IN_ALLOWLIST");
        }

        @Test
        void missingFacilityId_passesThrough() {
            // No X-Facility-Id header and no location in payload → facilityId is null → passes through
            SourceAdaptorService svc = buildServiceWithFilter(filterWith("0002"));
            InboundRequest request = InboundRequest.from(
                    encounterJson,
                    Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                    "/inbound");

            assertThat(svc.adapt(request)).hasSize(1);
        }
    }

    // ==================== Helpers ====================

    private static FacilityFilter disabledFilter() {
        return new FacilityFilter(
                new FacilityFilterProperties(List.of()),
                new SimpleMeterRegistry());
    }

    private InboundRequest buildRequest(String body) {
        return InboundRequest.from(
                body,
                Map.of("X-OpenHIM-ClientID", CLIENT_ID),
                "/inbound");
    }

    private String loadFixture(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Test fixture not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
