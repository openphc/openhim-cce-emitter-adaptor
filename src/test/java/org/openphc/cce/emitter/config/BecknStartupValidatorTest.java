package org.openphc.cce.emitter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openphc.cce.emitter.beckn.BecknTestSupport.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

/** The startup checks of spec §7. */
class BecknStartupValidatorTest {

    private final List<String> warnings = new ArrayList<>();

    private static EmitterProperties sources(Map<String, SourceProperties> sources) {
        return new EmitterProperties(sources, null);
    }

    private static final EmitterProperties DEFAULT_SOURCES = sources(Map.of(
            "ebuzima", new SourceProperties("ebuzima-emr-client"),
            OPENMRS_SOURCE, new SourceProperties(null, Output.BECKN)));

    private List<String> validate(BecknProperties beckn, EmitterProperties emitter) {
        return BecknStartupValidator.validate(beckn, emitter, warnings);
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
        BecknProperties.Subscriber s = new BecknProperties.Subscriber(OPENMRS_ID, "nope");
        assertThat(validate(properties(true, "cce-beckn-endpoint", List.of(s)), DEFAULT_SOURCES))
                .anySatisfy(e -> assertThat(e).contains("'nope' is not a key under cce.emitter.sources"));
    }

    @Test
    @DisplayName("beckn.self.id longer than 100 characters (CCE's CloudEvent source) → error")
    void selfIdTooLong() {
        assertThat(validate(properties(true, "cce-beckn-endpoint", "b".repeat(101), List.of(openmrsSubscriber())),
                DEFAULT_SOURCES))
                .anySatisfy(e -> assertThat(e).contains("beckn.self.id is longer than 100"));
    }

    @Test
    @DisplayName("missing ids → one error per setting")
    void missingIds() {
        BecknProperties p = properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber()));
        BecknProperties empty = new BecknProperties(true, p.schemaBaseUrl(), new BecknProperties.Onix("", 20000),
                new BecknProperties.Self(null, ""), new BecknProperties.Cce(null, " ", p.cce().retry()),
                p.openhimChannel(), p.subscribers());
        assertThat(validate(empty, DEFAULT_SOURCES)).contains(
                "beckn.onix.caller-url is not set", "beckn.self.id is not set", "beckn.self.network-id is not set",
                "beckn.cce.id is not set", "beckn.cce.uri is not set");
    }

    @Test
    @DisplayName("a subscriber listed twice → error")
    void duplicateSubscriber() {
        assertThat(validate(properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber(), openmrsSubscriber())),
                DEFAULT_SOURCES))
                .anySatisfy(e -> assertThat(e).contains("is listed twice"));
    }

    @Test
    @DisplayName("timeouts that do not nest (§8.3) → warnings only")
    void timeoutsThatDoNotNest() {
        BecknProperties p = properties(true, "cce-beckn-endpoint", List.of(openmrsSubscriber()));
        BecknProperties slow = new BecknProperties(true, p.schemaBaseUrl(),
                new BecknProperties.Onix(p.onix().callerUrl(), 30000), p.self(), p.cce(),
                new BecknProperties.OpenHimChannel(p.openhimChannel().url(), p.openhimChannel().username(), "s", 30000),
                p.subscribers());
        assertThat(validate(slow, DEFAULT_SOURCES)).isEmpty();
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("is not below beckn.openhim-channel.timeout"));
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("30000 ms onix waits"));
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
            "beckn.self.id=bap.openhim.example.org", "beckn.self.network-id=" + NETWORK_ID,
            "beckn.cce.id=" + CCE_ID, "beckn.cce.uri=" + CCE_URI,
            "beckn.openhim-channel.url=http://openhim/cce-beckn",
            "beckn.subscribers[0].subscriber-id=" + OPENMRS_ID,
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
                    assertThat(p.onix().callerUrl()).isEqualTo("http://onix-openhim:8082/sygnal/caller");
                    assertThat(p.onix().timeout()).isEqualTo(20000);
                    assertThat(p.openhimChannel().timeout()).isEqualTo(25000);
                    assertThat(p.cce().retry().maxAttempts()).isEqualTo(1);
                    assertThat(p.subscriber(OPENMRS_ID)).contains(openmrsSubscriber());
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
