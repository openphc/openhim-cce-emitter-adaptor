package org.openphc.cce.emitter.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.openphc.cce.emitter.redaction.ClinicalDataRedactionProperties.ResourceRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Strips clinical findings from a FHIR resource before it leaves the adaptor.
 *
 * <p>Applied at the last step of the inbound pipeline — after the patient UPID, facility ID and
 * clinical timestamp have already been extracted from the complete resource — so redaction cannot
 * affect routing, facility attribution or SLA timing. What leaves the adaptor, and therefore what
 * is persisted in {@code inbound_event_log}, Kafka, {@code compliance_event_log} and the ClickHouse
 * mirror, is the minimised payload.
 *
 * <p>What is removed is the union of the {@code "*"} rule, applied to every resource, and the
 * resource's own rule if it has one. Which fields those are depends on the resource type, because
 * the same element carries very different sensitivity per resource — see
 * {@link ClinicalDataRedactionProperties}.
 *
 * <p>Redaction removes named root-level fields plus explicitly configured nested paths. It is
 * never a blanket recursive scrub: that would also strip the {@code valueString} inside
 * {@code extension[]}, which is how {@code source-facility} attribution works, and the
 * {@code coding}/{@code display} elements protocol matching and the dashboard depend on. Every
 * nested removal is therefore something a human chose.
 *
 * <h2>Unmatched paths are reported</h2>
 *
 * <p>A configured path that matches nothing is the dangerous failure mode for a compliance control:
 * it looks configured and redacts nothing. Where a path walks into an array without the {@code []}
 * marker, this class logs a warning and increments
 * {@code cce.emitter.redaction.path.mismatch.total} rather than passing over it silently.
 *
 * <p>The result stays structurally valid FHIR R4 — the collector re-parses every payload with HAPI
 * and rejects anything malformed as {@code INVALID_FHIR}.
 */
@Component
public class ClinicalDataRedactor {

    private static final Logger log = LoggerFactory.getLogger(ClinicalDataRedactor.class);

    /** Marks a path element that steps through an array, e.g. {@code reaction[]}. */
    private static final String ARRAY_MARKER = "[]";

    private final boolean enabled;
    /** Applied to every resource, whatever its type. */
    private final List<CompiledPath> allTypesPaths;
    /** resourceType → the entries specific to that type, applied on top of {@link #allTypesPaths}. */
    private final Map<String, List<CompiledPath>> pathsByResourceType;
    private final MeterRegistry meterRegistry;

    /** Warn once per path+resourceType — at PROD volumes this would otherwise flood the log. */
    private final Set<String> reportedMismatches = ConcurrentHashMap.newKeySet();

    public ClinicalDataRedactor(ClinicalDataRedactionProperties properties, MeterRegistry meterRegistry) {
        this.enabled = Boolean.TRUE.equals(properties.enabled());
        this.meterRegistry = meterRegistry;

        this.allTypesPaths = compile(properties.getAllResourceTypesFields());

        this.pathsByResourceType = new LinkedHashMap<>();
        for (ResourceRule rule : properties.rules()) {
            if (rule == null || rule.resourceType() == null) {
                continue;
            }
            String type = rule.resourceType().trim();
            if (ClinicalDataRedactionProperties.ALL_RESOURCE_TYPES.equals(type)) {
                continue;   // already held as allTypesPaths
            }
            pathsByResourceType.put(type, compile(rule.fields()));
        }

        if (enabled) {
            log.info("Clinical-data redaction ACTIVE — {} entr(ies) applied to all resources, "
                            + "plus type-specific rules for {} type(s) [{}]",
                    allTypesPaths.size(),
                    pathsByResourceType.size(),
                    String.join(", ", pathsByResourceType.keySet()));
        } else {
            log.warn("Clinical-data redaction DISABLED — full clinical payloads will be forwarded to the collector");
        }
    }

    private static List<CompiledPath> compile(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(CompiledPath::parse)
                .toList();
    }

    /**
     * Returns the resource with clinical content removed, per the rule for its resource type.
     *
     * @param data the parsed FHIR resource destined for the CloudEvent {@code data} field
     * @return the same node, mutated in place; returned for call-site readability
     */
    public JsonNode redact(JsonNode data) {
        if (!enabled || data == null || !data.isObject()) {
            return data;
        }

        ObjectNode resource = (ObjectNode) data;
        String resourceType = resource.path("resourceType").asText("");

        List<String> removed = new ArrayList<>();
        applyAll(allTypesPaths, resource, resourceType, removed);
        applyAll(pathsByResourceType.getOrDefault(resourceType, List.of()), resource, resourceType, removed);

        if (!removed.isEmpty()) {
            Counter.builder("cce.emitter.events.redacted.total")
                    .description("Inbound events from which clinical fields were removed")
                    .tag("resource_type", resourceType.isEmpty() ? "unknown" : resourceType)
                    .register(meterRegistry)
                    .increment();
            // Field NAMES only — never their values, which are the clinical data itself.
            log.debug("Redacted {} field(s) from {}: {}", removed.size(), resourceType, removed);
        }

        return resource;
    }

    private void applyAll(List<CompiledPath> paths, ObjectNode resource,
                          String resourceType, List<String> removed) {
        for (CompiledPath path : paths) {
            if (path.removeFrom(resource, resourceType, this::reportMismatch)) {
                removed.add(path.raw());
            }
        }
    }

    /**
     * Records a configured path that could not be followed because the data held an array where the
     * path expected an object. The path is redacting nothing, so this is a configuration defect: it
     * is surfaced as a metric and a one-off warning rather than being silently skipped.
     */
    private void reportMismatch(String rawPath, String element, String resourceType) {
        Counter.builder("cce.emitter.redaction.path.mismatch.total")
                .description("Configured redaction paths that matched nothing because an array was "
                        + "found where an object was expected (add '[]' to the element)")
                .tag("resource_type", resourceType.isEmpty() ? "unknown" : resourceType)
                .tag("path", rawPath)
                .register(meterRegistry)
                .increment();

        if (reportedMismatches.add(resourceType + "|" + rawPath)) {
            log.warn("Redaction path '{}' does not match {} payloads: element '{}' is an array, "
                            + "but the path expects an object. NOTHING IS BEING REDACTED for this "
                            + "path — did you mean '{}[]'?",
                    rawPath, resourceType, element, element);
        }
    }

    /** A {@code remove-paths} entry parsed into elements, so the walk is not re-parsed per event. */
    private record CompiledPath(String raw, List<PathElement> elements) {

        /** One dot-delimited part of a path, e.g. {@code coding} or, with the {@code []} marker, {@code coding[]}. */
        private record PathElement(String fieldName, boolean array) {}

        static CompiledPath parse(String raw) {
            List<PathElement> elements = new ArrayList<>();
            // A raw entry like "code.coding[].display" becomes three PathElements: "code",
            // "coding" (array-typed), "display" — split purely on the dots.
            for (String part : raw.split("\\.")) {
                String fieldName = part.trim();
                boolean array = fieldName.endsWith(ARRAY_MARKER);
                if (array) {
                    // Strip the "[]" marker itself; it is a flag on the element, not part of the
                    // field name used to look the value up in the actual JSON.
                    fieldName = fieldName.substring(0, fieldName.length() - ARRAY_MARKER.length()).trim();
                }
                elements.add(new PathElement(fieldName, array));
            }
            return new CompiledPath(raw, List.copyOf(elements));
        }

        /**
         * Removes the leaf field everywhere this path reaches.
         *
         * @param onMismatch called when a path element expected an object but found an array
         * @return {@code true} if the field existed anywhere along the path and was removed
         */
        boolean removeFrom(ObjectNode root, String resourceType, MismatchReporter onMismatch) {
            // "objects" is the current frontier of the walk: every JSON object the path has
            // reached so far. It starts as just the resource root and, at every array-typed
            // element, fans out to hold one entry per array item — so by the time the loop below
            // finishes, it may hold many objects even though the path started at a single root.
            List<ObjectNode> objects = List.of(root);

            // Walk every element except the last: those are the steps that lead *to* the field
            // being removed, not the field itself.
            List<PathElement> parentElements = elements.subList(0, elements.size() - 1);
            for (PathElement pathElement : parentElements) {
                objects = descendInto(objects, pathElement, resourceType, onMismatch);
                if (objects.isEmpty()) {
                    // Nothing in the payload matched this element (e.g. an optional FHIR field
                    // that this resource doesn't carry) — not an error, just nothing to remove.
                    return false;
                }
            }

            // Whatever the walk reached, remove the final segment's field from all of it.
            return removeFieldFrom(objects);
        }

        /**
         * Reads one path element's field from every object reached so far, fanning out across
         * every item of an array when the path element is array-typed ({@code []}).
         *
         * @return the objects found at this path element; empty if none of {@code objects} had the field
         */
        private List<ObjectNode> descendInto(List<ObjectNode> objects, PathElement pathElement,
                                             String resourceType, MismatchReporter onMismatch) {
            List<ObjectNode> childObjects = new ArrayList<>();

            // For every object currently in the frontier, look up this element's field and
            // decide how to fold whatever is found there back into the next frontier.
            for (ObjectNode object : objects) {
                JsonNode value = object.get(pathElement.fieldName());
                if (value == null) {
                    // This object simply doesn't have the field — skip it, don't fail the walk.
                    continue;
                }
                if (value.isArray()) {
                    if (!pathElement.array()) {
                        // The configured path expected a single object here (no "[]"), but the
                        // data is an array. Descending into it anyway would silently redact
                        // nothing and look successful, so this is reported instead of ignored —
                        // The silent-no-op case this class exists to catch.
                        onMismatch.report(raw, pathElement.fieldName(), resourceType);
                        continue;
                    }
                    // Array-typed element: fan out — every object item in the array becomes a
                    // separate entry in the next frontier, so the rest of the path is applied to
                    // each one independently (e.g. every "coding" entry gets its own removal).
                    for (JsonNode item : value) {
                        if (item.isObject()) {
                            childObjects.add((ObjectNode) item);
                        }
                    }
                } else if (value.isObject()) {
                    // A [] marker on a single object is tolerated: FHIR sources vary on whether
                    // a cardinality-many element arrives as an array or a lone object.
                    childObjects.add((ObjectNode) value);
                }
                // Anything else (a scalar, null node, missing) is neither an object nor an array
                // to descend into, so it's silently left out of the next frontier.
            }

            return childObjects;
        }

        /** Removes the path's final field from every object the walk reached. */
        private boolean removeFieldFrom(List<ObjectNode> objects) {
            String leafField = elements.get(elements.size() - 1).fieldName();
            boolean removed = false;
            // "removed" tracks whether the field existed *anywhere* the path reached — a path
            // that fanned out across ten array items but only found the field on three of them
            // still counts as having done its job.
            for (ObjectNode object : objects) {
                if (object.has(leafField)) {
                    object.remove(leafField);
                    removed = true;
                }
            }
            return removed;
        }
    }

    @FunctionalInterface
    private interface MismatchReporter {
        void report(String rawPath, String element, String resourceType);
    }
}
