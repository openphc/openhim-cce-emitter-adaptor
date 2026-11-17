package org.openphc.cce.emitter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openphc.cce.emitter.beckn.BecknTestSupport.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties.Output;
import org.openphc.cce.emitter.config.EmitterProperties.SourceProperties;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;

/** The startup checks of spec §7. */
class BecknStartupValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultResourceLoader loader = new DefaultResourceLoader();
    private final List<String> warnings = new ArrayList<>();

    private static EmitterProperties sources(Map<String, SourceProperties> sources) {
        return new EmitterProperties(sources, null);
    }

    private static final EmitterProperties DEFAULT_SOURCES = sources(Map.of(
            "ebuzima", new SourceProperties("ebuzima-emr-client"),
            OPENMRS_SOURCE, new SourceProperties(null, Output.BECKN)));

    private List<String> validate(BecknProperties beckn, EmitterProperties emitter) {
        return BecknStartupValidator.validate(beckn, emitter, loader, mapper, warnings);
    }

    @Test
    @DisplayName("defaults: Beckn off, every source collector → no error")
    void defaultsAreFine() {
        assertThat(validate(properties(false, "cce-beckn-endpoint", List.of()),
                sources(Map.of("ebuzima", new SourceProperties("ebuzima-emr-client"))))).isEmpty();
    }

    @Test
    @DisplayName("a valid Beckn setup → no error")
    void validSetup() {
        assertThat(validate(properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber())), DEFAULT_SOURCES))
                .isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("output: beckn with beckn.enabled=false → error")
    void becknOutputWhileDisabled() {
        assertThat(validate(properties(false, "cce-beckn-endpoint", List.of()), DEFAULT_SOURCES))
                .anySatisfy(e -> assertThat(e).contains("output: beckn but beckn.enabled is false"));
    }

    @Test
    @DisplayName("the channel client id equals a source's client-id → error (spec §3.2)")
    void clientIdClash() {
        assertThat(validate(properties(true, "ebuzima-emr-client", List.of(openmrsSubscriber())), DEFAULT_SOURCES))
                .anySatisfy(e -> assertThat(e).contains("is the client-id of source 'ebuzima'"));
    }

    @Test
    @DisplayName("a subscriber's source-system is not a source → error")
    void unknownSourceSystem() {
        BecknProperties.Subscriber s = new BecknProperties.Subscriber(OPENMRS_BAP_ID, "u", OPENMRS_CONTRACT,
                OPENMRS_TXN, null, "nope");
        assertThat(validate(properties(true, "cce-beckn-endpoint", List.of(s)), DEFAULT_SOURCES))
                .anySatisfy(e -> assertThat(e).contains("'nope' is not a key under cce.emitter.sources"));
    }

    @Test
    @DisplayName("a commitment file that cannot be read → error")
    void missingCommitmentFile() {
        assertThat(validate(properties(true, "cce-beckn-endpoint", "bap.openhim.example.org",
                "classpath:beckn/nope.json", List.of(openmrsSubscriber())), DEFAULT_SOURCES))
                .anySatisfy(e -> assertThat(e).contains("nope.json"));
    }

    @Test
    @DisplayName("beckn.self.bap-id longer than 100 characters → error")
    void bapIdTooLong() {
        assertThat(validate(properties(true, "cce-beckn-endpoint", "b".repeat(101),
                "classpath:beckn/commitment-cce.json", List.of(openmrsSubscriber())), DEFAULT_SOURCES))
                .anySatisfy(e -> assertThat(e).contains("longer than 100"));
    }

    @Test
    @DisplayName("a subscriber's source has output: collector → warning only")
    void collectorSourceWarns() {
        EmitterProperties emitter = sources(Map.of(OPENMRS_SOURCE, new SourceProperties(null)));
        assertThat(validate(properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber())), emitter)).isEmpty();
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("output: collector"));
    }

    // ==================== Binding and fail-fast in a Spring context ====================

    @Configuration
    @EnableConfigurationProperties({BecknProperties.class, EmitterProperties.class})
    @Import(BecknStartupValidator.class)
    static class ValidatorConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(ValidatorConfig.class);

    private static final String[] ENABLED = {
            "beckn.enabled=true",
            "beckn.onix.bap-caller-url=http://onix-bap/bap/caller",
            "beckn.onix.bpp-caller-url=http://onix-bpp/bpp/caller",
            "beckn.self.bap-id=bap.openhim.example.org", "beckn.self.bap-uri=https://hie/bap",
            "beckn.self.bpp-id=bpp.openhim.example.org", "beckn.self.bpp-uri=https://hie/bpp",
            "beckn.cce.bpp-id=bpp.mdtlabs.org", "beckn.cce.bpp-uri=https://cce/bpp",
            "beckn.cce.contract-id=c", "beckn.cce.transaction-id=t",
            "beckn.openhim-channel.url=http://openhim/cce-beckn",
            "beckn.subscribers[0].subscriber-id=" + OPENMRS_BAP_ID,
            "beckn.subscribers[0].subscriber-uri=https://openmrs/bap",
            "beckn.subscribers[0].contract-id=" + OPENMRS_CONTRACT,
            "beckn.subscribers[0].transaction-id=" + OPENMRS_TXN,
            "beckn.subscribers[0].source-system=" + OPENMRS_SOURCE,
            "cce.emitter.sources.ebuzima.client-id=ebuzima-emr-client",
            "cce.emitter.sources." + OPENMRS_SOURCE + ".output=beckn",
    };

    @Test
    @DisplayName("binds the settings (defaults, output: beckn) and starts")
    void bindsAndStarts() {
        runner.withPropertyValues(ENABLED)
                .withPropertyValues("beckn.openhim-channel.username=cce-beckn-endpoint")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    BecknProperties p = ctx.getBean(BecknProperties.class);
                    assertThat(p.onix().timeout()).isEqualTo(25000);
                    assertThat(p.openhimChannel().timeout()).isEqualTo(35000);
                    assertThat(p.cce().retry().maxAttempts()).isEqualTo(1);
                    assertThat(p.cce().commitmentFile()).isEqualTo("classpath:beckn/commitment-cce.json");
                    EmitterProperties e = ctx.getBean(EmitterProperties.class);
                    assertThat(e.sources().get(OPENMRS_SOURCE).output()).isEqualTo(Output.BECKN);
                    // No explicit output: it follows beckn.enabled, which is on here.
                    assertThat(e.sources().get("ebuzima").output()).isNull();
                    assertThat(e.sources().get("ebuzima").effectiveOutput(p.enabled())).isEqualTo(Output.BECKN);
                });
    }

    @Test
    @DisplayName("fails to start when the Beckn client id equals a source client-id")
    void failsFastOnClientIdClash() {
        runner.withPropertyValues(ENABLED)
                .withPropertyValues("beckn.openhim-channel.username=ebuzima-emr-client")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("is the client-id of source 'ebuzima'"));
    }

    @Test
    @DisplayName("fails to start with output: beckn while beckn.enabled=false")
    void failsFastWhenDisabled() {
        runner.withPropertyValues("cce.emitter.sources.x.output=beckn")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
