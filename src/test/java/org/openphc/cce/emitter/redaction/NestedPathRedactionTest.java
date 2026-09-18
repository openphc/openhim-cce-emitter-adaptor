package org.openphc.cce.emitter.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.search.RequiredSearch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Nested-path redaction: array traversal, per-resource-type scoping, and the reporting of paths
 * that match nothing.
 *
 * <p>Root-level field removal is covered by {@link ClinicalDataRedactorTest}; this class covers
 * only what happens below the root, where the failure modes are quiet ones.
 */
class NestedPathRedactionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /**
     * @param allTypes entries for the "*" rule, applied to every resource
     * @param rules    type-specific rules, applied on top
     */
    private ClinicalDataRedactor redactor(List<String> allTypes,
                                          List<ClinicalDataRedactionProperties.ResourceRule> rules) {
        registry = new SimpleMeterRegistry();
        List<ClinicalDataRedactionProperties.ResourceRule> all = new java.util.ArrayList<>();
        all.add(new ClinicalDataRedactionProperties.ResourceRule(
                ClinicalDataRedactionProperties.ALL_RESOURCE_TYPES, allTypes));
        all.addAll(rules);
        return new ClinicalDataRedactor(new ClinicalDataRedactionProperties(true, all), registry);
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ClinicalDataRedactionProperties.ResourceRule rule(
            String type, List<String> paths) {
        return new ClinicalDataRedactionProperties.ResourceRule(type, paths);
    }

    @Nested
    @DisplayName("array traversal")
    class ArrayTraversal {

        @Test
        @DisplayName("a [] segment strips the field from every element of the array")
        void arraySegmentAppliesToEveryElement() {
            ClinicalDataRedactor redactor = redactor(List.of(),
                    List.of(rule("AllergyIntolerance", List.of("reaction[].manifestation"))));

            JsonNode out = redactor.redact(json("""
                    {
                      "resourceType": "AllergyIntolerance",
                      "reaction": [
                        {"manifestation": [{"text": "Anaphylaxis"}], "onset": "2026-01-02"},
                        {"manifestation": [{"text": "Rash"}], "onset": "2026-03-04"}
                      ]
                    }
                    """));

            assertThat(out.toString()).doesNotContain("Anaphylaxis", "Rash");
            assertThat(out.path("reaction")).hasSize(2);
            // Non-clinical siblings inside the same array elements are untouched
            assertThat(out.path("reaction").get(0).path("onset").asText()).isEqualTo("2026-01-02");
            assertThat(out.path("reaction").get(1).path("onset").asText()).isEqualTo("2026-03-04");
        }

        @Test
        @DisplayName("a [] segment also accepts a lone object, since FHIR sources vary")
        void arraySegmentToleratesSingleObject() {
            ClinicalDataRedactor redactor = redactor(List.of(),
                    List.of(rule("AllergyIntolerance", List.of("reaction[].manifestation"))));

            JsonNode out = redactor.redact(json("""
                    {"resourceType": "AllergyIntolerance",
                     "reaction": {"manifestation": [{"text": "Anaphylaxis"}], "onset": "2026-01-02"}}
                    """));

            assertThat(out.toString()).doesNotContain("Anaphylaxis");
            assertThat(out.path("reaction").path("onset").asText()).isEqualTo("2026-01-02");
        }

        @Test
        @DisplayName("nested arrays are traversed at every level")
        void multipleArrayLevels() {
            ClinicalDataRedactor redactor = redactor(List.of(),
                    List.of(rule("ImagingStudy", List.of("series[].instance[].title"))));

            JsonNode out = redactor.redact(json("""
                    {
                      "resourceType": "ImagingStudy",
                      "series": [
                        {"uid": "1.1", "instance": [{"uid": "a", "title": "Fracture of left radius"},
                                                    {"uid": "b", "title": "Normal study"}]},
                        {"uid": "1.2", "instance": [{"uid": "c", "title": "Mass in right lung"}]}
                      ]
                    }
                    """));

            assertThat(out.toString())
                    .doesNotContain("Fracture of left radius", "Normal study", "Mass in right lung");
            assertThat(out.toString()).contains("1.1", "1.2", "\"a\"", "\"b\"", "\"c\"");
        }
    }

    @Nested
    @DisplayName("a path that matches nothing is reported, not skipped")
    class MismatchReporting {

        private static final String ALLERGY = """
                {"resourceType": "AllergyIntolerance",
                 "reaction": [{"manifestation": [{"text": "Anaphylaxis"}]}]}
                """;

        @Test
        @DisplayName("omitting [] on an array increments the mismatch counter")
        void missingArrayMarkerIsCounted() {
            ClinicalDataRedactor redactor = redactor(List.of(),
                    List.of(rule("AllergyIntolerance", List.of("reaction.manifestation"))));

            redactor.redact(json(ALLERGY));

            RequiredSearch search = registry.get("cce.emitter.redaction.path.mismatch.total")
                    .tag("resource_type", "AllergyIntolerance")
                    .tag("path", "reaction.manifestation");
            assertThat(search.counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the correct path redacts and raises no mismatch")
        void correctPathRaisesNothing() {
            ClinicalDataRedactor redactor = redactor(List.of(),
                    List.of(rule("AllergyIntolerance", List.of("reaction[].manifestation"))));

            JsonNode out = redactor.redact(json(ALLERGY));

            assertThat(out.toString()).doesNotContain("Anaphylaxis");
            assertThat(registry.find("cce.emitter.redaction.path.mismatch.total").counter()).isNull();
        }

        @Test
        @DisplayName("a path whose structure is simply absent is not reported as a mismatch")
        void absentStructureIsNotAMismatch() {
            ClinicalDataRedactor redactor = redactor(List.of(),
                    List.of(rule("Encounter", List.of("hospitalization.dischargeDisposition"))));

            redactor.redact(json("""
                    {"resourceType": "Encounter", "status": "finished"}"""));

            // Absent is normal — most encounters have no hospitalization. Only a type conflict,
            // which means the config cannot ever work, is worth alerting on.
            assertThat(registry.find("cce.emitter.redaction.path.mismatch.total").counter()).isNull();
        }
    }

    @Nested
    @DisplayName("scoping")
    class Scoping {

        private ClinicalDataRedactor twoTypes() {
            return redactor(
                    List.of("subject.display"),
                    List.of(rule("Encounter", List.of("hospitalization.dischargeDisposition")),
                            rule("Procedure", List.of())));
        }

        @Test
        @DisplayName("a type-scoped path applies to its own resource type")
        void typeScopedPathApplies() {
            JsonNode out = twoTypes().redact(json("""
                    {"resourceType": "Encounter",
                     "hospitalization": {"origin": {"reference": "Location/42"},
                                         "dischargeDisposition": {"text": "Died in hospital"}}}
                    """));

            assertThat(out.path("hospitalization").has("dischargeDisposition")).isFalse();
            // hospitalization itself survives — origin is the facility source
            assertThat(out.path("hospitalization").path("origin").path("reference").asText())
                    .isEqualTo("Location/42");
        }

        @Test
        @DisplayName("a type-scoped path does NOT leak onto other resource types")
        void typeScopedPathDoesNotApplyElsewhere() {
            JsonNode out = twoTypes().redact(json("""
                    {"resourceType": "Procedure",
                     "hospitalization": {"dischargeDisposition": {"text": "kept"}}}
                    """));

            assertThat(out.path("hospitalization").has("dischargeDisposition")).isTrue();
        }

        @Test
        @DisplayName("all-types and type-scoped paths are additive, not alternatives")
        void allTypesAndTypeScopedBothApply() {
            JsonNode out = twoTypes().redact(json("""
                    {"resourceType": "Encounter",
                     "subject": {"reference": "Patient/1", "display": "Jane Doe"},
                     "hospitalization": {"dischargeDisposition": {"text": "Died in hospital"}}}
                    """));

            assertThat(out.path("subject").has("display")).as("all-types path").isFalse();
            assertThat(out.path("hospitalization").has("dischargeDisposition")).as("type path").isFalse();
            assertThat(out.path("subject").path("reference").asText()).isEqualTo("Patient/1");
        }

        @Test
        @DisplayName("a type with no rule of its own still gets the \"*\" entries")
        void allTypesPathsApplyToUnknownTypes() {
            ClinicalDataRedactor redactor = redactor(List.of("outcome.text"), List.of());

            JsonNode out = redactor.redact(json("""
                    {"resourceType": "SomeFutureResource", "outcome": {"text": "clinical finding"}}"""));

            assertThat(out.path("outcome").has("text")).isFalse();
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        @DisplayName("malformed and edge-case paths do not throw")
        void malformedPathsAreSafe() {
            ClinicalDataRedactor redactor = redactor(
                    List.of("", "   ", "a..b", "[]", "lonely"),
                    List.of(rule("Observation", List.of("x.y.z.deep"))));

            assertThatCode(() -> redactor.redact(json("""
                    {"resourceType": "Observation", "lonely": "gone", "a": {"b": "x"}}""")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a single-segment path removes a root field")
        void singleSegmentPathRemovesRootField() {
            ClinicalDataRedactor redactor = redactor(List.of("lonely"), List.of());

            JsonNode out = redactor.redact(json("""
                    {"resourceType": "Observation", "lonely": "gone", "status": "final"}"""));

            assertThat(out.has("lonely")).isFalse();
            assertThat(out.path("status").asText()).isEqualTo("final");
        }

        @Test
        @DisplayName("a path into an already-removed root field is a harmless no-op")
        void pathIntoRemovedRootField() {
            ClinicalDataRedactor redactor = new ClinicalDataRedactor(
                    new ClinicalDataRedactionProperties(true,
                            List.of(new ClinicalDataRedactionProperties.ResourceRule(
                                    "Observation", List.of("component", "component[].valueQuantity")))),
                    new SimpleMeterRegistry());

            JsonNode out = redactor.redact(json("""
                    {"resourceType": "Observation",
                     "component": [{"valueQuantity": {"value": 36.0}}]}"""));

            assertThat(out.has("component")).isFalse();
        }
    }
}
