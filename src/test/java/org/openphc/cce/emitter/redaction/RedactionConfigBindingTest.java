package org.openphc.cce.emitter.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the <b>real</b> {@code application.yml} and asserts on the rules that come out.
 *
 * <p>Redaction is a compliance control whose failure mode is silent. A typo in the YAML, or the
 * folded comma-separated {@code fields} scalars not splitting the way we assume, would not throw —
 * it would quietly forward clinical data to the Collector. {@link ClinicalDataRedactorTest} proves
 * the redactor honours the rules it is given; this proves the rules production actually ships are
 * the right ones.
 *
 * <p>Assertions are made against the <b>effective</b> set for a resource type — the {@code "*"}
 * entries plus that type's own — because that, not either list alone, is what gets removed.
 */
class RedactionConfigBindingTest {

    private static ClinicalDataRedactionProperties properties;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void bindRealApplicationYml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addFirst);

        properties = Binder.get(environment)
                .bind("cce.emitter.redaction", ClinicalDataRedactionProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "cce.emitter.redaction is absent from application.yml — redaction would fall "
                                + "back to Java defaults with nothing auditable in configuration"));
    }

    /** Everything removed for this resource type: the "*" entries plus the type's own. */
    private static Set<String> effectiveFor(String resourceType) {
        Set<String> all = new LinkedHashSet<>(properties.getAllResourceTypesFields());
        all.addAll(properties.fieldsFor(resourceType));
        return all;
    }

    private static List<String> configuredTypes() {
        return properties.rules().stream()
                .map(ClinicalDataRedactionProperties.ResourceRule::resourceType)
                .filter(t -> !ClinicalDataRedactionProperties.ALL_RESOURCE_TYPES.equals(t))
                .toList();
    }

    @Test
    @DisplayName("redaction is enabled in the shipped configuration")
    void enabledInShippedConfig() {
        assertThat(properties.enabled()).isTrue();
    }

    @Test
    @DisplayName("the \"*\" rule exists and is not empty")
    void allTypesRuleExists() {
        assertThat(properties.getAllResourceTypesFields())
                .as("without the \"*\" rule, every type would redact only its own extras")
                .isNotEmpty();
    }

    @Test
    @DisplayName("folded comma-separated field lists split into individual entries")
    void foldedScalarsSplitIntoFieldNames() {
        // If the folded scalar failed to split, this would be one long comma-joined string.
        assertThat(properties.getAllResourceTypesFields())
                .contains("valueString", "valueCodeableConcept", "subject.display");
        assertThat(properties.getAllResourceTypesFields()).allSatisfy(field -> {
            assertThat(field).doesNotContain(",");
            assertThat(field).isEqualTo(field.trim());
        });
    }

    @Test
    @DisplayName("every resource type seen in Rwanda has its own entry")
    void allObservedResourceTypesConfigured() {
        assertThat(configuredTypes()).contains(
                // PROD
                "Observation", "Encounter", "ServiceRequest", "MedicationRequest", "Condition",
                "MedicationDispense", "Consent", "Procedure", "MedicationAdministration",
                "AllergyIntolerance",
                // UAT only
                "ImagingStudy");
    }

    @Test
    @DisplayName("code is stripped where it is the finding, kept where it is the trigger key")
    void codeHandledPerResourceType() {
        for (String type : List.of("Condition", "AllergyIntolerance", "ServiceRequest", "Procedure")) {
            assertThat(effectiveFor(type))
                    .as("%s.code is the clinical finding and must be stripped", type)
                    .contains("code");
        }

        assertThat(effectiveFor("Observation"))
                .as("Observation.code is the LOINC trigger key — stripping it breaks protocol matching")
                .doesNotContain("code");
    }

    @Test
    @DisplayName("drug identity is stripped from every medication resource")
    void medicationResourcesStripTheDrug() {
        for (String type : List.of("MedicationRequest", "MedicationDispense", "MedicationAdministration")) {
            assertThat(effectiveFor(type))
                    .as("%s must not forward which drug was involved", type)
                    .contains("medicationCodeableConcept");
        }
    }

    @Test
    @DisplayName("every configured type strips value[x] and the patient name via the \"*\" rule")
    void everyTypeInheritsTheCommonSet() {
        for (String type : configuredTypes()) {
            assertThat(effectiveFor(type))
                    .as("type '%s'", type)
                    .contains("valueString", "valueQuantity", "valueCodeableConcept",
                            "subject.display", "patient.display");
        }
    }

    @Test
    @DisplayName("a type with no rule of its own still gets the \"*\" entries")
    void unknownTypeStillRedacted() {
        assertThat(effectiveFor("SomeFutureResourceType"))
                .contains("valueString", "subject.display")
                // conservative: code may be structural on an unrecognised type
                .doesNotContain("code");
    }

    @Test
    @DisplayName("the patient's name is stripped but the UPID reference is not")
    void patientNameStrippedNotReference() {
        assertThat(properties.getAllResourceTypesFields()).contains("subject.display", "patient.display");
        assertThat(properties.getAllResourceTypesFields())
                .doesNotContain("subject", "patient", "subject.reference", "patient.reference");
    }

    @Test
    @DisplayName("a type-specific entry does not leak onto other types")
    void typeSpecificEntriesStayScoped() {
        assertThat(properties.fieldsFor("Encounter"))
                .contains("hospitalization.dischargeDisposition");
        assertThat(properties.getAllResourceTypesFields())
                .as("if this were in the \"*\" rule it would apply to every resource type")
                .doesNotContain("hospitalization.dischargeDisposition");
        assertThat(effectiveFor("Procedure")).doesNotContain("hospitalization.dischargeDisposition");
    }

    @Test
    @DisplayName("no configured path omits [] where the FHIR element is a repeating one")
    void configuredPathsDeclareArraysCorrectly() {
        // FHIR elements with cardinality 0..* that could appear in our paths. A path traversing
        // one of these without '[]' matches nothing while looking correctly configured.
        List<String> repeatingElements = List.of(
                "reaction", "component", "series", "instance", "extension", "performer",
                "category", "type", "location", "participant", "identifier", "coding");

        List<String> allEntries = new ArrayList<>(properties.getAllResourceTypesFields());
        properties.rules().forEach(r -> allEntries.addAll(r.fields()));

        for (String entry : allEntries) {
            String[] segments = entry.split("\\.");
            for (int i = 0; i < segments.length - 1; i++) {   // leaf is removed by name, so exempt
                assertThat(repeatingElements)
                        .as("path '%s' traverses repeating element '%s' without '[]' — "
                                + "it would redact nothing", entry, segments[i])
                        .doesNotContain(segments[i]);
            }
        }
    }

    @Test
    @DisplayName("the shipped YAML redacts a real Condition end to end")
    void shippedConfigRedactsARealCondition() throws IOException {
        ClinicalDataRedactor redactor = new ClinicalDataRedactor(properties, new SimpleMeterRegistry());

        JsonNode redacted = redactor.redact(MAPPER.readTree("""
                {
                  "resourceType": "Condition",
                  "id": "abc-123",
                  "clinicalStatus": {"coding": [{"code": "active"}]},
                  "code": {"coding": [{"code": "5A11", "display": "Type 2 diabetes mellitus"}],
                           "text": "Type 2 diabetes mellitus"},
                  "subject": {"reference": "Patient/241018-1228-7904", "display": "Jane Doe"},
                  "recordedDate": "2026-07-24T10:51:53+02:00"
                }
                """));

        assertThat(redacted.has("code")).as("the diagnosis").isFalse();
        assertThat(redacted.path("subject").has("display")).as("the patient name").isFalse();

        // What CCE needs to keep working
        assertThat(redacted.path("subject").path("reference").asText()).isEqualTo("Patient/241018-1228-7904");
        assertThat(redacted.path("clinicalStatus").path("coding").get(0).path("code").asText()).isEqualTo("active");
        assertThat(redacted.path("recordedDate").asText()).isEqualTo("2026-07-24T10:51:53+02:00");
    }
}
