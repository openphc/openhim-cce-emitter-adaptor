package org.openphc.cce.emitter.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties.Output;
import org.openphc.cce.emitter.config.EmitterProperties.SourceProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** On the demo-beckn branch the Beckn role is on by default, and sources follow it. */
class BecknDefaultsTest {

    @Test
    @DisplayName("beckn.enabled is true when not set")
    void becknOnByDefault() {
        BecknProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("beckn", BecknProperties.class);
        assertThat(props.enabled()).isTrue();
    }

    @Test
    @DisplayName("a source without an output follows beckn.enabled")
    void unsetOutputFollowsTheSwitch() {
        SourceProperties source = new SourceProperties("client", null);
        assertThat(source.effectiveOutput(true)).isEqualTo(Output.BECKN);
        assertThat(source.effectiveOutput(false)).isEqualTo(Output.COLLECTOR);
    }

    @Test
    @DisplayName("an explicit output wins")
    void explicitOutputWins() {
        assertThat(new SourceProperties("client", Output.COLLECTOR).effectiveOutput(true)).isEqualTo(Output.COLLECTOR);
        assertThat(new SourceProperties("client", Output.BECKN).effectiveOutput(false)).isEqualTo(Output.BECKN);
    }
}
