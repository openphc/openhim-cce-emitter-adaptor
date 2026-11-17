package org.openphc.cce.emitter.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.controller.BecknBapWebhookController;
import org.openphc.cce.emitter.controller.BecknBppWebhookController;
import org.openphc.cce.emitter.service.BecknForwardingService;
import org.openphc.cce.emitter.service.BecknInboundService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** With beckn.enabled=false (set by the integration profile), the Beckn role is off: no endpoints, no output, every source on collector. */
@SpringBootTest
@ActiveProfiles("integration")
class BecknDisabledIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("beckn.enabled=false: no Beckn bean exists")
    void becknIsOff() {
        assertThat(context.getBean(BecknProperties.class).enabled()).isFalse();
        assertThat(context.getBeanNamesForType(BecknBppWebhookController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(BecknBapWebhookController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(BecknInboundService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(BecknForwardingService.class)).isEmpty();
    }

    @Test
    @DisplayName("with Beckn off, every source without an explicit output uses the collector")
    void sourcesFallBackToCollector() {
        assertThat(context.getBean(EmitterProperties.class).sources().values())
                .allSatisfy(s -> assertThat(s.effectiveOutput(false)).isEqualTo(EmitterProperties.Output.COLLECTOR));
    }
}
