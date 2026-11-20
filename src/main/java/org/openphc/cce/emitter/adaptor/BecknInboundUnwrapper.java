package org.openphc.cce.emitter.adaptor;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.openphc.cce.emitter.config.BecknProperties;
import org.openphc.cce.emitter.exception.BecknNackException;
import org.openphc.cce.emitter.model.beckn.CceClinicalEvent;
import org.openphc.cce.emitter.model.beckn.ErrorCodes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Takes an HMIS's {@code sygnal/event/report} apart (spec §3.1): finds the subscriber by
 * {@code context.senderId}, checks that the report is addressed to this HIE and holds exactly one
 * event with a UUID id, a {@code cce:CceClinicalEvent} box and a FHIR resource, and builds the
 * headers for the OpenHIM channel (spec §3.2). Every failed check is a {@code Nack} (400) with a
 * canonical Beckn code whose text names the field.
 */
@Component
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknInboundUnwrapper {

    public static final String HEADER_SOURCE_SYSTEM = "X-Source-System";
    public static final String HEADER_SOURCE_EVENT_ID = "X-Source-Event-Id";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_SYGNAL_EVENT_ID = "X-Sygnal-Event-Id";
    public static final String HEADER_SYGNAL_OCCURRED_AT = "X-Sygnal-Occurred-At";

    // UUID.fromString() accepts short forms such as "1-1-1-1-1". The Sygnal schema does not.
    private static final Pattern UUID_FORM =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final BecknProperties props;

    public BecknInboundUnwrapper(BecknProperties props) {
        this.props = props;
    }

    /**
     * An unwrapped event.
     *
     * @param subscriber    the HMIS that sent it
     * @param messageId     {@code context.messageId}
     * @param transactionId {@code context.transactionId}
     * @param eventId       {@code events[0].id}, kept as the CloudEvent id
     * @param occurredAt    {@code events[0].occurredAt}, or {@code context.timestamp} when it has none
     * @param eventType     the box's {@code eventType} (logged only)
     * @param resource      the FHIR resource, as it is
     * @param headers       the headers for the channel, without {@code Authorization} and {@code Content-Type}
     */
    public record UnwrappedEvent(
            BecknProperties.Subscriber subscriber,
            String messageId,
            String transactionId,
            String eventId,
            String occurredAt,
            String eventType,
            JsonNode resource,
            Map<String, String> headers
    ) {}

    public UnwrappedEvent unwrap(JsonNode body) {
        JsonNode context = body.path("context");

        // The sender: one of the HIE's subscribers
        String senderId = text(context, "senderId");
        if (senderId.isBlank()) {
            throw BecknNackException.badRequest(ErrorCodes.CTX_MISSING_FIELD, "context.senderId is missing");
        }
        BecknProperties.Subscriber subscriber = props.subscriber(senderId)
                .orElseThrow(() -> BecknNackException.badRequest(ErrorCodes.CTX_INVALID_FIELD,
                        "context.senderId '" + senderId + "' is not a subscriber of this HIE"));

        // The receiver: this HIE
        String receiverId = text(context, "receiverId");
        if (receiverId.isBlank()) {
            throw BecknNackException.badRequest(ErrorCodes.CTX_MISSING_FIELD, "context.receiverId is missing");
        }
        if (!receiverId.equals(props.self().id())) {
            throw BecknNackException.badRequest(ErrorCodes.CTX_INVALID_FIELD,
                    "context.receiverId is '" + receiverId + "', but this HIE is '" + props.self().id() + "'");
        }
        String messageId = text(context, "messageId");
        if (messageId.isBlank()) {
            throw BecknNackException.badRequest(ErrorCodes.CTX_MISSING_FIELD, "context.messageId is missing");
        }

        // Exactly one event with a UUID id, revision 1, from the sender
        JsonNode events = body.path("message").path("events");
        if (!events.isArray() || events.size() != 1) {
            throw BecknNackException.badRequest(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED,
                    "message.events must hold exactly one event");
        }
        JsonNode event = events.get(0);
        String eventId = text(event, "id");
        if (eventId.isBlank()) {
            throw BecknNackException.badRequest(ErrorCodes.SCH_REQUIRED_FIELD_MISSING, "events[0].id is missing");
        }
        if (!UUID_FORM.matcher(eventId).matches()) {
            throw BecknNackException.badRequest(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED,
                    "events[0].id must be a UUID; CCE de-duplicates on it");
        }
        JsonNode revision = event.path("revision");
        if (!revision.isMissingNode() && revision.asInt(-1) != 1) {
            throw BecknNackException.badRequest(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED,
                    "events[0].revision must be 1. CCE never replaces an event; send a correction as a new event");
        }
        String fromId = text(event.path("from"), "id");
        if (!fromId.isBlank() && !fromId.equals(senderId)) {
            throw BecknNackException.badRequest(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED,
                    "events[0].from.id must equal context.senderId");
        }
        String occurredAt = text(event, "occurredAt");
        String occurredAtField = "events[0].occurredAt";
        if (occurredAt.isBlank()) {
            occurredAt = text(context, "timestamp");
            occurredAtField = "context.timestamp";
        }
        if (!occurredAt.isBlank() && !isTime(occurredAt)) {
            throw BecknNackException.badRequest(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED,
                    occurredAtField + " is not an ISO-8601 date-time");
        }

        // The box and its FHIR resource
        JsonNode attrs = event.path("eventAttributes");
        if (!CceClinicalEvent.TYPE.equals(text(attrs, "@type"))) {
            throw BecknNackException.badRequest(ErrorCodes.SCH_TYPE_NOT_SUPPORTED,
                    "events[0].eventAttributes.@type must be " + CceClinicalEvent.TYPE);
        }
        JsonNode resource = attrs.path("resource");
        if (!resource.isObject() || text(resource, "resourceType").isBlank()) {
            throw BecknNackException.badRequest(ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED,
                    "events[0].eventAttributes.resource must be a FHIR resource with a resourceType");
        }

        // The HMIS's own id of the event, when it gave one that fits in a header; else the event id
        String sourceEventId = text(attrs, "sourceEventId");
        if (!headerSafe(sourceEventId)) {
            sourceEventId = eventId;
        }
        return new UnwrappedEvent(subscriber, messageId, text(context, "transactionId"), eventId, occurredAt,
                text(attrs, "eventType"), resource,
                headers(subscriber, eventId, sourceEventId, occurredAt, headerSafe(messageId) ? messageId : null));
    }

    /** The headers of spec §3.2 (the channel client adds {@code Authorization} and {@code Content-Type}). */
    static Map<String, String> headers(BecknProperties.Subscriber subscriber, String eventId, String sourceEventId,
                                       String occurredAt, String messageId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_SOURCE_SYSTEM, subscriber.sourceSystem());
        headers.put(HEADER_SOURCE_EVENT_ID, sourceEventId);
        headers.put(HEADER_SYGNAL_EVENT_ID, eventId);
        if (occurredAt != null && !occurredAt.isBlank()) {
            headers.put(HEADER_SYGNAL_OCCURRED_AT, occurredAt);
        }
        if (messageId != null && !messageId.isBlank()) {
            headers.put(HEADER_CORRELATION_ID, messageId);
        }
        return headers;
    }

    /** Not blank, at most 200 characters, printable ASCII only (no line breaks in a header). */
    static boolean headerSafe(String value) {
        return value != null && !value.isBlank() && value.length() <= 200
                && value.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
    }

    private static boolean isTime(String value) {
        try {
            OffsetDateTime.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isValueNode() ? v.asText("") : "";
    }
}
