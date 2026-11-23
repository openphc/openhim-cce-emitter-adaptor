package org.openphc.cce.emitter.config;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Sygnal role of this adaptor ({@code beckn.*}). See {@code docs/beckn-integration.md} §7.
 *
 * @param enabled        turns on the {@code sygnal/event/report} endpoint and allows {@code output: beckn}. Default {@code true} on this branch
 * @param schemaBaseUrl  start of the {@code @context} address of the {@code cce:CceClinicalEvent}
 * @param onix           this HIE's onix container ({@code onix-openhim})
 * @param self           the HIE's own Sygnal identity
 * @param cce            CCE's Sygnal identity and the retry of the report to CCE
 * @param openhimChannel the OpenHIM channel the {@code sygnal/event/report} endpoint posts to
 * @param subscribers    one entry per HMIS that may send events
 */
@ConfigurationProperties(prefix = "beckn")
public record BecknProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema") String schemaBaseUrl,
        @DefaultValue Onix onix,
        @DefaultValue Self self,
        @DefaultValue Cce cce,
        @DefaultValue OpenHimChannel openhimChannel,
        List<Subscriber> subscribers
) {

    public BecknProperties {
        subscribers = subscribers == null ? List.of() : List.copyOf(subscribers);
    }

    /**
     * @param callerUrl the Sygnal caller of {@code onix-openhim}. The adaptor appends the action
     *                  ({@code /sygnal/event/report})
     * @param timeout   connect and read timeout of the call to the caller, in ms. Must stay below
     *                  {@code openhim-channel.timeout}
     */
    public record Onix(
            @DefaultValue("http://onix-openhim:8082/sygnal/caller") String callerUrl,
            @DefaultValue("20000") int timeout
    ) {}

    /**
     * @param id        the HIE's subscriber id: {@code context.senderId} towards CCE, the expected
     *                  {@code context.receiverId} from an HMIS. At most 100 characters (CCE's CloudEvent source)
     * @param networkId {@code context.networkId} of every message the HIE sends
     */
    public record Self(
            String id,
            String networkId
    ) {}

    /**
     * @param id    CCE's subscriber id: {@code context.receiverId} of the report
     * @param uri   CCE's public Sygnal receiver base: {@code context.receiverUri} (onix appends the action)
     * @param retry tries per report
     */
    public record Cce(
            String id,
            String uri,
            @DefaultValue Retry retry
    ) {}

    /** @param maxAttempts tries per report to CCE. Default 1 (no retry, §8.4) */
    public record Retry(@DefaultValue("1") int maxAttempts) {}

    /**
     * @param url      the channel the {@code sygnal/event/report} endpoint posts the FHIR resource to
     * @param username the OpenHIM client of that endpoint
     * @param password its password
     * @param timeout  wait for the channel's answer, in ms. Must stay below onix's
     *                 {@code responseHeaderTimeout} towards this adaptor (30 s)
     */
    public record OpenHimChannel(
            String url,
            String username,
            String password,
            @DefaultValue("25000") int timeout
    ) {}

    /**
     * One HMIS that may send {@code sygnal/event/report}: its subscriber id (the report's
     * {@code context.senderId}) and the source key its events use.
     */
    public record Subscriber(
            String subscriberId,
            String sourceSystem
    ) {}

    /** The subscriber whose {@code subscriber-id} is the given {@code senderId}. */
    public Optional<Subscriber> subscriber(String senderId) {
        if (senderId == null || senderId.isBlank()) {
            return Optional.empty();
        }
        return subscribers.stream().filter(s -> senderId.equals(s.subscriberId())).findFirst();
    }
}
