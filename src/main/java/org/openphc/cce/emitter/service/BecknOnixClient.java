package org.openphc.cce.emitter.service;

import java.net.URI;
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
 * Posts Sygnal messages to the caller of {@code onix-openhim}
 * ({@code {beckn.onix.caller-url}/sygnal/event/report}). onix signs the message and sends it to
 * {@code context.receiverUri} + {@code /} + action. The action holds slashes; it is appended to the
 * URL as it is (never as a URI template variable, which would encode them as {@code %2F}).
 * Never throws for a bad answer or a missing one; the {@link HttpAnswer} says what happened. Times
 * each call as {@code cce.emitter.beckn.onix.latency}.
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

    /** POST to {@code onix-openhim}'s caller. */
    public HttpAnswer post(String action, JsonNode message) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return client.post()
                    .uri(url(props.onix().callerUrl(), action))
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
                    .description("Round trip to onix-openhim's Sygnal caller")
                    .tag("action", action)
                    .register(meterRegistry));
        }
    }

    /** {@code <caller url>/<action>}, with exactly one slash between them. */
    static URI url(String callerUrl, String action) {
        String a = action.startsWith("/") ? action.substring(1) : action;
        return URI.create(stripSlash(callerUrl) + "/" + a);
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
