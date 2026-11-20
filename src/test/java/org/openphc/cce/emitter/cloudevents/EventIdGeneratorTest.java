package org.openphc.cce.emitter.cloudevents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.model.SourceMetadata;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EventIdGenerator}.
 */
class EventIdGeneratorTest {

    private EventIdGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new EventIdGenerator();
    }

    // --- A given event id (Sygnal) is kept ---

    @Test
    void generate_withGivenEventId_keepsIt() {
        SourceMetadata meta = new SourceMetadata(
                "openmrs", "0002", "enc-visit-001", "corr-123",
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound", "3f6c1c1e-8a53-4b0e-9d7a-2b6f4e1a9c10");

        assertThat(generator.generate(meta)).isEqualTo("3f6c1c1e-8a53-4b0e-9d7a-2b6f4e1a9c10");
    }

    // --- Deterministic ID generation (sourceEventId present) ---

    @Test
    void generate_withSourceEventId_returnsDeterministicUuid() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", "0002", "enc-visit-001", "corr-123",
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound");

        String id1 = generator.generate(meta);
        String id2 = generator.generate(meta);

        assertThat(id1).isNotNull().isNotBlank();
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void generate_withSourceEventId_producesValidUuidFormat() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", "0002", "enc-visit-001", "corr-123",
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound");

        String id = generator.generate(meta);

        // UUID format: 8-4-4-4-12 hex characters
        assertThat(id).matches("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }

    @Test
    void generate_withDifferentSourceEventId_returnsDifferentIds() {
        SourceMetadata meta1 = new SourceMetadata(
                "ebuzima", "0002", "enc-visit-001", "corr-123",
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound");
        SourceMetadata meta2 = new SourceMetadata(
                "ebuzima", "0002", "enc-visit-002", "corr-456",
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound");

        String id1 = generator.generate(meta1);
        String id2 = generator.generate(meta2);

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void generate_withDifferentSourceIdentifier_returnsDifferentIds() {
        SourceMetadata meta1 = new SourceMetadata(
                "ebuzima", "0002", "enc-visit-001", "corr-123",
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound");
        SourceMetadata meta2 = new SourceMetadata(
                "other-source", "0002", "enc-visit-001", "corr-123",
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound");

        String id1 = generator.generate(meta1);
        String id2 = generator.generate(meta2);

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void generate_withSameInputs_isIdempotent() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", "0002", "enc-visit-001", "corr-123",
                OffsetDateTime.of(2026, 2, 25, 8, 0, 0, 0, ZoneOffset.UTC), "/inbound");

        // Generate multiple times — should always return the same UUID
        String id1 = generator.generate(meta);
        String id2 = generator.generate(meta);
        String id3 = generator.generate(meta);

        assertThat(id1).isEqualTo(id2).isEqualTo(id3);
    }

    // --- Random ID generation (sourceEventId absent) ---

    @Test
    void generate_withNullSourceEventId_returnsRandomUuid() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", "0002", null, "corr-123",
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound");

        String id1 = generator.generate(meta);
        String id2 = generator.generate(meta);

        assertThat(id1).isNotNull().isNotBlank();
        assertThat(id2).isNotNull().isNotBlank();
        // Random UUIDs should be different (practically always)
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void generate_withBlankSourceEventId_returnsRandomUuid() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", "0002", "   ", "corr-123",
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound");

        String id1 = generator.generate(meta);
        String id2 = generator.generate(meta);

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void generate_withNullMeta_returnsRandomUuid() {
        String id = generator.generate(null);

        assertThat(id).isNotNull().isNotBlank();
        // Should be a valid UUID format (v4 random)
        assertThat(id).matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }

    @Test
    void generate_randomUuid_producesValidUuidFormat() {
        SourceMetadata meta = new SourceMetadata(
                "ebuzima", "0002", null, null,
                OffsetDateTime.now(ZoneOffset.UTC), "/inbound");

        String id = generator.generate(meta);

        // UUID v4 format: version 4 in byte 6
        assertThat(id).matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }
}
