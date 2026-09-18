package org.openphc.cce.emitter.redaction;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for clinical-data redaction, bound to {@code cce.emitter.redaction}.
 *
 * <p>CCE is a care <em>coordination</em> engine: it needs to know <em>that</em> a clinical step
 * happened, when, for which patient and at which facility — not <em>what the clinical finding
 * was</em>.
 *
 * <h2>How rules combine</h2>
 *
 * <p>The rule with resource type {@value #ALL_RESOURCE_TYPES} is applied to <b>every</b> resource.
 * A resource's own rule, if it has one, is applied <b>in addition</b>. So each type's rule lists
 * only what is special about that type, and the content that is sensitive everywhere is written
 * once:
 *
 * <pre>
 *   Condition  →  the "*" list  +  code
 *   Observation → the "*" list  +  component      (code deliberately absent — see below)
 *   SomeNewType → the "*" list                    (no rule of its own)
 * </pre>
 *
 * <p>Rules are per-resource-type because the same FHIR element carries very different sensitivity
 * depending on the resource. {@code code} is the clearest case:
 *
 * <ul>
 *   <li>{@code Condition.code} — the diagnosis ("Type 2 diabetes mellitus") → must be removed</li>
 *   <li>{@code AllergyIntolerance.code} — the allergy ("allergy on aminophyline") → must be removed</li>
 *   <li>{@code ServiceRequest.code} — the test ordered ("a1-Acid Glycoprotein") → must be removed</li>
 *   <li>{@code Observation.code} — the observation <em>type</em> (LOINC 8716-3 "Vital signs") →
 *       must be <b>kept</b>: protocol triggers match on it, so removing it would stop compliance
 *       tracking working</li>
 * </ul>
 *
 * <h2>Field syntax</h2>
 *
 * <p>An entry is either a root field name or a dot-delimited path. A segment ending in {@code []}
 * steps through an array and applies to every element:
 *
 * <pre>
 *   code                                 → Condition.code
 *   subject.display                      → Observation.subject.display
 *   hospitalization.dischargeDisposition → Encounter.hospitalization.dischargeDisposition
 *   reaction[].manifestation             → AllergyIntolerance.reaction[*].manifestation
 * </pre>
 *
 * <p>The {@code []} marker is required where the data is an array; without it the path matches
 * nothing. That is not silent — see {@link ClinicalDataRedactor} for how it is reported.
 *
 * <p>Prefer removing a whole structure over pathing into it. {@code component} on Observation drops
 * every sub-observation in one step; a path is only needed when part of the structure must survive.
 *
 * @param enabled master switch; {@code false} passes payloads through untouched
 * @param rules   one entry per resource type, plus the {@value #ALL_RESOURCE_TYPES} entry applied
 *                to everything
 */
@ConfigurationProperties(prefix = "cce.emitter.redaction")
public record ClinicalDataRedactionProperties(
        Boolean enabled,
        List<ResourceRule> rules
) {

    /**
     * The rule applied to every resource, on top of that resource's own rule.
     *
     * <p>There is deliberately no second wildcard token for "types without a rule of their own":
     * such a type is simply one with nothing to add, and this rule already covers it. One token,
     * one meaning.
     */
    public static final String ALL_RESOURCE_TYPES = "*";

    /**
     * What to remove for one FHIR resource type.
     *
     * @param resourceType FHIR resource type (e.g. {@code Condition}), or {@value #ALL_RESOURCE_TYPES}
     * @param fields       root field names and/or dot-delimited paths to remove
     */
    public record ResourceRule(String resourceType, List<String> fields) {
        public ResourceRule {
            if (fields == null) fields = List.of();
        }
        // No convenience constructor: an extra constructor on a record stops Spring Boot
        // identifying the canonical one, and the whole redaction tree then fails to bind.
    }

    /**
     * Content that is sensitive on every resource type that carries it, plus the patient's name.
     * Verified against every reader in the platform (matcher, collector, compliance, insights,
     * ClickHouse materialised columns): none of these are consumed, so removing them changes no
     * behaviour.
     */
    private static final List<String> ALL_TYPES_FIELDS = List.of(
            // value[x] — the measurement or coded finding
            "valueQuantity", "valueCodeableConcept", "valueString", "valueBoolean",
            "valueInteger", "valueRange", "valueRatio", "valueSampledData",
            "valueTime", "valueDateTime", "valuePeriod", "valueAttachment",
            // Free-text and interpretive clinical content
            "note", "text", "interpretation", "dataAbsentReason",
            "bodySite", "method", "specimen", "referenceRange",
            // Embedded resources (Provenance, contained Observations carrying values)
            "contained",
            // Condition-style qualifiers
            "severity", "stage", "evidence",
            // Ordering / prescribing detail and clinical justification
            "dosageInstruction", "reasonCode", "reasonReference",
            "orderDetail", "patientInstruction",
            // Any remaining free-text a clinician can type into. Verified 2026-09-18 that no
            // service reads these from inbound events (the two `description` hits in insights /
            // intelligence are writes that BUILD output, not reads).
            "comment", "description", "statusReason", "instruction", "summary",
            "conclusion", "conclusionCode", "outcome",
            // The patient's name. Not clinical, but direct identifying data in the same payload,
            // and nothing downstream reads it — the patient is keyed on subject.reference /
            // patient.reference (the UPID), which is preserved. Both spellings are needed because
            // AllergyIntolerance and several other types use `patient` rather than `subject`.
            "subject.display", "patient.display"
    );

    /**
     * One rule per resource type observed in Rwanda, listing only what that type adds to
     * {@link #ALL_TYPES_FIELDS}. Volumes at time of writing (PROD {@code inbound_event_log}):
     * Observation 141k, Encounter 48k, ServiceRequest 33k, MedicationRequest 30k, Condition 25k,
     * MedicationDispense 20k, Consent 16k, Procedure 4.2k, MedicationAdministration 1.6k,
     * AllergyIntolerance 22.
     */
    private static final List<ResourceRule> DEFAULT_RULES = List.of(
            new ResourceRule(ALL_RESOURCE_TYPES, ALL_TYPES_FIELDS),

            // code = the observation TYPE (LOINC) and is what protocol triggers match on — kept.
            // component holds sub-observations, each with their own value[x].
            new ResourceRule("Observation", List.of("component")),

            // diagnosis = the encounter's diagnosis. type/class/serviceType drive protocol matching
            // and the referral KPI; hospitalization and location are the facility source, so they
            // are kept — hence dischargeDisposition ("Died in hospital") needs a nested path.
            // extension is dropped WHOLESALE here, and only here. eBuzima packs diagnosis,
            // prescriptions, vitals, obstetric detail, the patient's NAME, DOB and village-level
            // address into custom extensions on transfer Encounters. Safe for Encounter only:
            // the matcher and insights resolve an Encounter's facility from hospitalization.origin
            // -> location[0].location and "deliberately never consult the source-facility
            // extension for Encounter" (FacilityService). Every OTHER resource type has the
            // source-facility extension as its ONLY facility signal, so extension must stay there.
            new ResourceRule("Encounter",
                    List.of("diagnosis", "hospitalization.dischargeDisposition", "extension")),

            // code = the test/procedure requested ("a1-Acid Glycoprotein").
            // category (laboratory vs other) and locationReference (facility) are kept.
            new ResourceRule("ServiceRequest", List.of("code")),

            // medicationCodeableConcept = the drug; dispenseRequest carries quantity and refills.
            // intent and authoredOn are kept — protocol conditions read intent.
            new ResourceRule("MedicationRequest",
                    List.of("medicationCodeableConcept", "medicationReference", "dispenseRequest")),

            // code = the diagnosis itself. clinicalStatus/verificationStatus are the trigger keys.
            new ResourceRule("Condition", List.of("code")),

            // quantity = how much of the drug was dispensed. whenHandedOver is kept (clinical time).
            new ResourceRule("MedicationDispense",
                    List.of("medicationCodeableConcept", "medicationReference", "quantity")),

            // Consent carries no clinical finding — category/scope/status are consent metadata, and
            // are what the Consent step matches on. Listed with nothing to add so it is documented
            // as reviewed rather than merely unmentioned.
            new ResourceRule("Consent", List.of()),

            // code = the procedure performed. performedDateTime and location are kept.
            new ResourceRule("Procedure", List.of("code", "complication")),

            // dosage = how much was administered; supportingInformation may point at clinical data.
            new ResourceRule("MedicationAdministration",
                    List.of("medicationCodeableConcept", "medicationReference",
                            "dosage", "supportingInformation")),

            // code = the allergen ("allergy on aminophyline"); reaction holds manifestation detail.
            new ResourceRule("AllergyIntolerance", List.of("code", "reaction")),

            // Seen in UAT (not yet in PROD). The radiology findings live in fields that appear on
            // no other resource type — conclusion is free-text narrative from the reporting
            // radiologist — so without this rule they would not be removed at all.
            new ResourceRule("ImagingStudy",
                    List.of("procedureCode", "series", "modality")),

            // Not currently received from eBuzima, but included so a new feed cannot leak the
            // vaccine given before anyone notices the resource type is unhandled.
            new ResourceRule("Immunization", List.of("vaccineCode"))
    );

    public ClinicalDataRedactionProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (rules == null || rules.isEmpty()) rules = DEFAULT_RULES;
    }

    /** The fields applied to every resource, or empty if no {@value #ALL_RESOURCE_TYPES} rule exists. */
    public List<String> getAllResourceTypesFields() {
        return rules.stream()
                .filter(r -> ALL_RESOURCE_TYPES.equals(r.resourceType()))
                .findFirst()
                .map(ResourceRule::fields)
                .orElse(List.of());
    }

    /** The fields specific to {@code resourceType}, excluding {@link #getAllResourceTypesFields()}. */
    public List<String> fieldsFor(String resourceType) {
        List<String> found = new ArrayList<>();
        rules.stream()
                .filter(r -> !ALL_RESOURCE_TYPES.equals(r.resourceType()))
                .filter(r -> r.resourceType().equals(resourceType))
                .findFirst()
                .ifPresent(r -> found.addAll(r.fields()));
        return List.copyOf(found);
    }
}
