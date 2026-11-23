package org.openphc.cce.emitter.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Type-safe configuration properties for source system adaptor routing.
 *
 * <p>Binds to the {@code cce.emitter.*} namespace in {@code application.yml}.
 * Each entry in {@code sources} maps a source system key (e.g., {@code "ebuzima"})
 * to its routing configuration.
 *
 * @param sources map of source system key → {@link SourceProperties}
 */
@ConfigurationProperties(prefix = "cce.emitter")
public record EmitterProperties(
        Map<String, SourceProperties> sources,
        String patientIdentifierSystem
) {

    /** Where the CloudEvents of a source go. */
    public enum Output {
        /** POST to the CCE Collector ({@code {cce.collector.url}/v1/events}). Today's behaviour. */
        COLLECTOR,
        /** Send to CCE as the {@code cce:CceClinicalEvent} of a Sygnal {@code sygnal/event/report}. */
        BECKN
    }

    /**
     * Configuration for a single source system.
     *
     * @param clientId the OpenHIM client ID used for header-based routing
     * @param output   where this source's CloudEvents go; {@code null} when not set, which follows
     *                 {@code beckn.enabled} (see {@link #effectiveOutput(boolean)})
     */
    public record SourceProperties(
            String clientId,
            Output output
    ) {
        @ConstructorBinding
        public SourceProperties {
        }

        /** A source that posts to the CCE Collector. */
        public SourceProperties(String clientId) {
            this(clientId, Output.COLLECTOR);
        }

        /**
         * The output in use. An explicit {@code output} wins. Without one, the source follows the
         * Beckn switch: {@code beckn} when {@code beckn.enabled} is true (the default on the
         * {@code demo-beckn} branch), else {@code collector}.
         */
        public Output effectiveOutput(boolean becknEnabled) {
            if (output != null) {
                return output;
            }
            return becknEnabled ? Output.BECKN : Output.COLLECTOR;
        }
    }
}
