package org.openphc.cce.emitter.redaction;

import ca.uhn.fhir.context.FhirContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openphc.cce.emitter.fhir.FhirResourceParser;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The collector re-parses every payload with HAPI and rejects anything malformed as INVALID_FHIR.
 * Redaction must therefore leave structurally valid FHIR R4 behind — verified here against the
 * actual redacted UAT payloads.
 */
class RedactedPayloadStillValidFhirTest {

    private final FhirResourceParser parser = new FhirResourceParser(FhirContext.forR4());

    @ParameterizedTest
    @ValueSource(strings = {"chief-complaint-redacted.json", "diagnosis-redacted.json"})
    @DisplayName("redacted UAT payload still parses as valid FHIR R4")
    void redactedPayloadParses(String file) throws Exception {
        String json = Files.readString(Paths.get("src/test/resources/uat-samples/" + file));
        var resource = parser.parse(json);
        assertThat(resource.fhirType()).isEqualTo("Observation");
    }
}
