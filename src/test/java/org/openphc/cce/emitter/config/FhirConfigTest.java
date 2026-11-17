package org.openphc.cce.emitter.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@link FhirConfig} bean is correctly configured as a singleton
 * and returns an R4 {@link FhirContext}.
 */
// Beckn is on by default on demo-beckn and needs its ids; this test is about the base context.
@SpringBootTest(properties = "beckn.enabled=false")
class FhirConfigTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FhirContext fhirContext;

    @Test
    void fhirContextBeanShouldBeR4() {
        assertThat(fhirContext).isNotNull();
        assertThat(fhirContext.getVersion().getVersion()).isEqualTo(FhirVersionEnum.R4);
    }

    @Test
    void fhirContextBeanShouldBeSingleton() {
        FhirContext first = applicationContext.getBean(FhirContext.class);
        FhirContext second = applicationContext.getBean(FhirContext.class);
        assertThat(first).isSameAs(second);
    }
}
