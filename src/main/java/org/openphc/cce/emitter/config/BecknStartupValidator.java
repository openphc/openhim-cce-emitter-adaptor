package org.openphc.cce.emitter.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * The startup checks of {@code docs/beckn-integration.md} §7. Any error stops the start, with
 * every error found in the message. Always active, so that {@code output: beckn} with
 * {@code beckn.enabled=false} is caught too.
 */
@Component
public class BecknStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(BecknStartupValidator.class);

    static final int MAX_BAP_ID = 100;

    public BecknStartupValidator(BecknProperties beckn, EmitterProperties emitter,
                                 ResourceLoader resourceLoader, ObjectMapper mapper) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = validate(beckn, emitter, resourceLoader, mapper, warnings);
        warnings.forEach(w -> log.warn("Beckn settings: {}", w));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid Beckn settings: " + String.join("; ", errors));
        }
        if (beckn.enabled()) {
            log.info("Beckn enabled: {} subscriber(s), bapId='{}', CCE bppId='{}'",
                    beckn.subscribers().size(), beckn.self().bapId(), beckn.cce().bppId());
        }
    }

    /**
     * Runs all checks.
     *
     * @param warnings collects the warnings (allowed, logged)
     * @return the errors; empty when the settings are fine
     */
    public static List<String> validate(BecknProperties beckn, EmitterProperties emitter,
                                        ResourceLoader resourceLoader, ObjectMapper mapper, List<String> warnings) {
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
        require(errors, beckn.onix().bapCallerUrl(), "beckn.onix.bap-caller-url");
        require(errors, beckn.onix().bppCallerUrl(), "beckn.onix.bpp-caller-url");
        require(errors, beckn.self().bapId(), "beckn.self.bap-id");
        require(errors, beckn.self().bapUri(), "beckn.self.bap-uri");
        require(errors, beckn.self().bppId(), "beckn.self.bpp-id");
        require(errors, beckn.self().bppUri(), "beckn.self.bpp-uri");
        require(errors, beckn.cce().bppId(), "beckn.cce.bpp-id");
        require(errors, beckn.cce().bppUri(), "beckn.cce.bpp-uri");
        require(errors, beckn.cce().contractId(), "beckn.cce.contract-id");
        require(errors, beckn.cce().transactionId(), "beckn.cce.transaction-id");
        require(errors, beckn.openhimChannel().url(), "beckn.openhim-channel.url");
        require(errors, beckn.openhimChannel().username(), "beckn.openhim-channel.username");
        if (beckn.cce().retry().maxAttempts() < 1) {
            errors.add("beckn.cce.retry.max-attempts must be at least 1");
        }

        // bapId becomes CCE's CloudEvent source, at most 100 characters
        String bapId = beckn.self().bapId();
        if (bapId != null && bapId.length() > MAX_BAP_ID) {
            errors.add("beckn.self.bap-id is longer than " + MAX_BAP_ID + " characters; CCE would refuse every update");
        }

        // The unwrap endpoint's OpenHIM client must not be a source's client id (spec §3.2)
        String channelClient = beckn.openhimChannel().username();
        if (channelClient != null && !channelClient.isBlank()) {
            sources.forEach((key, source) -> {
                if (channelClient.equals(source.clientId())) {
                    errors.add("beckn.openhim-channel.username '" + channelClient + "' is the client-id of source '"
                            + key + "'; X-OpenHIM-ClientID would credit every Beckn event to that source");
                }
            });
        }

        // Subscribers
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < beckn.subscribers().size(); i++) {
            BecknProperties.Subscriber s = beckn.subscribers().get(i);
            String name = "beckn.subscribers[" + i + "]";
            require(errors, s.subscriberId(), name + ".subscriber-id");
            require(errors, s.subscriberUri(), name + ".subscriber-uri");
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
            checkFile(errors, resourceLoader, mapper, BecknCommitments.subscriberFile(s));
        }

        checkFile(errors, resourceLoader, mapper, beckn.cce().commitmentFile());
        return errors;
    }

    private static void require(List<String> errors, String value, String name) {
        if (value == null || value.isBlank()) {
            errors.add(name + " is not set");
        }
    }

    private static void checkFile(List<String> errors, ResourceLoader loader, ObjectMapper mapper, String location) {
        try {
            BecknCommitments.read(loader, mapper, location);
        } catch (IllegalStateException e) {
            errors.add(e.getMessage());
        }
    }
}
