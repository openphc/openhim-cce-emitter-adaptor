package org.openphc.cce.emitter.beckn;

import java.util.List;

import org.openphc.cce.emitter.config.BecknProperties;

/** Shared settings and messages for the Sygnal tests. */
public final class BecknTestSupport {

    public static final String OPENMRS_ID = "bap.openmrs.example.org";
    public static final String OPENMRS_TXN = "0d5f8c1a-7b2e-4f6d-9a3c-1e2b3c4d5e6f";
    public static final String OPENMRS_SOURCE = "openmrs-kicukiro";
    public static final String SELF_ID = "bap.openhim.example.org";
    public static final String NETWORK_ID = "test.example.org/cce-net";
    public static final String CCE_ID = "bpp.cce.example.org";
    public static final String CCE_URI = "https://cce.example.org/sygnal/receiver";
    public static final String MESSAGE_ID = "9c4e2a10-5d6f-4b7a-8c9d-0e1f2a3b4c5d";
    public static final String EVENT_ID = "3f6c1c1e-8a53-4b0e-9d7a-2b6f4e1a9c10";
    public static final String OCCURRED_AT = "2026-11-13T07:55:00Z";
    public static final String SOURCE_EVENT_ID = "Encounter/7d2c9b1e";

    private BecknTestSupport() {
    }

    public static BecknProperties.Subscriber openmrsSubscriber() {
        return new BecknProperties.Subscriber(OPENMRS_ID, OPENMRS_SOURCE);
    }

    public static BecknProperties properties(boolean enabled, String channelUser, List<BecknProperties.Subscriber> subs) {
        return properties(enabled, channelUser, SELF_ID, subs);
    }

    public static BecknProperties properties(boolean enabled, String channelUser, String selfId,
                                             List<BecknProperties.Subscriber> subs) {
        return new BecknProperties(enabled,
                "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema",
                new BecknProperties.Onix("http://onix-openhim:8082/sygnal/caller", 20000),
                new BecknProperties.Self(selfId, NETWORK_ID),
                new BecknProperties.Cce(CCE_ID, CCE_URI, new BecknProperties.Retry(1)),
                new BecknProperties.OpenHimChannel("https://openhim-core:5001/cce-beckn", channelUser, "secret", 25000),
                subs);
    }

    /** An HMIS {@code sygnal/event/report} as in spec §3.6, with the given sender, receiver and events. */
    public static String report(String senderId, String receiverId, String events) {
        return """
                {
                  "context": {
                    "version": "2.0.0", "action": "sygnal/event/report",
                    "networkId": "%s",
                    "senderId": "%s", "receiverId": "%s",
                    "receiverUri": "https://hie.example.org/sygnal/receiver",
                    "transactionId": "%s", "messageId": "%s", "timestamp": "2026-11-13T08:00:35Z"
                  },
                  "message": { "events": [ %s ] }
                }
                """.formatted(NETWORK_ID, senderId, receiverId, OPENMRS_TXN, MESSAGE_ID, events);
    }

    public static String report(String resourceJson) {
        return report(OPENMRS_ID, SELF_ID, event(EVENT_ID, resourceJson));
    }

    public static String event(String id, String resourceJson) {
        return """
                {
                  "@type": "sygnal:CoordinationEvent",
                  "id": "%s",
                  "revision": 1,
                  "eventType": "DELIVERY_RECORDED",
                  "granularity": "INDIVIDUAL",
                  "from": { "id": "%s", "role": "PROVIDER" },
                  "occurredAt": "%s",
                  "eventAttributes": {
                    "@context": "https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceClinicalEvent/v1.0/context.jsonld",
                    "@type": "cce:CceClinicalEvent",
                    "eventType": "Encounter",
                    "patientId": "1198503150001234",
                    "fhirVersion": "4.0.1",
                    "sourceEventId": "%s",
                    "resource": %s
                  }
                }
                """.formatted(id, OPENMRS_ID, OCCURRED_AT, SOURCE_EVENT_ID, resourceJson);
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
