package org.openphc.cce.emitter.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Posts the unwrapped FHIR resource to the OpenHIM channel ({@code beckn.openhim-channel.url}) with
 * the unwrap endpoint's OpenHIM client and the headers of spec §3.2, and reads the answer.
 *
 * <p>OpenHIM Core unpacks a mediator's {@code application/json+openhim} envelope and returns its
 * {@code response.status} and {@code response.body}. When the answer is still an envelope (for
 * example the channel URL points straight at the adaptor), it is unpacked here the same way.
 * Never throws: no answer is {@link HttpAnswer#unreachable}.
 */
@Component
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenHimChannelClient {

    private final RestClient client;
    private final BecknProperties props;
    private final ObjectMapper mapper;
    private final Timer latency;

    public OpenHimChannelClient(@Qualifier("openHimChannelRestClient") RestClient client, BecknProperties props,
                                ObjectMapper mapper, MeterRegistry meterRegistry) {
        this.client = client;
        this.props = props;
        this.mapper = mapper;
        this.latency = Timer.builder("cce.emitter.beckn.channel.latency")
                .description("Unwrap endpoint -> OpenHIM channel -> answer")
                .register(meterRegistry);
    }

    public HttpAnswer post(JsonNode resource, Map<String, String> headers) {
        return latency.record(() -> doPost(resource, headers));
    }

    private HttpAnswer doPost(JsonNode resource, Map<String, String> headers) {
        try {
            HttpAnswer answer = client.post()
                    .uri(props.openhimChannel().url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> headers.forEach(h::set))
                    .body(mapper.writeValueAsString(resource))
                    .exchange((request, response) -> {
                        byte[] bytes = response.getBody().readAllBytes();
                        MediaType type = response.getHeaders().getContentType();
                        return HttpAnswer.of(response.getStatusCode().value(),
                                type != null ? type.toString() : null,
                                new String(bytes, StandardCharsets.UTF_8));
                    });
            return unpackEnvelope(answer);
        } catch (Exception e) {
            return HttpAnswer.unreachable(BecknOnixClient.rootMessage(e));
        }
    }

    /** Unpacks an {@code application/json+openhim} envelope into its {@code response.status} and body. */
    HttpAnswer unpackEnvelope(HttpAnswer answer) {
        String type = answer.contentType() != null ? answer.contentType() : "";
        String body = answer.body();
        if (body == null || body.isBlank()) {
            return answer;
        }
        boolean looksLikeEnvelope = type.contains("json+openhim") || body.contains("\"x-mediator-urn\"");
        if (!looksLikeEnvelope) {
            return answer;
        }
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode response = node.path("response");
            if (!node.has("x-mediator-urn") || !response.path("status").canConvertToInt()) {
                return answer;
            }
            JsonNode inner = response.path("body");
            String innerBody = inner.isTextual() ? inner.asText() : inner.isMissingNode() || inner.isNull() ? "" : inner.toString();
            return HttpAnswer.of(response.path("status").asInt(), MediaType.APPLICATION_JSON_VALUE, innerBody);
        } catch (Exception e) {
            return answer;
        }
    }
}
