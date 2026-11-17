package org.openphc.cce.emitter.beckn;

import java.util.List;

import org.openphc.cce.emitter.config.BecknProperties;

/** Shared settings and messages for the Beckn tests. */
public final class BecknTestSupport {

    public static final String OPENMRS_BAP_ID = "bap.openmrs.example.org";
    public static final String OPENMRS_CONTRACT = "5b1e7f3c-2a4d-4c6e-8f90-a1b2c3d4e5f6";
    public static final String OPENMRS_TXN = "0d5f8c1a-7b2e-4f6d-9a3c-1e2b3c4d5e6f";
    public static final String OPENMRS_SOURCE = "openmrs-kicukiro";
    public static final String CCE_BPP_ID = "bpp.mdtlabs.org";
    public static final String CCE_CONTRACT = "9d4e2f6a-1b3c-4d5e-8f70-b1c2d3e4f5a6";
    public static final String CCE_TXN = "4a7b9c2d-3e5f-4a1b-8c6d-7e8f9a0b1c2d";
    public static final String MESSAGE_ID = "9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d";
    public static final String PERFORMANCE_ID = "3f6c1c1e-8a53-5b0e-9d7a-2b6f4e1a9c10";

    private BecknTestSupport() {
    }

    public static BecknProperties.Subscriber openmrsSubscriber() {
        return new BecknProperties.Subscriber(OPENMRS_BAP_ID, "https://openmrs.example.org/bap/receiver",
                OPENMRS_CONTRACT, OPENMRS_TXN, "classpath:beckn/commitment-hie-feed.json", OPENMRS_SOURCE);
    }

    public static BecknProperties properties(boolean enabled, String channelUser, List<BecknProperties.Subscriber> subs) {
        return properties(enabled, channelUser, "bap.openhim.example.org", "classpath:beckn/commitment-cce.json", subs);
    }

    public static BecknProperties properties(boolean enabled, String channelUser, String bapId, String cceCommitment,
                                             List<BecknProperties.Subscriber> subs) {
        return new BecknProperties(enabled,
                "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema",
                new BecknProperties.Onix("http://onix-bap:8081/bap/caller", "http://onix-bpp:8082/bpp/caller", 25000),
                new BecknProperties.Self(null, bapId, "https://hie.example.org/bap/receiver",
                        "bpp.openhim.example.org", "https://hie.example.org/bpp/receiver"),
                new BecknProperties.Cce(CCE_BPP_ID, "https://cce.example.org/bpp/receiver", CCE_CONTRACT, CCE_TXN,
                        cceCommitment, new BecknProperties.Retry(1)),
                new BecknProperties.OpenHimChannel("https://openhim-core:5001/cce-beckn", channelUser, "secret", 35000),
                subs);
    }

    /** An HMIS {@code update} as in spec §3.6. */
    public static String update(String bapId, String contractId, String performanceEntries) {
        return """
                {
                  "context": {
                    "version": "2.0.0", "action": "update",
                    "bapId": "%s", "bapUri": "https://openmrs.example.org/bap/receiver",
                    "bppId": "bpp.openhim.example.org", "bppUri": "https://hie.example.org/bpp/receiver",
                    "transactionId": "%s", "messageId": "%s", "timestamp": "2026-11-13T08:00:35Z"
                  },
                  "message": { "contract": {
                    "id": "%s",
                    "commitments": [ { "id": "hie-event-feed" } ],
                    "performance": [ %s ]
                  } }
                }
                """.formatted(bapId, OPENMRS_TXN, MESSAGE_ID, contractId, performanceEntries);
    }

    public static String update(String resourceJson) {
        return update(OPENMRS_BAP_ID, OPENMRS_CONTRACT, entry(PERFORMANCE_ID, resourceJson));
    }

    public static String entry(String id, String resourceJson) {
        return """
                {
                  "id": "%s",
                  "performanceAttributes": {
                    "@context": "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v1.0/context.jsonld",
                    "@type": "cce:CceClinicalEvent",
                    "eventType": "Encounter",
                    "patientId": "1198503150001234",
                    "fhirVersion": "4.0.1",
                    "sourceEventId": "Encounter/7d2c9b1e",
                    "resource": %s
                  }
                }
                """.formatted(id, resourceJson);
    }

    public static final String ENCOUNTER = """
            {
              "resourceType": "Encounter",
              "id": "7d2c9b1e-4f3a-4e8b-9c6d-5a1b2c3d4e5f",
              "meta": { "lastUpdated": "2026-11-13T08:00:12Z" },
              "status": "finished",
              "subject": { "reference": "Patient/1198503150001234" },
              "period": { "start": "2026-11-13T07:30:00Z", "end": "2026-11-13T08:00:00Z" },
              "location": [ { "location": { "reference": "Location/0002" } } ]
            }
            """;
}
