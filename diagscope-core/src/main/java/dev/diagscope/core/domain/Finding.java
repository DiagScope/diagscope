package dev.diagscope.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record Finding(
        String ruleId,
        Severity severity,
        Confidence confidence,
        SourceLocation location,
        String message,
        String recommendation,
        List<RelatedFlow> relatedFlows,
        Map<String, String> evidence
) {
    /** Increment whenever the fingerprint inputs change, so stored baselines can be migrated. */
    public static final int FINGERPRINT_VERSION = 1;

    private static final String FINGERPRINT_NAMESPACE = "diagscope.finding";

    private static final Comparator<RelatedFlow> RELATED_FLOW_ORDER =
            Comparator.comparing(RelatedFlow::id).thenComparing(RelatedFlow::displayName);


    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(recommendation, "recommendation");
        Objects.requireNonNull(relatedFlows, "relatedFlows");
        Objects.requireNonNull(evidence, "evidence");
        if (ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        relatedFlows = normalizeRelatedFlows(relatedFlows);
        evidence = Collections.unmodifiableMap(new TreeMap<>(Map.copyOf(evidence)));
    }

    /**
     * Returns the stable identity used to deduplicate findings and to compare runs over time.
     *
     * <p>Identity is built from the rule, the fingerprint version, the normalized relative path,
     * and the sorted rule evidence (which carries the declaring type, the method signature, and the
     * normalized expression). Line numbers are deliberately excluded: they are useful for
     * navigation, but they change whenever unrelated code above the finding moves, which would
     * otherwise invalidate baselines and suppressions.</p>
     */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, FINGERPRINT_NAMESPACE);
            update(digest, Integer.toString(FINGERPRINT_VERSION));
            update(digest, ruleId);
            update(digest, normalizedPath(location));
            for (var entry : evidence.entrySet()) {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }


    public static String normalizedPath(SourceLocation location) {
        Objects.requireNonNull(location, "location");
        return location.file().normalize().toString().replace('\\', '/');
    }

    private static List<RelatedFlow> normalizeRelatedFlows(List<RelatedFlow> relatedFlows) {
        var byId = new TreeMap<String, RelatedFlow>();
        for (var relatedFlow : relatedFlows) {
            Objects.requireNonNull(relatedFlow, "relatedFlows must not contain null");
            byId.merge(relatedFlow.id(), relatedFlow, Finding::mergeRelatedFlow);
        }
        var normalized = new ArrayList<>(byId.values());
        normalized.sort(RELATED_FLOW_ORDER);
        return List.copyOf(normalized);
    }

    private static RelatedFlow mergeRelatedFlow(RelatedFlow left, RelatedFlow right) {
        String displayName = left.displayName().compareTo(right.displayName()) <= 0
                ? left.displayName() : right.displayName();
        return new RelatedFlow(left.id(), displayName, Confidence.min(left.confidence(), right.confidence()));
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
