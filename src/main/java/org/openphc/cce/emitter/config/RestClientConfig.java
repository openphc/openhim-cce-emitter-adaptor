package org.openphc.cce.emitter.config;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.openphc.cce.emitter.openhim.OpenHimAuthInterceptor;
import org.openphc.cce.emitter.service.CollectorTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures {@link RestClient} beans for outbound HTTP communication.
 *
 * <p>Two named beans are created:
 * <ul>
 *   <li>{@code collectorRestClient} — targets the CCE Collector with JSON content type and configurable timeout</li>
 *   <li>{@code coreApiRestClient} — targets the OpenHIM Core API with Basic Auth</li>
 *   <li>{@code becknOnixRestClient} — {@code openhim-onix} callers (only with {@code beckn.enabled=true})</li>
 *   <li>{@code openHimChannelRestClient} — the OpenHIM Beckn channel (only with {@code beckn.enabled=true})</li>
 * </ul>
 */
@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    /**
     * Creates a {@link RestClient} for forwarding CloudEvents to the CCE Collector.
     *
     * <p>Configured with:
     * <ul>
     *   <li>Base URL from {@link CollectorProperties#url()}</li>
     *   <li>JSON content type default header</li>
     *   <li>Connect + read timeout from {@link CollectorProperties#timeout()}</li>
     *   <li>Dynamic Bearer token via {@link CollectorTokenService} (OAuth2 or static fallback)</li>
     * </ul>
     *
     * @param properties   Collector configuration properties
     * @param tokenService token provider for Collector authentication
     * @return configured RestClient for Collector communication
     */
    @Bean
    @Qualifier("collectorRestClient")
    public RestClient collectorRestClient(CollectorProperties properties, CollectorTokenService tokenService) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.timeout()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.timeout()));

        ClientHttpRequestInterceptor authInterceptor = (request, body, execution) -> {
            String token = tokenService.getToken();
            if (token != null && !token.isBlank()) {
                request.getHeaders().setBearerAuth(token);
            }
            return execution.execute(request, body);
        };

        return RestClient.builder()
                .baseUrl(properties.url())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .requestInterceptor(authInterceptor)
                .build();
    }

    /**
     * Creates a {@link RestClient} for communicating with the OpenHIM Core API.
     *
     * <p>Supports two authentication modes based on {@code openhim.core.auth-type}:
     * <ul>
     *   <li>{@code basic} (default) — HTTP Basic Auth (for local OpenHIM with {@code api_authenticationTypes=["local"]})</li>
     *   <li>{@code token} — OpenHIM challenge-response auth via {@link OpenHimAuthInterceptor}</li>
     * </ul>
     *
     * @param properties OpenHIM configuration properties
     * @return configured RestClient for OpenHIM Core API communication
     */
    @Bean
    @Qualifier("coreApiRestClient")
    public RestClient coreApiRestClient(OpenHimProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = createTrustAllRequestFactory();

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.core().apiUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory);

        if (properties.core().isTokenAuth()) {
            log.info("OpenHIM Core API auth: token (challenge-response)");
            RestClient authRestClient = RestClient.builder()
                    .baseUrl(properties.core().apiUrl())
                    .requestFactory(requestFactory)
                    .build();
            builder.requestInterceptor(
                    new OpenHimAuthInterceptor(properties.core(), authRestClient));
        } else {
            log.info("OpenHIM Core API auth: basic");
            String credentials = properties.core().username() + ":" + properties.core().password();
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
            builder.defaultHeader("Authorization", basicAuth);
        }

        return builder.build();
    }

    /**
     * Creates a {@link RestClient} for the two {@code openhim-onix} callers ({@code onix-bap} and
     * {@code onix-bpp}). Absolute URLs are used per call. No auth: the adapter signs the message.
     * Connect and read timeout from {@code beckn.onix.timeout} (spec §8.3).
     */
    @Bean
    @Qualifier("becknOnixRestClient")
    @ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RestClient becknOnixRestClient(BecknProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.onix().timeout()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.onix().timeout()));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Creates a {@link RestClient} for the OpenHIM channel the Beckn unwrap endpoint posts to,
     * with the endpoint's OpenHIM client (Basic Auth) and {@code beckn.openhim-channel.timeout}.
     * Trusts OpenHIM Core's self-signed certificate, like {@code coreApiRestClient}.
     */
    @Bean
    @Qualifier("openHimChannelRestClient")
    @ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RestClient openHimChannelRestClient(BecknProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = createTrustAllRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.openhimChannel().timeout()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.openhimChannel().timeout()));
        String password = properties.openhimChannel().password() != null ? properties.openhimChannel().password() : "";
        String credentials = properties.openhimChannel().username() + ":" + password;
        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .build();
    }

    /**
     * Creates a {@link SimpleClientHttpRequestFactory} that trusts all SSL certificates.
     * Required because OpenHIM Core uses a self-signed certificate on its API port.
     */
    private SimpleClientHttpRequestFactory createTrustAllRequestFactory() {
        try {
            TrustManager[] trustAllCerts = { new X509TrustManager() {
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }};

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            return new SimpleClientHttpRequestFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.warn("Failed to configure trust-all SSL context for OpenHIM Core API: {}", e.getMessage());
            return new SimpleClientHttpRequestFactory();
        }
    }
}
