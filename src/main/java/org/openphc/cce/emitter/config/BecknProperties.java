package org.openphc.cce.emitter.config;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Beckn role of this adaptor ({@code beckn.*}). See {@code docs/beckn-integration.md} §7.
 *
 * @param enabled        turns on both Beckn endpoints and allows {@code output: beckn}. Default {@code true} on the {@code demo-beckn} branch
 * @param schemaBaseUrl  start of the {@code @context} address of the {@code CceClinicalEvent} box
 * @param onix           the two {@code openhim-onix} callers
 * @param self           OpenHIM's own Beckn ids
 * @param cce            CCE's ids and the HIE's contract with CCE (issued by CCE)
 * @param openhimChannel the OpenHIM channel the unwrap endpoint posts to
 * @param subscribers    one entry per HMIS
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
     * @param bapCallerUrl {@code onix-bap}'s caller. The adaptor appends {@code /update}
     * @param bppCallerUrl {@code onix-bpp}'s caller. The adaptor appends {@code /on_update}
     * @param timeout      connect and read timeout for both onix calls, in ms
     */
    public record Onix(
            String bapCallerUrl,
            String bppCallerUrl,
            @DefaultValue("25000") int timeout
    ) {}

    /** OpenHIM's consumer id and URI (towards CCE) and provider id and URI (towards the HMIS systems). */
    public record Self(
            String networkId,
            String bapId,
            String bapUri,
            String bppId,
            String bppUri
    ) {}

    /** CCE's provider id and URI, and the ids and commitment of the HIE's contract with CCE. */
    public record Cce(
            String bppId,
            String bppUri,
            String contractId,
            String transactionId,
            @DefaultValue("classpath:beckn/commitment-cce.json") String commitmentFile,
            @DefaultValue Retry retry
    ) {}

    /** @param maxAttempts tries per {@code update} to CCE. Default 1 (no retry, §8.4) */
    public record Retry(@DefaultValue("1") int maxAttempts) {}

    /**
     * @param url      the channel the unwrap endpoint posts the FHIR resource to
     * @param username the OpenHIM client of the unwrap endpoint
     * @param password its password
     * @param timeout  wait for the channel's answer, in ms
     */
    public record OpenHimChannel(
            String url,
            String username,
            String password,
            @DefaultValue("35000") int timeout
    ) {}

    /** One HMIS: its Beckn id and URI, its HIE feed ids and commitment, and the source key its events use. */
    public record Subscriber(
            String subscriberId,
            String subscriberUri,
            String contractId,
            String transactionId,
            String commitmentFile,
            String sourceSystem
    ) {}

    /** The subscriber whose {@code subscriber-id} is the given {@code bapId}. */
    public Optional<Subscriber> subscriber(String bapId) {
        if (bapId == null || bapId.isBlank()) {
            return Optional.empty();
        }
        return subscribers.stream().filter(s -> bapId.equals(s.subscriberId())).findFirst();
    }
}
