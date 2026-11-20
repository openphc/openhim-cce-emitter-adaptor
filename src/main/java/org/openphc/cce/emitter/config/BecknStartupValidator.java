package org.openphc.cce.emitter.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The startup checks of {@code docs/beckn-integration.md} §7. Any error stops the start, with
 * every error found in the message. Always active, so that {@code output: beckn} with
 * {@code beckn.enabled=false} is caught too.
 */
@Component
public class BecknStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(BecknStartupValidator.class);

    /** {@code beckn.self.id} becomes CCE's CloudEvent {@code source}, which holds at most 100 characters. */
    static final int MAX_SELF_ID = 100;

    /** onix waits this long for this adaptor's answer ({@code responseHeaderTimeout} of the receiver). */
    static final int ONIX_RECEIVER_WAIT_MS = 30000;

    public BecknStartupValidator(BecknProperties beckn, EmitterProperties emitter) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = validate(beckn, emitter, warnings);
        warnings.forEach(w -> log.warn("Sygnal settings: {}", w));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid Sygnal settings: " + String.join("; ", errors));
        }
        if (beckn.enabled()) {
            log.info("Sygnal enabled: {} subscriber(s), self id='{}', CCE id='{}'",
                    beckn.subscribers().size(), beckn.self().id(), beckn.cce().id());
        }
    }

    /**
     * Runs all checks.
     *
     * @param warnings collects the warnings (allowed, logged)
     * @return the errors; empty when the settings are fine
     */
    public static List<String> validate(BecknProperties beckn, EmitterProperties emitter, List<String> warnings) {
        List<String> errors = new ArrayList<>();
        Map<String, EmitterProperties.SourceProperties> sources =
                emitter.sources() != null ? emitter.sources() : Map.of();

        sources.forEach((key, source) -> {
            if (source.output() == EmitterProperties.Output.BECKN && !beckn.enabled()) {
                errors.add("source '" + key + "' has output: beckn but beckn.enabled is false");
            }
        });
        if (!beckn.enabled()) {
            return errors;
        }

        // Required settings
        require(errors, beckn.onix().callerUrl(), "beckn.onix.caller-url");
        require(errors, beckn.self().id(), "beckn.self.id");
        require(errors, beckn.self().networkId(), "beckn.self.network-id");
        require(errors, beckn.cce().id(), "beckn.cce.id");
        require(errors, beckn.cce().uri(), "beckn.cce.uri");
        require(errors, beckn.openhimChannel().url(), "beckn.openhim-channel.url");
        require(errors, beckn.openhimChannel().username(), "beckn.openhim-channel.username");
        if (beckn.cce().retry().maxAttempts() < 1) {
            errors.add("beckn.cce.retry.max-attempts must be at least 1");
        }

        // self.id becomes CCE's CloudEvent source, at most 100 characters
        String selfId = beckn.self().id();
        if (selfId != null && selfId.length() > MAX_SELF_ID) {
            errors.add("beckn.self.id is longer than " + MAX_SELF_ID + " characters; CCE would refuse every report");
        }

        // Each hop must give up before the hop around it (§8.3)
        int channelTimeout = beckn.openhimChannel().timeout();
        long cceBudget = (long) Math.max(1, beckn.cce().retry().maxAttempts()) * beckn.onix().timeout();
        if (cceBudget >= channelTimeout) {
            warnings.add("beckn.onix.timeout x beckn.cce.retry.max-attempts (" + cceBudget
                    + " ms) is not below beckn.openhim-channel.timeout (" + channelTimeout
                    + " ms); the HMIS may get NET_DOWNSTREAM_UNAVAILABLE for an event CCE took");
        }
        if (channelTimeout >= ONIX_RECEIVER_WAIT_MS) {
            warnings.add("beckn.openhim-channel.timeout (" + channelTimeout + " ms) is not below the "
                    + ONIX_RECEIVER_WAIT_MS + " ms onix waits for this adaptor's answer");
        }

        // The endpoint's OpenHIM client must not be a source's client id (spec §3.2)
        String channelClient = beckn.openhimChannel().username();
        if (channelClient != null && !channelClient.isBlank()) {
            sources.forEach((key, source) -> {
                if (channelClient.equals(source.clientId())) {
                    errors.add("beckn.openhim-channel.username '" + channelClient + "' is the client-id of source '"
                            + key + "'; X-OpenHIM-ClientID would credit every Sygnal event to that source");
                }
            });
        }

        // Subscribers
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < beckn.subscribers().size(); i++) {
            BecknProperties.Subscriber s = beckn.subscribers().get(i);
            String name = "beckn.subscribers[" + i + "]";
            require(errors, s.subscriberId(), name + ".subscriber-id");
            require(errors, s.sourceSystem(), name + ".source-system");
            if (s.subscriberId() != null && !ids.add(s.subscriberId())) {
                errors.add(name + ".subscriber-id '" + s.subscriberId() + "' is listed twice");
            }
            if (s.sourceSystem() != null && !s.sourceSystem().isBlank()) {
                Optional<Map.Entry<String, EmitterProperties.SourceProperties>> source = sources.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(s.sourceSystem()))
                        .findFirst();
                if (source.isEmpty()) {
                    errors.add(name + ".source-system '" + s.sourceSystem() + "' is not a key under cce.emitter.sources");
                } else if (source.get().getValue().effectiveOutput(true) == EmitterProperties.Output.COLLECTOR) {
                    warnings.add("subscriber '" + s.subscriberId() + "' uses source '" + source.get().getKey()
                            + "' with output: collector; its events go to the CCE Collector directly");
                }
            }
        }
        return errors;
    }

    private static void require(List<String> errors, String value, String name) {
        if (value == null || value.isBlank()) {
            errors.add(name + " is not set");
        }
    }
}
