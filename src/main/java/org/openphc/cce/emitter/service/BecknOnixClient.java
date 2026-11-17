package org.openphc.cce.emitter.service;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.model.beckn.HttpAnswer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts Beckn messages to the {@code openhim-onix} callers: {@code onix-bap}
 * ({@code {beckn.onix.bap-caller-url}/update}) and {@code onix-bpp}
 * ({@code {beckn.onix.bpp-caller-url}/on_update}). Never throws for a bad answer or a missing one;
 * the {@link HttpAnswer} says what happened. Times each call as {@code cce.emitter.beckn.onix.latency}.
 */
@Component
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknOnixClient {

    private final RestClient client;
    private final BecknProperties props;
    private final MeterRegistry meterRegistry;

    public BecknOnixClient(@Qualifier("becknOnixRestClient") RestClient client, BecknProperties props,
                           MeterRegistry meterRegistry) {
        this.client = client;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    /** POST to {@code onix-bap}'s caller. */
    public HttpAnswer postToBap(String action, JsonNode message) {
        return post("bap", props.onix().bapCallerUrl(), action, message);
    }

    /** POST to {@code onix-bpp}'s caller. */
    public HttpAnswer postToBpp(String action, JsonNode message) {
        return post("bpp", props.onix().bppCallerUrl(), action, message);
    }

    private HttpAnswer post(String container, String callerUrl, String action, JsonNode message) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return client.post()
                    .uri(stripSlash(callerUrl) + "/" + action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(message)
                    .exchange((request, response) -> {
                        byte[] bytes = response.getBody().readAllBytes();
                        MediaType type = response.getHeaders().getContentType();
                        return HttpAnswer.of(response.getStatusCode().value(),
                                type != null ? type.toString() : null,
                                new String(bytes, StandardCharsets.UTF_8));
                    });
        } catch (RuntimeException e) {
            return HttpAnswer.unreachable(rootMessage(e));
        } finally {
            sample.stop(Timer.builder("cce.emitter.beckn.onix.latency")
                    .description("Round trip to onix-bap and onix-bpp")
                    .tag("container", container).tag("action", action)
                    .register(meterRegistry));
        }
    }

    static String stripSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The innermost cause, as {@code ClassName: message}. */
    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
