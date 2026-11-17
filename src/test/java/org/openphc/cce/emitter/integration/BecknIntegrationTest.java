package org.openphc.cce.emitter.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.openphc.cce.emitter.beckn.BecknTestSupport.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Beckn role end to end, with {@code beckn.enabled=true}. One WireMock server plays:
 * <ul>
 *   <li>the OpenHIM channel ({@code /inbound}): it proxies to this adaptor's own {@code /inbound}
 *       and adds {@code X-OpenHIM-ClientID}, as OpenHIM Core does; single tests replace it with a
 *       fixed answer (5xx, 401)</li>
 *   <li>{@code onix-bap} ({@code /bap/caller/update}) answering for CCE</li>
 *   <li>{@code onix-bpp} ({@code /bpp/caller/on_update}) receiving the HMIS's {@code on_update}</li>
 *   <li>the CCE Collector ({@code /v1/events}) for the sources that stay on {@code collector}</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class BecknIntegrationTest {

    private static final int ONIX_TIMEOUT_MS = 1500;

    private static WireMockServer wm;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void start() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort().containerThreads(40));
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + wm.port();
        r.add("cce.collector.url", () -> base);
        r.add("cce.collector.retry.backoff-ms", () -> "50");
        r.add("cce.emitter.sources." + OPENMRS_SOURCE + ".output", () -> "beckn");
        // With Beckn on, a source without an output would follow it; ebuzima stays on the collector explicitly.
        r.add("cce.emitter.sources.ebuzima.output", () -> "collector");
        r.add("beckn.enabled", () -> "true");
        r.add("beckn.onix.bap-caller-url", () -> base + "/bap/caller");
        r.add("beckn.onix.bpp-caller-url", () -> base + "/bpp/caller");
        r.add("beckn.onix.timeout", () -> String.valueOf(ONIX_TIMEOUT_MS));
        r.add("beckn.self.bap-id", () -> "bap.openhim.example.org");
        r.add("beckn.self.bap-uri", () -> "https://hie.example.org/bap/receiver");
        r.add("beckn.self.bpp-id", () -> "bpp.openhim.example.org");
        r.add("beckn.self.bpp-uri", () -> "https://hie.example.org/bpp/receiver");
        r.add("beckn.cce.bpp-id", () -> CCE_BPP_ID);
        r.add("beckn.cce.bpp-uri", () -> "https://cce.example.org/bpp/receiver");
        r.add("beckn.cce.contract-id", () -> CCE_CONTRACT);
        r.add("beckn.cce.transaction-id", () -> CCE_TXN);
        r.add("beckn.openhim-channel.url", () -> base + "/inbound");
        r.add("beckn.openhim-channel.username", () -> "cce-beckn-endpoint");
        r.add("beckn.openhim-channel.password", () -> "secret");
        r.add("beckn.openhim-channel.timeout", () -> "8000");
        r.add("beckn.subscribers[0].subscriber-id", () -> OPENMRS_BAP_ID);
        r.add("beckn.subscribers[0].subscriber-uri", () -> "https://openmrs.example.org/bap/receiver");
        r.add("beckn.subscribers[0].contract-id", () -> OPENMRS_CONTRACT);
        r.add("beckn.subscribers[0].transaction-id", () -> OPENMRS_TXN);
        r.add("beckn.subscribers[0].source-system", () -> OPENMRS_SOURCE);
    }

    @BeforeEach
    void stubChannelAndOnBpp() {
        // The OpenHIM channel: a primary route to this adaptor's /inbound
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/inbound")).atPriority(10)
                .willReturn(aResponse().proxiedFrom("http://localhost:" + port)
                        .withAdditionalRequestHeader("X-OpenHIM-ClientID", "cce-beckn-endpoint")));
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/bpp/caller/on_update"))
                .willReturn(okJson("{\"message\":{\"status\":\"ACK\",\"messageId\":\"" + MESSAGE_ID + "\"}}")));
    }

    @AfterEach
    void reset() {
        wm.resetAll();
    }

    // ==================== Helpers ====================

    private static void stubCce(int status, String body) {
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/bap/caller/update"))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static void stubCceAck() {
        stubCce(200, "{\"message\":{\"status\":\"ACK\",\"messageId\":\"x\"}}");
    }

    private static void stubCceNack(int status, String code, String text) {
        stubCce(status, "{\"message\":{\"status\":\"NACK\",\"messageId\":\"x\",\"error\":{\"code\":\"" + code
                + "\",\"message\":\"" + text + "\"}}}");
    }

    private static void stubCollectorAccepted() {
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/events"))
                .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"eventId\":\"c-1\",\"status\":\"accepted\"}}")));
    }

    private org.springframework.test.web.servlet.ResultActions sendUpdate(String body) throws Exception {
        return mockMvc.perform(post("/beckn/bpp-webhook/update")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static List<LoggedRequest> onUpdatesSent(long waitMs) throws InterruptedException {
        long until = System.currentTimeMillis() + waitMs;
        List<LoggedRequest> found;
        do {
            found = wm.findAll(postRequestedFor(urlEqualTo("/bpp/caller/on_update")));
            if (!found.isEmpty()) {
                return found;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < until);
        return found;
    }

    private double count(String name, String... tags) {
        var search = meterRegistry.find(name).tags(tags);
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    // ==================== Unwrap endpoint ====================

    @Nested
    @DisplayName("POST /beckn/bpp-webhook/update")
    class BppWebhook {

        @Test
        @DisplayName("happy path: channel → /inbound → CCE ACK → Ack, then on_update ACCEPTED")
        void happyPath() throws Exception {
            stubCceAck();

            sendUpdate(update(ENCOUNTER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message.status", is("ACK")))
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)))
                    .andExpect(jsonPath("$.message.error").doesNotExist());

            // The channel got the raw resource with the OpenHIM client and the headers of §3.2
            String basic = "Basic " + Base64.getEncoder()
                    .encodeToString("cce-beckn-endpoint:secret".getBytes(StandardCharsets.UTF_8));
            wm.verify(1, postRequestedFor(urlEqualTo("/inbound"))
                    .withHeader("Authorization", equalTo(basic))
                    .withHeader("Content-Type", containing("application/json"))
                    .withHeader("X-Source-System", equalTo(OPENMRS_SOURCE))
                    .withHeader("X-Source-Event-Id", equalTo(PERFORMANCE_ID))
                    .withHeader("X-Correlation-Id", equalTo(MESSAGE_ID))
                    .withoutHeader("X-Facility-Id")
                    .withRequestBody(equalToJson(ENCOUNTER)));

            // The update to CCE
            List<LoggedRequest> updates = wm.findAll(postRequestedFor(urlEqualTo("/bap/caller/update")));
            assertThat(updates).hasSize(1);
            JsonNode u = mapper.readTree(updates.get(0).getBodyAsString());
            assertThat(u.at("/context/bapId").asText()).isEqualTo("bap.openhim.example.org");
            assertThat(u.at("/context/bppId").asText()).isEqualTo(CCE_BPP_ID);
            assertThat(u.at("/context/transactionId").asText()).isEqualTo(CCE_TXN);
            assertThat(u.at("/context/messageId").asText()).isNotEqualTo(MESSAGE_ID).hasSize(36);
            assertThat(u.at("/message/contract/id").asText()).isEqualTo(CCE_CONTRACT);
            assertThat(u.at("/message/contract/commitments/0/id").asText()).isEqualTo("cce-care-coordination");
            JsonNode perf = u.at("/message/contract/performance");
            assertThat(perf).hasSize(1);
            assertThat(perf.get(0).path("id").asText())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}");
            JsonNode box = perf.get(0).path("performanceAttributes");
            assertThat(box.path("@type").asText()).isEqualTo("cce:CceClinicalEvent");
            assertThat(box.path("eventType").asText()).isEqualTo("Encounter");
            assertThat(box.path("patientId").asText()).isEqualTo("1198503150001234");
            assertThat(box.path("facilityId").asText()).isEqualTo("0002");
            assertThat(box.path("sourceEventId").asText()).isEqualTo(PERFORMANCE_ID);
            assertThat(box.path("resource").path("id").asText()).isEqualTo("7d2c9b1e-4f3a-4e8b-9c6d-5a1b2c3d4e5f");

            // Nothing went to the collector
            wm.verify(0, postRequestedFor(urlEqualTo("/v1/events")));

            // on_update ACCEPTED to the HMIS, after the Ack
            List<LoggedRequest> onUpdates = onUpdatesSent(5000);
            assertThat(onUpdates).hasSize(1);
            JsonNode o = mapper.readTree(onUpdates.get(0).getBodyAsString());
            assertThat(o.at("/context/action").asText()).isEqualTo("on_update");
            assertThat(o.at("/context/bapId").asText()).isEqualTo(OPENMRS_BAP_ID);
            assertThat(o.at("/context/messageId").asText()).isEqualTo(MESSAGE_ID);
            assertThat(o.at("/context/transactionId").asText()).isEqualTo(OPENMRS_TXN);
            assertThat(o.at("/message/contract/id").asText()).isEqualTo(OPENMRS_CONTRACT);
            assertThat(o.at("/message/contract/commitments/0/id").asText()).isEqualTo("hie-event-feed");
            assertThat(o.at("/message/contract/performance/0/id").asText()).isEqualTo(PERFORMANCE_ID);
            assertThat(o.at("/message/contract/performance/0/status/code").asText()).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("the same HMIS event gives the same CloudEvent id (resend → CCE DUPLICATE)")
        void fixedEventId() throws Exception {
            stubCceAck();
            sendUpdate(update(ENCOUNTER)).andExpect(status().isOk());
            sendUpdate(update(ENCOUNTER)).andExpect(status().isOk());
            List<LoggedRequest> updates = wm.findAll(postRequestedFor(urlEqualTo("/bap/caller/update")));
            assertThat(updates).hasSize(2);
            assertThat(mapper.readTree(updates.get(0).getBodyAsString()).at("/message/contract/performance/0/id"))
                    .isEqualTo(mapper.readTree(updates.get(1).getBodyAsString()).at("/message/contract/performance/0/id"));
        }

        @Test
        @DisplayName("ignored by the pipeline (Bundle) → Nack 400 EVENT_REJECTED, no on_update")
        void ignored() throws Exception {
            double before = count("cce.emitter.beckn.channel.ignored", "status", "ignored");
            sendUpdate(update("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.status", is("NACK")))
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)))
                    .andExpect(jsonPath("$.message.error.code", is("EVENT_REJECTED")))
                    .andExpect(jsonPath("$.message.error.message", startsWith("ignored: ")));
            wm.verify(0, postRequestedFor(urlEqualTo("/bap/caller/update")));
            assertThat(onUpdatesSent(300)).isEmpty();
            assertThat(count("cce.emitter.beckn.channel.ignored", "status", "ignored")).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("no patient reference → Nack 400 EVENT_REJECTED with PATIENT_ID_NOT_FOUND")
        void noPatient() throws Exception {
            sendUpdate(update("{\"resourceType\":\"Encounter\",\"id\":\"e1\",\"status\":\"finished\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("EVENT_REJECTED")))
                    .andExpect(jsonPath("$.message.error.message", startsWith("PATIENT_ID_NOT_FOUND")));
        }

        @Test
        @DisplayName("channel answers 5xx → Nack 500 UNAVAILABLE")
        void channel5xx() throws Exception {
            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/inbound")).atPriority(1)
                    .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));
            sendUpdate(update(ENCOUNTER))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message.status", is("NACK")))
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)))
                    .andExpect(jsonPath("$.message.error.code", is("UNAVAILABLE")));
            assertThat(onUpdatesSent(300)).isEmpty();
        }

        @Test
        @DisplayName("OpenHIM refuses the client (401) → Nack 500 HIE_ERROR")
        void channel401() throws Exception {
            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/inbound")).atPriority(1)
                    .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));
            sendUpdate(update(ENCOUNTER))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message.error.code", is("HIE_ERROR")))
                    .andExpect(jsonPath("$.message.error.message", containsString("401")));
        }

        @Test
        @DisplayName("CCE NACK EVENT_REJECTED → Nack 400 EVENT_REJECTED with CCE's text")
        void cceEventRejected() throws Exception {
            stubCceNack(400, "EVENT_REJECTED", "Payload validation failed");
            sendUpdate(update(ENCOUNTER))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("EVENT_REJECTED")))
                    .andExpect(jsonPath("$.message.error.message", is("CCE: Payload validation failed")));
            assertThat(onUpdatesSent(300)).isEmpty();
        }

        @Test
        @DisplayName("CCE NACK BAD_REQUEST (our own update broke a rule) → Nack 500 HIE_ERROR")
        void cceBadRequest() throws Exception {
            stubCceNack(400, "BAD_REQUEST", "performance[0].id is longer than 50 characters");
            sendUpdate(update(ENCOUNTER))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message.error.code", is("HIE_ERROR")))
                    .andExpect(jsonPath("$.message.error.message",
                            is("CCE BAD_REQUEST: performance[0].id is longer than 50 characters")));
        }

        @Test
        @DisplayName("CCE 500 → Nack 500 UNAVAILABLE, one try only")
        void cce500() throws Exception {
            stubCceNack(500, "COLLECTOR_UNAVAILABLE", "Send it again later.");
            sendUpdate(update(ENCOUNTER))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message.error.code", is("UNAVAILABLE")));
            wm.verify(1, postRequestedFor(urlEqualTo("/bap/caller/update")));
        }

        @Test
        @DisplayName("unknown subscriber → Nack 400 BAD_REQUEST, nothing sent to the channel")
        void unknownSubscriber() throws Exception {
            sendUpdate(update("bap.other.example.org", OPENMRS_CONTRACT, entry(PERFORMANCE_ID, ENCOUNTER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)))
                    .andExpect(jsonPath("$.message.error.code", is("BAD_REQUEST")));
            wm.verify(0, postRequestedFor(urlEqualTo("/inbound")));
        }

        @Test
        @DisplayName("other actions → Nack 400 NOT_SUPPORTED")
        void otherAction() throws Exception {
            mockMvc.perform(post("/beckn/bpp-webhook/confirm").contentType(MediaType.APPLICATION_JSON)
                            .content(update(ENCOUNTER).replace("\"action\": \"update\"", "\"action\": \"confirm\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("NOT_SUPPORTED")));
        }

        @Test
        @DisplayName("a body that is not JSON → Nack 400 BAD_REQUEST")
        void notJson() throws Exception {
            mockMvc.perform(post("/beckn/bpp-webhook/update").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.status", is("NACK")))
                    .andExpect(jsonPath("$.message.error.code", is("BAD_REQUEST")));
        }
    }

    // ==================== Beckn output on /inbound ====================

    @Nested
    @DisplayName("POST /inbound for a source with output: beckn")
    class BecknOutput {

        private org.springframework.test.web.servlet.ResultActions sendInbound() throws Exception {
            return mockMvc.perform(post("/inbound").contentType(MediaType.APPLICATION_JSON)
                    .header("X-OpenHIM-ClientID", "cce-beckn-endpoint")
                    .header("X-Source-System", OPENMRS_SOURCE)
                    .header("X-Source-Event-Id", PERFORMANCE_ID)
                    .content(ENCOUNTER));
        }

        @Test
        @DisplayName("CCE ACK → 202, like a collector accept")
        void ack() throws Exception {
            stubCceAck();
            sendInbound()
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status", is("Successful")))
                    .andExpect(jsonPath("$.response.status", is(202)))
                    .andExpect(jsonPath("$.orchestrations[0].name", is("Forward to CCE over Beckn")))
                    .andExpect(jsonPath("$.orchestrations[0].request.path", is("/bap/caller/update")));
            wm.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
        }

        @Test
        @DisplayName("CCE NACK EVENT_REJECTED → 400 COLLECTOR_CLIENT_ERROR 'CCE EVENT_REJECTED: …'")
        void nackEventRejected() throws Exception {
            stubCceNack(400, "EVENT_REJECTED", "Payload validation failed");
            sendInbound()
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_CLIENT_ERROR")))
                    .andExpect(jsonPath("$.error.message", is("CCE EVENT_REJECTED: Payload validation failed")));
            wm.verify(1, postRequestedFor(urlEqualTo("/bap/caller/update")));
        }

        @Test
        @DisplayName("CCE NACK BAD_REQUEST → 400 COLLECTOR_CLIENT_ERROR 'CCE BAD_REQUEST: …'")
        void nackBadRequest() throws Exception {
            stubCceNack(400, "BAD_REQUEST", "context.bapId is missing");
            sendInbound()
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_CLIENT_ERROR")))
                    .andExpect(jsonPath("$.error.message", is("CCE BAD_REQUEST: context.bapId is missing")));
        }

        @Test
        @DisplayName("CCE 500 → 502 COLLECTOR_FORWARDING_ERROR, no retry")
        void serverError() throws Exception {
            stubCceNack(500, "INTERNAL_ERROR", "later");
            sendInbound()
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_FORWARDING_ERROR")));
            wm.verify(1, postRequestedFor(urlEqualTo("/bap/caller/update")));
        }

        @Test
        @DisplayName("onix-bap timeout → 502 COLLECTOR_FORWARDING_ERROR, no retry")
        void timeout() throws Exception {
            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/bap/caller/update"))
                    .willReturn(okJson("{\"message\":{\"status\":\"ACK\",\"messageId\":\"x\"}}")
                            .withFixedDelay(ONIX_TIMEOUT_MS + 1000)));
            sendInbound()
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_FORWARDING_ERROR")));
            wm.verify(1, postRequestedFor(urlEqualTo("/bap/caller/update")));
        }
    }

    // ==================== Collector regression ====================

    @Nested
    @DisplayName("POST /inbound for a source with output: collector (unchanged)")
    class CollectorRegression {

        @Test
        @DisplayName("goes to the collector exactly as before, never to onix")
        void collectorUnchanged() throws Exception {
            stubCollectorAccepted();
            mockMvc.perform(post("/inbound").contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client")
                            .header("X-Facility-Id", "0002")
                            .header("X-Source-Event-Id", "enc-visit-001")
                            .content(ENCOUNTER))
                    .andExpect(status().isAccepted())
                    .andExpect(content().contentTypeCompatibleWith("application/json+openhim"))
                    .andExpect(jsonPath("$.response.status", is(202)))
                    .andExpect(jsonPath("$.orchestrations[0].name", is("Forward to CCE Collector")))
                    .andExpect(jsonPath("$.orchestrations[0].request.path", is("/v1/events")))
                    .andExpect(jsonPath("$.orchestrations[0].response.status", is(202)));
            wm.verify(1, postRequestedFor(urlEqualTo("/v1/events"))
                    .withHeader("Authorization", equalTo("Bearer test-token"))
                    .withRequestBody(matchingJsonPath("$.source", equalTo("ebuzima")))
                    .withRequestBody(matchingJsonPath("$.facilityid", equalTo("0002"))));
            wm.verify(0, postRequestedFor(urlEqualTo("/bap/caller/update")));
        }

        @Test
        @DisplayName("collector 503 is still retried 3 times")
        void collectorStillRetries() throws Exception {
            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/events"))
                    .willReturn(aResponse().withStatus(503)));
            mockMvc.perform(post("/inbound").contentType(MediaType.APPLICATION_JSON)
                            .header("X-OpenHIM-ClientID", "ebuzima-emr-client").content(ENCOUNTER))
                    .andExpect(status().isBadGateway());
            wm.verify(3, postRequestedFor(urlEqualTo("/v1/events")));
        }
    }

    // ==================== Callback endpoint ====================

    @Nested
    @DisplayName("POST /beckn/bap-webhook/{action}")
    class BapWebhook {

        private String onUpdate(String bppId, String contractId, String status) {
            return """
                    { "context": { "version": "2.0.0", "action": "on_update", "bapId": "bap.openhim.example.org",
                                   "bppId": "%s", "transactionId": "%s", "messageId": "%s",
                                   "timestamp": "2026-11-13T08:00:37Z" },
                      "message": { "contract": { "id": "%s", "status": { "code": "ACTIVE" },
                        "commitments": [ { "id": "cce-care-coordination" } ],
                        "performance": [ { "id": "b2d6e8f0-1a3c-5e7f-9b1d-3f5a7c9e1b3d", "status": { "code": "%s" } } ] } } }
                    """.formatted(bppId, CCE_TXN, MESSAGE_ID, contractId, status);
        }

        @Test
        @DisplayName("CCE's on_update ACCEPTED → Ack")
        void accepted() throws Exception {
            mockMvc.perform(post("/beckn/bap-webhook/on_update").contentType(MediaType.APPLICATION_JSON)
                            .content(onUpdate(CCE_BPP_ID, CCE_CONTRACT, "ACCEPTED")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message.status", is("ACK")))
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)));
        }

        @Test
        @DisplayName("CCE's on_update DUPLICATE → Ack, counts cce.emitter.events.duplicate")
        void duplicate() throws Exception {
            double before = count("cce.emitter.events.duplicate");
            mockMvc.perform(post("/beckn/bap-webhook/on_update").contentType(MediaType.APPLICATION_JSON)
                            .content(onUpdate(CCE_BPP_ID, CCE_CONTRACT, "DUPLICATE")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message.status", is("ACK")));
            assertThat(count("cce.emitter.events.duplicate")).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("on_update from another bppId → Nack 400 BAD_REQUEST")
        void otherBpp() throws Exception {
            mockMvc.perform(post("/beckn/bap-webhook/on_update").contentType(MediaType.APPLICATION_JSON)
                            .content(onUpdate("bpp.other.org", CCE_CONTRACT, "ACCEPTED")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("BAD_REQUEST")));
        }

        @Test
        @DisplayName("on_update on another contract → Nack 400 BAD_REQUEST")
        void otherContract() throws Exception {
            mockMvc.perform(post("/beckn/bap-webhook/on_update").contentType(MediaType.APPLICATION_JSON)
                            .content(onUpdate(CCE_BPP_ID, "other", "ACCEPTED")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("BAD_REQUEST")));
        }

        @Test
        @DisplayName("on_status (CCE alerts) → Nack 400 NOT_SUPPORTED")
        void onStatus() throws Exception {
            mockMvc.perform(post("/beckn/bap-webhook/on_status").contentType(MediaType.APPLICATION_JSON)
                            .content(onUpdate(CCE_BPP_ID, CCE_CONTRACT, "ACCEPTED")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("NOT_SUPPORTED")))
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)));
        }
    }
}
