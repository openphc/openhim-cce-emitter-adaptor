package org.openphc.cce.emitter.cloudevents;

import org.openphc.cce.emitter.model.SourceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Generates unique event IDs for CloudEvents envelopes.
 *
 * <p>ID generation strategy:
 * <ul>
 *   <li>If the metadata carries an {@code eventId} (the HMIS's Sygnal event id, a UUID), it is
 *       kept as it is: CCE de-duplicates on the same id on every hop.</li>
 *   <li>Else, if {@code sourceEventId} is present in the metadata, a <strong>deterministic</strong>
 *       UUID v5 (name-based, SHA-1) is generated from {@code sourceIdentifier + sourceEventId}.
 *       This ensures idempotent event IDs for the same source event, supporting deduplication.</li>
 *   <li>If {@code sourceEventId} is absent, a random {@link UUID#randomUUID()} is generated.</li>
 * </ul>
 *
 * <p>The deterministic path uses the DNS namespace UUID as the base namespace for UUID v5 generation.
 */
@Component
public class EventIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(EventIdGenerator.class);

    /**
     * UUID v5 namespace — uses the well-known DNS namespace as the base.
     * RFC 4122 Appendix C: {@code 6ba7b810-9dad-11d1-80b4-00c04fd430c8}
     */
    private static final UUID NAMESPACE_DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    /**
     * Generates a CloudEvent ID from the given source metadata.
     *
     * @param meta the source metadata containing identifiers
     * @return a UUID string — the given event id, else deterministic if sourceEventId is present, random otherwise
     */
    public String generate(SourceMetadata meta) {
        if (meta != null && meta.eventId() != null && !meta.eventId().isBlank()) {
            log.debug("Kept the given event ID '{}'", meta.eventId());
            return meta.eventId();
        }
        if (meta != null
                && meta.sourceEventId() != null
                && !meta.sourceEventId().isBlank()) {

            String name = meta.sourceIdentifier() + ":" + meta.sourceEventId();
            String id = generateUuidV5(name);
            log.debug("Generated deterministic event ID '{}' from name '{}'", id, name);
            return id;
        }

        String id = UUID.randomUUID().toString();
        log.debug("Generated random event ID '{}'", id);
        return id;
    }

    /**
     * Generates a UUID v5 (SHA-1 name-based) from the given name string,
     * using the DNS namespace as the base namespace.
     */
    private String generateUuidV5(String name) {
        return nameUUIDFromNamespaceAndString(NAMESPACE_DNS, name).toString();
    }

    /**
     * Implements UUID v5 generation per RFC 4122 §4.3.
     *
     * <p>Combines a namespace UUID with a name string, hashes with SHA-1,
     * and sets the version (5) and variant (RFC 4122) bits.
     */
    static UUID nameUUIDFromNamespaceAndString(UUID namespace, String name) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(toBytes(namespace));
            md.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] sha1 = md.digest();

            // Set version 5 (bits 4-7 of byte 6)
            sha1[6] = (byte) ((sha1[6] & 0x0F) | 0x50);
            // Set variant to RFC 4122 (bits 6-7 of byte 8)
            sha1[8] = (byte) ((sha1[8] & 0x3F) | 0x80);

            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (sha1[i] & 0xFF);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (sha1[i] & 0xFF);
            }

            return new UUID(msb, lsb);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed by Java spec — should never happen
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (8 * (7 - i)));
        }
        for (int i = 8; i < 16; i++) {
            bytes[i] = (byte) (lsb >>> (8 * (15 - i)));
        }
        return bytes;
    }
}
