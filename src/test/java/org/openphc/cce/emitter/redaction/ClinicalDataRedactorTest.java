package org.openphc.cce.emitter.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verified against payloads captured verbatim from Rwanda UAT rather than hand-written fixtures —
 * the point of the change is that real eBuzima events stop carrying clinical findings.
 *
 * <p>The central case is {@code code}: it is the <em>finding</em> on Condition, AllergyIntolerance
 * and ServiceRequest (so it must go), but the observation <em>type</em> on Observation, which
 * protocol triggers match on (so it must stay).
 */
class ClinicalDataRedactorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ClinicalDataRedactor redactor;

    @BeforeEach
    void setUp() {
        redactor = new ClinicalDataRedactor(
                new ClinicalDataRedactionProperties(true, null), new SimpleMeterRegistry());
    }

    private JsonNode redact(String json) throws Exception {
        return redactor.redact(mapper.readTree(json));
    }

    // ==================== Observation ====================

    @Nested
    @DisplayName("Observation — code is the observation TYPE and must survive")
    class Observations {

        /** Chief Complaints observation from UAT — the finding is free text: "HEADACHE". */
        private static final String CHIEF_COMPLAINT = """
                {
                  "id": "0312872e-f002-41c7-be52-9468e5211c92",
                  "code": { "coding": [ { "code": "33747-0", "system": "http://loinc.org", "display": "Chief Complaints" } ] },
                  "status": "final",
                  "subject": { "reference": "Patient/260908-0000-6502" },
                  "category": [ { "coding": [ { "code": "survey", "display": "Survey" } ] } ],
                  "encounter": { "reference": "Encounter/ae1b3cab-4665-4db9-8a09-6efc5ba27550" },
                  "extension": [
                    { "url": "http://example.org/fhir/StructureDefinition/source-system", "valueString": "eBuzima" },
                    { "url": "http://example.org/fhir/StructureDefinition/source-facility", "valueString": "0000" }
                  ],
                  "performer": [ { "display": "QT Admins", "reference": "Practitioner/HLC-PRAC-2026-00005" } ],
                  "valueString": "HEADACHE",
                  "resourceType": "Observation",
                  "effectiveDateTime": "2026-09-08T20:03:03+02:00"
                }
                """;

        /** Diagnosis-category observation from UAT — coded finding plus the patient's name. */
        private static final String DIAGNOSIS_OBS = """
                {
                  "id": "ef833deb-e75a-41e1-977d-412ec5f5cf4b",
                  "code": { "text": "Diagnosis", "coding": [ { "code": "diagnosis", "display": "Diagnosis" } ] },
                  "text": { "div": "<div>Observation</div>", "status": "generated" },
                  "issued": "2026-09-08T20:03:21+02:00",
                  "status": "final",
                  "subject": {
                    "type": "Patient", "display": "TEST  YAN7",
                    "reference": "Patient/260908-0000-6502",
                    "identifier": { "value": "260908-0000-6502" }
                  },
                  "category": [ { "coding": [ { "code": "diagnosis", "display": "Diagnosis" } ] } ],
                  "contained": [ { "id": "d90a04b9", "resourceType": "Provenance" } ],
                  "valueCodeableConcept": { "coding": [ { "code": "MB4D - Headache, not elsewhere classified" } ] },
                  "resourceType": "Observation",
                  "effectiveDateTime": "2026-09-08T20:03:21+02:00"
                }
                """;

        private final ObjectMapper m = new ObjectMapper();
        private final ClinicalDataRedactor r = new ClinicalDataRedactor(
                new ClinicalDataRedactionProperties(true, null), new SimpleMeterRegistry());

        @Test
        @DisplayName("free-text finding removed, LOINC code kept for protocol matching")
        void stripsValueKeepsCode() throws Exception {
            JsonNode out = r.redact(m.readTree(CHIEF_COMPLAINT));
            assertThat(out.has("valueString")).isFalse();
            assertThat(out.get("code").get("coding").get(0).get("code").asText()).isEqualTo("33747-0");
        }

        @Test
        @DisplayName("coded finding, narrative, contained resources and patient name removed")
        void stripsCodedFindingAndPii() throws Exception {
            JsonNode out = r.redact(m.readTree(DIAGNOSIS_OBS));
            assertThat(out.has("valueCodeableConcept")).isFalse();
            assertThat(out.has("text")).isFalse();
            assertThat(out.has("contained")).isFalse();
            assertThat(out.get("subject").has("display")).isFalse();
            assertThat(out.toString()).doesNotContain("MB4D").doesNotContain("TEST  YAN7");
        }

        @Test
        @DisplayName("everything CCE matches on survives")
        void preservesDownstreamFields() throws Exception {
            JsonNode out = r.redact(m.readTree(CHIEF_COMPLAINT));
            assertThat(out.get("resourceType").asText()).isEqualTo("Observation");
            assertThat(out.get("category").get(0).get("coding").get(0).get("code").asText()).isEqualTo("survey");
            assertThat(out.get("status").asText()).isEqualTo("final");
            assertThat(out.get("effectiveDateTime").asText()).isEqualTo("2026-09-08T20:03:03+02:00");
            assertThat(out.get("subject").get("reference").asText()).isEqualTo("Patient/260908-0000-6502");
            assertThat(out.get("performer").get(0).get("reference").asText())
                    .isEqualTo("Practitioner/HLC-PRAC-2026-00005");
            // source-facility lives in an extension valueString — shallow redaction must not touch it
            assertThat(out.get("extension").get(1).get("valueString").asText()).isEqualTo("0000");
        }
    }

    // ==================== Condition / AllergyIntolerance / ServiceRequest ====================

    @Test
    @DisplayName("Condition.code — the diagnosis itself — is removed")
    void removesConditionCode() throws Exception {
        String condition = """
                {
                  "resourceType": "Condition",
                  "id": "56116e54-89d9-4016-bd4e-9f5b96441018",
                  "clinicalStatus": { "coding": [ { "code": "active" } ] },
                  "verificationStatus": { "coding": [ { "code": "confirmed" } ] },
                  "code": { "coding": [ { "system": "https://icd.who.int", "code": "5A11", "display": "Type 2 diabetes mellitus" } ] },
                  "subject": { "reference": "Patient/260119-0035-6404" },
                  "onsetDateTime": "2026-04-21T22:16:59+02:00",
                  "encounter": { "reference": "Encounter/b4c6c3e7-3bb6-41b9-aada-0a9e89653e5a" },
                  "asserter": { "reference": "Practitioner/HLC-PRAC-2024-00004", "display": "Trainer Three" }
                }
                """;
        JsonNode out = redact(condition);

        assertThat(out.has("code")).isFalse();
        assertThat(out.toString()).doesNotContain("Type 2 diabetes").doesNotContain("5A11");

        // clinicalStatus is what the Rwanda protocol's diagnosis step triggers on — must survive
        assertThat(out.get("clinicalStatus").get("coding").get(0).get("code").asText()).isEqualTo("active");
        assertThat(out.get("verificationStatus").get("coding").get(0).get("code").asText()).isEqualTo("confirmed");
        assertThat(out.get("onsetDateTime").asText()).isEqualTo("2026-04-21T22:16:59+02:00");
        assertThat(out.get("subject").get("reference").asText()).isEqualTo("Patient/260119-0035-6404");
    }

    @Test
    @DisplayName("AllergyIntolerance.code — the allergen — is removed, patient.display too")
    void removesAllergyCodeAndPatientName() throws Exception {
        String allergy = """
                {
                  "resourceType": "AllergyIntolerance",
                  "id": "b087c9bf-9947-4107-b74b-98e20c267b27",
                  "clinicalStatus": { "coding": [ { "code": "active" } ] },
                  "verificationStatus": { "coding": [ { "code": "confirmed" } ] },
                  "patient": { "reference": "Patient/260119-0035-6404", "display": "Jane Doe" },
                  "code": { "coding": [ { "system": "http://npc.rw/npc", "code": "allergy on aminophyline", "display": "allergy on aminophyline" } ] },
                  "onsetDateTime": "2026-04-21T22:08:07+02:00",
                  "recordedDate": "2026-04-21",
                  "encounter": { "reference": "Encounter/e5cb1b38-92e0-4692-8132-55efbf70375d" },
                  "extension": [ { "url": "http://example.org/fhir/StructureDefinition/location", "valueReference": { "reference": "Location/0022" } } ]
                }
                """;
        JsonNode out = redact(allergy);

        assertThat(out.has("code")).isFalse();
        assertThat(out.toString()).doesNotContain("aminophyline");

        // patient.display (not subject.display) — this resource uses `patient`
        assertThat(out.get("patient").has("display")).isFalse();
        assertThat(out.get("patient").get("reference").asText()).isEqualTo("Patient/260119-0035-6404");

        // facility attribution via extension valueReference must survive
        assertThat(out.get("extension").get(0).get("valueReference").get("reference").asText())
                .isEqualTo("Location/0022");
        assertThat(out.get("recordedDate").asText()).isEqualTo("2026-04-21");
    }

    @Test
    @DisplayName("ServiceRequest.code — the test ordered — is removed, category kept")
    void removesServiceRequestCode() throws Exception {
        String serviceRequest = """
                {
                  "resourceType": "ServiceRequest",
                  "id": "219e30dd-dc84-49d1-9df2-2e310efb2d71",
                  "status": "active",
                  "intent": "order",
                  "category": { "coding": { "system": "http://snomed.info/sct", "code": "108252007", "display": "Laboratory procedure" } },
                  "code": { "coding": [ { "code": "a1-Acid Glycoprotein • •", "display": "a1-Acid Glycoprotein • •" } ] },
                  "subject": { "reference": "Patient/260119-0035-6404" },
                  "occurrenceDateTime": "2026-04-21T22:08:06+02:00",
                  "encounter": { "reference": "Encounter/e5cb1b38-92e0-4692-8132-55efbf70375d" },
                  "requester": { "reference": "Practitioner/HLC-PRAC-2026-00009", "display": "Isaac  Ntakirutimana" },
                  "performer": { "reference": "Practitioner/HLC-PRAC-2026-00009", "display": "Isaac  Ntakirutimana" }
                }
                """;
        JsonNode out = redact(serviceRequest);

        assertThat(out.has("code")).isFalse();
        assertThat(out.toString()).doesNotContain("Glycoprotein");

        // category distinguishes a laboratory order and is used by the lab-results trigger
        assertThat(out.get("category").get("coding").get("code").asText()).isEqualTo("108252007");
        assertThat(out.get("intent").asText()).isEqualTo("order");
        assertThat(out.get("occurrenceDateTime").asText()).isEqualTo("2026-04-21T22:08:06+02:00");
    }

    @Test
    @DisplayName("Encounter.type survives — it drives protocol matching and the referral KPI")
    void preservesEncounterType() throws Exception {
        String encounter = """
                {
                  "resourceType": "Encounter",
                  "status": "in-progress",
                  "class": { "code": "AMB", "display": "Ambulatory" },
                  "type": [ { "coding": [ { "display": "TRANSFER_ENCOUNTER" } ] } ],
                  "subject": { "reference": "Patient/260119-0035-6404", "display": "Jane Doe" },
                  "location": [ { "location": { "reference": "Location/0032", "display": "Gikondo Health Center" } } ],
                  "period": { "start": "2026-04-21T22:08:06+02:00" }
                }
                """;
        JsonNode out = redact(encounter);

        assertThat(out.get("type").get(0).get("coding").get(0).get("display").asText())
                .isEqualTo("TRANSFER_ENCOUNTER");
        assertThat(out.get("class").get("code").asText()).isEqualTo("AMB");
        assertThat(out.get("location").get(0).get("location").get("reference").asText()).isEqualTo("Location/0032");
        assertThat(out.get("subject").has("display")).isFalse();
    }

    // ==================== configuration behaviour ====================

    @Test
    @DisplayName("unknown resource type falls back to the * rule (code left alone)")
    void unknownResourceTypeUsesFallback() throws Exception {
        String unknown = """
                { "resourceType": "SomethingNew",
                  "code": { "coding": [ { "code": "X" } ] },
                  "valueString": "secret finding",
                  "note": [ { "text": "free text" } ] }
                """;
        JsonNode out = redact(unknown);
        assertThat(out.has("valueString")).isFalse();
        assertThat(out.has("note")).isFalse();
        // fallback deliberately does NOT strip code — for unknown types it may be structural
        assertThat(out.has("code")).isTrue();
    }

    @Test
    @DisplayName("custom per-type rules override the defaults")
    void honoursCustomRules() throws Exception {
        ClinicalDataRedactor custom = new ClinicalDataRedactor(
                new ClinicalDataRedactionProperties(true,
                        List.of(new ClinicalDataRedactionProperties.ResourceRule("Observation", List.of("status")))),
                new SimpleMeterRegistry());
        JsonNode out = custom.redact(mapper.readTree(
                """
                { "resourceType": "Observation", "status": "final", "valueString": "HEADACHE" }
                """));
        assertThat(out.has("status")).isFalse();
        // not in the custom rule, so it stays
        assertThat(out.has("valueString")).isTrue();
    }

    @Test
    @DisplayName("redaction disabled leaves the payload untouched")
    void disabledIsPassThrough() throws Exception {
        ClinicalDataRedactor off = new ClinicalDataRedactor(
                new ClinicalDataRedactionProperties(false, null), new SimpleMeterRegistry());
        JsonNode out = off.redact(mapper.readTree(
                """
                { "resourceType": "Condition", "code": { "coding": [ { "display": "Type 2 diabetes mellitus" } ] } }
                """));
        assertThat(out.get("code").get("coding").get(0).get("display").asText())
                .isEqualTo("Type 2 diabetes mellitus");
    }

    @Test
    @DisplayName("every resource type seen in production has an explicit rule")
    void coversAllProductionResourceTypes() {
        // Types observed in Rwanda PROD inbound_event_log. If eBuzima starts sending a new type,
        // this test should fail and force a deliberate decision rather than the type silently
        // receiving only the "*" entries (which leave `code` intact).
        List<String> production = List.of(
                "Observation", "Encounter", "ServiceRequest", "MedicationRequest", "Condition",
                "MedicationDispense", "Consent", "Procedure", "MedicationAdministration",
                "AllergyIntolerance");

        List<String> configured = new ClinicalDataRedactionProperties(true, null)
                .rules().stream()
                .map(ClinicalDataRedactionProperties.ResourceRule::resourceType)
                .toList();

        assertThat(configured).containsAll(production);
        assertThat(configured).contains(ClinicalDataRedactionProperties.ALL_RESOURCE_TYPES);
    }

    @Test
    @DisplayName("Consent keeps the fields its protocol step matches on")
    void consentKeepsMatchingFields() throws Exception {
        // 15,761 Consent events in PROD. It carries no clinical finding — category/scope/status
        // are consent metadata and must survive for the Consent step to match.
        JsonNode out = redact("""
                { "resourceType": "Consent", "status": "active",
                  "scope": { "coding": [ { "code": "patient-privacy" } ] },
                  "category": [ { "coding": [ { "code": "INFA", "display": "information access" } ] } ],
                  "patient": { "reference": "Patient/260119-0035-6404", "display": "Jane Doe" },
                  "dateTime": "2026-04-21T22:08:06+02:00" }
                """);
        assertThat(out.get("status").asText()).isEqualTo("active");
        assertThat(out.get("scope").get("coding").get(0).get("code").asText()).isEqualTo("patient-privacy");
        assertThat(out.get("category").get(0).get("coding").get(0).get("code").asText()).isEqualTo("INFA");
        assertThat(out.get("dateTime").asText()).isEqualTo("2026-04-21T22:08:06+02:00");
        assertThat(out.get("patient").has("display")).isFalse();   // name still goes
    }

    @Test
    @DisplayName("medication resources lose the drug but keep timing and status")
    void medicationResourcesLoseTheDrug() throws Exception {
        JsonNode req = redact("""
                { "resourceType": "MedicationRequest", "status": "active", "intent": "order",
                  "medicationCodeableConcept": { "coding": [ { "display": "Aminophylline 100mg" } ] },
                  "dosageInstruction": [ { "text": "twice daily" } ],
                  "dispenseRequest": { "quantity": { "value": 30 } },
                  "authoredOn": "2026-04-21T22:08:06+02:00",
                  "requester": { "reference": "Practitioner/HLC-PRAC-2026-00009" } }
                """);
        assertThat(req.toString()).doesNotContain("Aminophylline").doesNotContain("twice daily");
        assertThat(req.has("dispenseRequest")).isFalse();
        assertThat(req.get("intent").asText()).isEqualTo("order");          // protocol conditions read intent
        assertThat(req.get("authoredOn").asText()).isEqualTo("2026-04-21T22:08:06+02:00");
        assertThat(req.get("requester").get("reference").asText()).isEqualTo("Practitioner/HLC-PRAC-2026-00009");

        JsonNode disp = redact("""
                { "resourceType": "MedicationDispense", "status": "completed",
                  "medicationCodeableConcept": { "coding": [ { "display": "Aminophylline 100mg" } ] },
                  "quantity": { "value": 30 },
                  "whenHandedOver": "2026-04-21T22:30:00+02:00" }
                """);
        assertThat(disp.toString()).doesNotContain("Aminophylline");
        assertThat(disp.has("quantity")).isFalse();
        // whenHandedOver is a clinical-time field the SLA engine reads
        assertThat(disp.get("whenHandedOver").asText()).isEqualTo("2026-04-21T22:30:00+02:00");
    }

    @Test
    @DisplayName("Procedure loses the procedure code but keeps time and facility")
    void procedureLosesCode() throws Exception {
        JsonNode out = redact("""
                { "resourceType": "Procedure", "status": "completed",
                  "code": { "coding": [ { "display": "Appendectomy" } ] },
                  "performedDateTime": "2026-04-21T22:08:06+02:00",
                  "location": { "reference": "Location/0022" },
                  "performer": [ { "actor": { "reference": "Practitioner/HLC-PRAC-2026-00009" } } ] }
                """);
        assertThat(out.has("code")).isFalse();
        assertThat(out.toString()).doesNotContain("Appendectomy");
        assertThat(out.get("performedDateTime").asText()).isEqualTo("2026-04-21T22:08:06+02:00");
        assertThat(out.get("location").get("reference").asText()).isEqualTo("Location/0022");
    }

    @Test
    @DisplayName("payload with nothing to redact is unchanged")
    void handlesNothingToRedact() throws Exception {
        JsonNode out = redact("""
                { "resourceType": "Encounter", "status": "in-progress",
                  "subject": { "reference": "Patient/260908-0000-6502" } }
                """);
        assertThat(out.get("resourceType").asText()).isEqualTo("Encounter");
        assertThat(out.get("subject").get("reference").asText()).isEqualTo("Patient/260908-0000-6502");
    }
}
