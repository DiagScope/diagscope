package dev.diagscope.core.application.rule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Semantic version of the evidence contract of each rule.
 *
 * <p>The version tracks the <em>detection contract</em>, not the wording in {@link RuleCatalog}:
 * catalog text can be improved freely, while a change in what a rule reports, or in the evidence
 * keys it attaches, must bump the version here. CI integrations use it to tell a real detection
 * change apart from a documentation change when findings move between runs.</p>
 *
 * <ul>
 *   <li>patch — precision fix; the same situations are reported with fewer false positives.</li>
 *   <li>minor — additional situations reported, or new evidence keys added.</li>
 *   <li>major — reported situations or evidence keys removed or renamed.</li>
 * </ul>
 */
public final class RuleVersions {
    /** Version assumed for rules that have not been versioned explicitly (plugins included). */
    public static final String INITIAL_VERSION = "1.0.0";

    private static final Map<String, String> OVERRIDES = overrides();
    private static final Map<String, String> VERSIONS = buildVersions();

    private RuleVersions() {
    }

    /** Returns the evidence contract version of a rule, defaulting to {@link #INITIAL_VERSION}. */
    public static String versionOf(String ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        return VERSIONS.getOrDefault(ruleId, INITIAL_VERSION);
    }

    /** Returns every documented rule id with its evidence contract version, sorted by rule id. */
    public static Map<String, String> all() {
        return VERSIONS;
    }

    private static Map<String, String> buildVersions() {
        var versions = new TreeMap<String, String>();
        RuleCatalog.all().keySet()
                .forEach(ruleId -> versions.put(ruleId, OVERRIDES.getOrDefault(ruleId, INITIAL_VERSION)));
        OVERRIDES.forEach(versions::putIfAbsent);
        return Map.copyOf(versions);
    }

    private static Map<String, String> overrides() {
        var overrides = new LinkedHashMap<String, String>();
        // Kafka producer coverage was generalized to producer listeners and async handoffs.
        overrides.put(IgnoredKafkaSendResultRule.ID, "1.2.0");
        // Manual acknowledgement detection was extended to class-level listeners and @KafkaHandler.
        overrides.put(KafkaManualAckMissingRule.ID, "1.1.0");
        // Metric rules gained exact Micrometer type resolution and provenance evidence.
        overrides.put(HighCardinalityMetricTagRule.ID, "1.1.0");
        // Transactional analysis gained self-invocation and propagation evidence.
        overrides.put(TransactionalRollbackSuppressedRule.ID, "1.1.0");
        // Mutiny recovery covers Multi operators and no longer reports chains that already observe
        // the failure through onFailure().invoke/call.
        overrides.put(MutinyFailureRecoveredSilentlyRule.ID, "1.1.0");
        // Mutiny subscription detection tolerates formatting variations in the subscribe() receiver.
        overrides.put(MutinySubscriptionFailureUnobservedRule.ID, "1.0.1");
        return Map.copyOf(overrides);
    }
}
