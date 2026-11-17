package org.openphc.cce.emitter.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * The commitments the Beckn messages carry, read once at startup: CCE's fixed
 * {@code cce-care-coordination} commitment (in every {@code update} to CCE) and each subscriber's
 * HIE feed commitment (in every {@code on_update} to that HMIS). A file that cannot be read stops
 * the start.
 */
@Component
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknCommitments {

    /** Used when a subscriber names no {@code commitment-file}. */
    public static final String DEFAULT_HIE_FEED_FILE = "classpath:beckn/commitment-hie-feed.json";

    private final JsonNode cce;
    private final Map<String, JsonNode> bySubscriber = new HashMap<>();

    public BecknCommitments(BecknProperties props, ResourceLoader resourceLoader, ObjectMapper mapper) {
        this.cce = read(resourceLoader, mapper, props.cce().commitmentFile());
        for (BecknProperties.Subscriber s : props.subscribers()) {
            bySubscriber.put(s.subscriberId(), read(resourceLoader, mapper, subscriberFile(s)));
        }
    }

    /** CCE's commitment, a copy for the caller to put into a message. */
    public JsonNode cce() {
        return cce.deepCopy();
    }

    /** The HIE feed commitment of a subscriber, a copy. */
    public JsonNode forSubscriber(String subscriberId) {
        JsonNode c = bySubscriber.get(subscriberId);
        if (c == null) {
            throw new IllegalArgumentException("No commitment for subscriber " + subscriberId);
        }
        return c.deepCopy();
    }

    static String subscriberFile(BecknProperties.Subscriber s) {
        return s.commitmentFile() == null || s.commitmentFile().isBlank() ? DEFAULT_HIE_FEED_FILE : s.commitmentFile();
    }

    /**
     * Reads a commitment file as a JSON object.
     *
     * @throws IllegalStateException when the file is missing, unreadable or not a JSON object
     */
    public static JsonNode read(ResourceLoader resourceLoader, ObjectMapper mapper, String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("A Beckn commitment-file is not set");
        }
        Resource resource = resourceLoader.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            JsonNode node = mapper.readTree(in);
            if (node == null || !node.isObject() || !node.hasNonNull("id")) {
                throw new IllegalStateException("Beckn commitment-file " + location + " is not a JSON object with an id");
            }
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("Beckn commitment-file " + location + " cannot be read: " + e.getMessage(), e);
        }
    }
}
