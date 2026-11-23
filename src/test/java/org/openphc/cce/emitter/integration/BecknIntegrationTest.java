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
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Sygnal role end to end, with {@code beckn.enabled=true}: both hops are
 * {@code sygnal/event/report}, with no callback on either. One WireMock server plays:
 * <ul>
 *   <li>the OpenHIM channel ({@code /inbound}): it proxies to this adaptor's own {@code /inbound}
 *       and adds {@code X-OpenHIM-ClientID}, as OpenHIM Core does; single tests replace it with a
 *       fixed answer (5xx, 401)</li>
 *   <li>{@code onix-openhim}'s Sygnal caller ({@code /sygnal/caller/sygnal/event/report}) answering
 *       for CCE</li>
 *   <li>the CCE Collector ({@code /v1/events}) for the sources that stay on {@code collector}</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class BecknIntegrationTest {

    private static final int ONIX_TIMEOUT_MS = 1500;
    private static final String ENDPOINT = "/sygnal/event/report";
    private static final String CCE_REPORT = "/sygnal/caller/sygnal/event/report";

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
        // With Sygnal on, a source without an output would follow it; ebuzima stays on the collector explicitly.
        r.add("cce.emitter.sources.ebuzima.output", () -> "collector");
        r.add("beckn.enabled", () -> "true");
        r.add("beckn.onix.caller-url", () -> base + "/sygnal/caller");
        r.add("beckn.onix.timeout", () -> String.valueOf(ONIX_TIMEOUT_MS));
        r.add("beckn.self.id", () -> SELF_ID);
        r.add("beckn.self.network-id", () -> NETWORK_ID);
        r.add("beckn.cce.id", () -> CCE_ID);
        r.add("beckn.cce.uri", () -> CCE_URI);
        r.add("beckn.openhim-channel.url", () -> base + "/inbound");
        r.add("beckn.openhim-channel.username", () -> "cce-beckn-endpoint");
        r.add("beckn.openhim-channel.password", () -> "secret");
        r.add("beckn.openhim-channel.timeout", () -> "8000");
        r.add("beckn.subscribers[0].subscriber-id", () -> OPENMRS_ID);
        r.add("beckn.subscribers[0].source-system", () -> OPENMRS_SOURCE);
    }

    @BeforeEach
    void stubChannel() {
        // The OpenHIM channel: a primary route to this adaptor's /inbound
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/inbound")).atPriority(10)
                .willReturn(aResponse().proxiedFrom("http://localhost:" + port)
                        .withAdditionalRequestHeader("X-OpenHIM-ClientID", "cce-beckn-endpoint")));
    }

    @AfterEach
    void reset() {
        wm.resetAll();
    }

    // ==================== Helpers ====================

    private static void stubCce(int status, String body) {
        wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(CCE_REPORT))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static void stubCceAck() {
        stubCce(202, "{\"message\":{\"status\":\"ACK\",\"messageId\":\"x\"}}");
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

    private ResultActions sendReport(String body) throws Exception {
        return mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<JsonNode> reportsToCce() throws Exception {
        List<LoggedRequest> found = wm.findAll(postRequestedFor(urlEqualTo(CCE_REPORT)));
        List<JsonNode> out = new java.util.ArrayList<>();
        for (LoggedRequest r : found) {
            out.add(mapper.readTree(r.getBodyAsString()));
        }
        return out;
    }

    /** Nothing but the report went to onix: no callbacks, and the action's slashes were never encoded. */
    private static void onlyReportsWentToOnix(int reports) {
        wm.verify(reports, postRequestedFor(urlMatching("/sygnal/caller/.*")));
        wm.verify(0, anyRequestedFor(urlMatching(".*%2F.*")));
        wm.verify(0, anyRequestedFor(urlMatching(".*(on_update|on_status|bpp|bap).*")));
    }

    private double count(String name, String... tags) {
        var search = meterRegistry.find(name).tags(tags);
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    // ==================== HMIS -> HIE: the sygnal/event/report endpoint ====================

    @Nested
    @DisplayName("POST /sygnal/event/report")
    class ReportEndpoint {

        @Test
        @DisplayName("happy path: channel → /inbound → CCE ACK → 202 Ack, the same event id on both hops, no callback")
        void happyPath() throws Exception {
            stubCceAck();

            sendReport(report(ENCOUNTER))
                    .andExpect(status().isAccepted())
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
                    .withHeader("X-Source-Event-Id", equalTo(SOURCE_EVENT_ID))
                    .withHeader("X-Sygnal-Event-Id", equalTo(EVENT_ID))
                    .withHeader("X-Sygnal-Occurred-At", equalTo(OCCURRED_AT))
                    .withHeader("X-Correlation-Id", equalTo(MESSAGE_ID))
                    .withoutHeader("X-Facility-Id")
                    .withRequestBody(equalToJson(ENCOUNTER)));

            // The report to CCE, posted to <caller url>/sygnal/event/report
            List<JsonNode> reports = reportsToCce();
            assertThat(reports).hasSize(1);
            JsonNode r = reports.get(0);
            JsonNode c = r.path("context");
            assertThat(c.path("version").asText()).isEqualTo("2.0.0");
            assertThat(c.path("action").asText()).isEqualTo("sygnal/event/report");
            assertThat(c.path("networkId").asText()).isEqualTo(NETWORK_ID);
            assertThat(c.path("senderId").asText()).isEqualTo(SELF_ID);
            assertThat(c.path("receiverId").asText()).isEqualTo(CCE_ID);
            assertThat(c.path("receiverUri").asText()).isEqualTo(CCE_URI);
            assertThat(c.path("transactionId").asText()).isNotEqualTo(OPENMRS_TXN).hasSize(36);
            assertThat(c.path("messageId").asText()).isNotEqualTo(MESSAGE_ID).hasSize(36);
            assertThat(c.has("bapId") || c.has("bppId") || c.has("bapUri") || c.has("bppUri")).isFalse();
            assertThat(r.path("message").has("contract")).isFalse();

            // message.events = [ the flat cce:CceClinicalEvent v2.0 ]: these fields and no other
            assertThat(r.path("message").fieldNames()).toIterable().containsExactly("events");
            JsonNode events = r.at("/message/events");
            assertThat(events).hasSize(1);
            JsonNode e = events.get(0);
            assertThat(e).isEqualTo(mapper.readTree("""
                    {
                      "@context": "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v2.0/context.jsonld",
                      "@type": "cce:CceClinicalEvent",
                      "id": "%s",
                      "occurredAt": "%s",
                      "patientId": "1198503150001234",
                      "fhirVersion": "4.0.1",
                      "facilityId": "0002",
                      "sourceEventId": "%s",
                      "resource": %s
                    }
                    """.formatted(EVENT_ID, OCCURRED_AT, SOURCE_EVENT_ID, ENCOUNTER)));
            assertThat(e.fieldNames()).toIterable().containsExactly("@context", "@type", "id", "occurredAt",
                    "patientId", "fhirVersion", "facilityId", "sourceEventId", "resource");
            // Nothing of the sygnal:CoordinationEvent of profile v1.0
            assertThat(e.has("eventAttributes")).isFalse();
            assertThat(r.toString()).doesNotContain("sygnal:CoordinationEvent", "eventAttributes", "eventType",
                    "revision", "granularity", "\"from\"", "DELIVERY_RECORDED");

            // Nothing went to the collector, and nothing but the report went to onix
            wm.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
            onlyReportsWentToOnix(1);
        }

        @Test
        @DisplayName("a resend keeps the event id (CCE answers Ack for a duplicate) → 202 Ack again")
        void resendKeepsTheEventId() throws Exception {
            stubCceAck();
            sendReport(report(ENCOUNTER)).andExpect(status().isAccepted());
            sendReport(report(ENCOUNTER)).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.message.status", is("ACK")));
            List<JsonNode> reports = reportsToCce();
            assertThat(reports).hasSize(2);
            assertThat(reports.get(0).at("/message/events/0/id").asText()).isEqualTo(EVENT_ID);
            assertThat(reports.get(1).at("/message/events/0/id").asText()).isEqualTo(EVENT_ID);
            assertThat(reports.get(0).at("/context/messageId"))
                    .isNotEqualTo(reports.get(1).at("/context/messageId"));
        }

        @Test
        @DisplayName("ignored by the pipeline (Bundle) → Nack 400 BIZ_GENERIC_ERROR, nothing sent to CCE")
        void ignored() throws Exception {
            double before = count("cce.emitter.beckn.channel.ignored", "status", "ignored");
            sendReport(report("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.status", is("NACK")))
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)))
                    .andExpect(jsonPath("$.message.error.code", is("BIZ_GENERIC_ERROR")))
                    .andExpect(jsonPath("$.message.error.message", startsWith("ignored: ")));
            onlyReportsWentToOnix(0);
            assertThat(count("cce.emitter.beckn.channel.ignored", "status", "ignored")).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("no patient reference → Nack 400 BIZ_GENERIC_ERROR with PATIENT_ID_NOT_FOUND")
        void noPatient() throws Exception {
            sendReport(report("{\"resourceType\":\"Encounter\",\"id\":\"e1\",\"status\":\"finished\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("BIZ_GENERIC_ERROR")))
                    .andExpect(jsonPath("$.message.error.message", startsWith("PATIENT_ID_NOT_FOUND")));
        }

        @Test
        @DisplayName("channel answers 5xx → Nack 500 NET_DOWNSTREAM_UNAVAILABLE (the HMIS sends it again)")
        void channel5xx() throws Exception {
            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/inbound")).atPriority(1)
                    .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));
            sendReport(report(ENCOUNTER))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message.status", is("NACK")))
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)))
                    .andExpect(jsonPath("$.message.error.code", is("NET_DOWNSTREAM_UNAVAILABLE")));
        }

        @Test
        @DisplayName("OpenHIM refuses the client (401) → Nack 500 NET_INTERNAL_ERROR")
        void channel401() throws Exception {
            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/inbound")).atPriority(1)
                    .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));
            sendReport(report(ENCOUNTER))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message.error.code", is("NET_INTERNAL_ERROR")))
                    .andExpect(jsonPath("$.message.error.message", containsString("401")));
        }

        @Test
        @DisplayName("CCE NACK BIZ_GENERIC_ERROR → Nack 400 BIZ_GENERIC_ERROR with CCE's text")
        void cceRefusedEvent() throws Exception {
            stubCceNack(400, "BIZ_GENERIC_ERROR", "Payload validation failed");
            sendReport(report(ENCOUNTER))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("BIZ_GENERIC_ERROR")))
                    .andExpect(jsonPath("$.message.error.message", is("CCE: Payload validation failed")));
        }

        @Test
        @DisplayName("CCE NACK SCH_* → Nack 400 BIZ_GENERIC_ERROR naming CCE's code")
        void cceSchemaRefusal() throws Exception {
            stubCceNack(400, "SCH_REQUIRED_FIELD_MISSING", "events[0].patientId is missing");
            sendReport(report(ENCOUNTER))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("BIZ_GENERIC_ERROR")))
                    .andExpect(jsonPath("$.message.error.message",
                            is("CCE SCH_REQUIRED_FIELD_MISSING: events[0].patientId is missing")));
        }

        @Test
        @DisplayName("CCE NACK CTX_* (the HIE's own settings) → Nack 500 NET_INTERNAL_ERROR")
        void cceRefusedHieContext() throws Exception {
            stubCceNack(400, "CTX_INVALID_FIELD", "context.receiverId is 'x', but CCE is 'y'");
            sendReport(report(ENCOUNTER))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message.error.code", is("NET_INTERNAL_ERROR")))
                    .andExpect(jsonPath("$.message.error.message", startsWith("CCE CTX_INVALID_FIELD: ")));
        }

        @Test
        @DisplayName("CCE 500 → Nack 500 NET_DOWNSTREAM_UNAVAILABLE, one try only")
        void cce500() throws Exception {
            stubCceNack(500, "NET_DOWNSTREAM_UNAVAILABLE", "Send it again later.");
            sendReport(report(ENCOUNTER))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message.error.code", is("NET_DOWNSTREAM_UNAVAILABLE")));
            wm.verify(1, postRequestedFor(urlEqualTo(CCE_REPORT)));
        }

        @Test
        @DisplayName("onix gives no answer in time → Nack 500 NET_DOWNSTREAM_UNAVAILABLE, before the channel gives up")
        void cceTimeout() throws Exception {
            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(CCE_REPORT))
                    .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                            .withBody("{\"message\":{\"status\":\"ACK\",\"messageId\":\"x\"}}")
                            .withFixedDelay(ONIX_TIMEOUT_MS + 1000)));
            long started = System.currentTimeMillis();
            sendReport(report(ENCOUNTER))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message.error.code", is("NET_DOWNSTREAM_UNAVAILABLE")));
            assertThat(System.currentTimeMillis() - started).isLessThan(8000);
        }

        @Test
        @DisplayName("unknown sender → Nack 400 CTX_INVALID_FIELD, nothing sent to the channel")
        void unknownSender() throws Exception {
            sendReport(report("bap.other.example.org", SELF_ID, event(EVENT_ID, ENCOUNTER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)))
                    .andExpect(jsonPath("$.message.error.code", is("CTX_INVALID_FIELD")));
            wm.verify(0, postRequestedFor(urlEqualTo("/inbound")));
        }

        @Test
        @DisplayName("a report for another receiver → Nack 400 CTX_INVALID_FIELD")
        void otherReceiver() throws Exception {
            sendReport(report(OPENMRS_ID, "bap.other-hie.example.org", event(EVENT_ID, ENCOUNTER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("CTX_INVALID_FIELD")));
            wm.verify(0, postRequestedFor(urlEqualTo("/inbound")));
        }

        @Test
        @DisplayName("an event id that is not a UUID → Nack 400 SCH_SCHEMA_VALIDATION_FAILED")
        void eventIdNotAUuid() throws Exception {
            sendReport(report(OPENMRS_ID, SELF_ID, event("enc-1", ENCOUNTER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("SCH_SCHEMA_VALIDATION_FAILED")));
            wm.verify(0, postRequestedFor(urlEqualTo("/inbound")));
        }

        @Test
        @DisplayName("the old sygnal:CoordinationEvent with an eventAttributes bag → Nack 400 SCH_TYPE_NOT_SUPPORTED")
        void oldCoordinationEvent() throws Exception {
            sendReport(report(OPENMRS_ID, SELF_ID, oldEvent(EVENT_ID, ENCOUNTER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.status", is("NACK")))
                    .andExpect(jsonPath("$.message.messageId", is(MESSAGE_ID)))
                    .andExpect(jsonPath("$.message.error.code", is("SCH_TYPE_NOT_SUPPORTED")))
                    .andExpect(jsonPath("$.message.error.message", is("events[0].@type must be cce:CceClinicalEvent")));
            wm.verify(0, postRequestedFor(urlEqualTo("/inbound")));
            onlyReportsWentToOnix(0);
        }

        @Test
        @DisplayName("an event without occurredAt → Nack 400 SCH_REQUIRED_FIELD_MISSING")
        void missingOccurredAt() throws Exception {
            String e = event(EVENT_ID, ENCOUNTER).replace("\"occurredAt\": \"" + OCCURRED_AT + "\",", "");
            sendReport(report(OPENMRS_ID, SELF_ID, e))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("SCH_REQUIRED_FIELD_MISSING")));
            wm.verify(0, postRequestedFor(urlEqualTo("/inbound")));
        }

        @Test
        @DisplayName("another action in the context → Nack 400 CTX_ACTION_MISMATCH")
        void otherAction() throws Exception {
            sendReport(report(ENCOUNTER).replace("\"action\": \"sygnal/event/report\"", "\"action\": \"update\""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.error.code", is("CTX_ACTION_MISMATCH")));
            wm.verify(0, postRequestedFor(urlEqualTo("/inbound")));
        }

        @Test
        @DisplayName("a body that is not JSON → Nack 400 SCH_INVALID_JSON")
        void notJson() throws Exception {
            mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("{nope"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.status", is("NACK")))
                    .andExpect(jsonPath("$.message.error.code", is("SCH_INVALID_JSON")));
        }

        @Test
        @DisplayName("the old Beckn webhooks are gone")
        void oldWebhooksAreGone() throws Exception {
            for (String path : List.of("/beckn/bpp-webhook/update", "/beckn/bap-webhook/on_update")) {
                mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(report(ENCOUNTER)))
                        .andExpect(result -> assertThat(result.getResponse().getStatus() / 100).isNotEqualTo(2));
            }
            wm.verify(0, postRequestedFor(urlEqualTo("/inbound")));
        }
    }

    // ==================== HIE -> CCE: the Sygnal output on /inbound ====================

    @Nested
    @DisplayName("POST /inbound for a source with output: beckn")
    class SygnalOutput {

        private ResultActions sendInbound() throws Exception {
            return mockMvc.perform(post("/inbound").contentType(MediaType.APPLICATION_JSON)
                    .header("X-OpenHIM-ClientID", "cce-beckn-endpoint")
                    .header("X-Source-System", OPENMRS_SOURCE)
                    .header("X-Source-Event-Id", SOURCE_EVENT_ID)
                    .content(ENCOUNTER));
        }

        @Test
        @DisplayName("CCE ACK → 202, like a collector accept; orchestration names the Sygnal path")
        void ack() throws Exception {
            stubCceAck();
            sendInbound()
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status", is("Successful")))
                    .andExpect(jsonPath("$.response.status", is(202)))
                    .andExpect(jsonPath("$.orchestrations[0].name", is("Forward to CCE over Sygnal")))
                    .andExpect(jsonPath("$.orchestrations[0].request.path", is(CCE_REPORT)))
                    .andExpect(jsonPath("$.orchestrations[0].response.status", is(202)));
            wm.verify(0, postRequestedFor(urlEqualTo("/v1/events")));
            // Without X-Sygnal-Event-Id the event id is derived from the source event id, as before
            JsonNode e = reportsToCce().get(0).at("/message/events/0");
            assertThat(e.path("id").asText())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}");
            // The same flat cce:CceClinicalEvent; occurredAt is the time of receipt
            assertThat(e.fieldNames()).toIterable().containsExactly("@context", "@type", "id", "occurredAt",
                    "patientId", "fhirVersion", "facilityId", "sourceEventId", "resource");
            assertThat(e.path("@type").asText()).isEqualTo("cce:CceClinicalEvent");
            assertThat(e.path("@context").asText()).isEqualTo(EVENT_CONTEXT);
            assertThat(e.path("occurredAt").asText()).isNotBlank();
            onlyReportsWentToOnix(1);
        }

        @Test
        @DisplayName("CCE NACK 400 → 400 COLLECTOR_CLIENT_ERROR 'CCE <code>: …'")
        void nack() throws Exception {
            stubCceNack(400, "BIZ_GENERIC_ERROR", "Payload validation failed");
            sendInbound()
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_CLIENT_ERROR")))
                    .andExpect(jsonPath("$.error.message", is("CCE BIZ_GENERIC_ERROR: Payload validation failed")));
            wm.verify(1, postRequestedFor(urlEqualTo(CCE_REPORT)));
        }

        @Test
        @DisplayName("CCE 500 → 502 COLLECTOR_FORWARDING_ERROR, no retry")
        void serverError() throws Exception {
            stubCceNack(500, "NET_INTERNAL_ERROR", "later");
            sendInbound()
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_FORWARDING_ERROR")));
            wm.verify(1, postRequestedFor(urlEqualTo(CCE_REPORT)));
        }

        @Test
        @DisplayName("onix timeout → 502 COLLECTOR_FORWARDING_ERROR, no retry")
        void timeout() throws Exception {
            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(CCE_REPORT))
                    .willReturn(okJson("{\"message\":{\"status\":\"ACK\",\"messageId\":\"x\"}}")
                            .withFixedDelay(ONIX_TIMEOUT_MS + 1000)));
            sendInbound()
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error.code", is("COLLECTOR_FORWARDING_ERROR")));
            wm.verify(1, postRequestedFor(urlEqualTo(CCE_REPORT)));
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
            onlyReportsWentToOnix(0);
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
}
