package org.openphc.cce.emitter.service;

import java.util.Map;
import java.util.Optional;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.config.CollectorProperties;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.Output;
import org.openphc.cce.emitter.model.CloudEventDto;
import org.openphc.cce.emitter.model.CollectorResponse;
import org.springframework.stereotype.Service;

/**
 * Picks the output of a CloudEvent by its source ({@code cce.emitter.sources.<key>.output}, spec
 * §5.1): {@link CollectorForwardingService} for {@code collector} (the default, today's behaviour)
 * or {@link BecknForwardingService} for {@code beckn} (a {@code sygnal/event/report} to CCE).
 */
@Service
public class EventForwardingRouter {

    static final String COLLECTOR_ORCHESTRATION = "Forward to CCE Collector";
    static final String OPENHIM_EMITTER_BECKN_ORCHESTRATION = "Forward to CCE over Sygnal";
    static final String DEFAULT_CALLER_URL = "http://onix-openhim:8082/sygnal/caller";

    private final Map<String, EmitterProperties.SourceProperties> sources;
    private final CollectorForwardingService collectorForwardingService;
    private final Optional<BecknForwardingService> becknForwardingService;
    private final CollectorProperties collectorProperties;
    private final BecknProperties becknProperties;

    public EventForwardingRouter(EmitterProperties emitterProperties,
                                 CollectorForwardingService collectorForwardingService,
                                 Optional<BecknForwardingService> becknForwardingService,
                                 CollectorProperties collectorProperties,
                                 Optional<BecknProperties> becknProperties) {
        this.sources = emitterProperties.sources() != null ? emitterProperties.sources() : Map.of();
        this.collectorForwardingService = collectorForwardingService;
        this.becknForwardingService = becknForwardingService;
        this.collectorProperties = collectorProperties;
        this.becknProperties = becknProperties.orElse(null);
    }

    /** The output of the CloudEvent's source; {@code collector} for an unknown source. */
    public Output outputFor(CloudEventDto event) {
        EmitterProperties.SourceProperties source = event.getSource() != null ? sources.get(event.getSource()) : null;
        boolean becknEnabled = becknProperties != null && becknProperties.enabled();
        return source != null ? source.effectiveOutput(becknEnabled) : Output.COLLECTOR;
    }

    /**
     * Sends the CloudEvent to its source's output. Both outputs give the same results and throw
     * the same exceptions (spec §5.4).
     */
    public CollectorResponse forward(CloudEventDto event) {
        if (outputFor(event) == Output.BECKN) {
            return becknForwardingService
                    .orElseThrow(() -> new IllegalStateException(
                            "Source '" + event.getSource() + "' has output: beckn but beckn.enabled is false"))
                    .forward(event);
        }
        return collectorForwardingService.forward(event);
    }

    /** The OpenHIM orchestration name of the output. */
    public String orchestrationName(Output output) {
        return output == Output.BECKN ? OPENHIM_EMITTER_BECKN_ORCHESTRATION : COLLECTOR_ORCHESTRATION;
    }

    /** The OpenHIM orchestration request path of the output. */
    public String orchestrationPath(Output output) {
        if (output == Output.BECKN) {
            String caller = becknProperties != null && becknProperties.onix().callerUrl() != null
                    ? becknProperties.onix().callerUrl() : DEFAULT_CALLER_URL;
            return pathOf(caller) + "/" + BecknMessageFactory.EVENT_REPORT;
        }
        return collectorProperties.eventsPath();
    }

    private static String pathOf(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int scheme = u.indexOf("://");
        if (scheme < 0) {
            return u;
        }
        int slash = u.indexOf('/', scheme + 3);
        return slash < 0 ? "" : u.substring(slash);
    }
}
