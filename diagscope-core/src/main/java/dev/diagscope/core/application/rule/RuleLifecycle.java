package dev.diagscope.core.application.rule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Lifecycle state of every rule identifier the tool has ever published.
 *
 * <p>Rule identifiers are a public contract: they appear in {@code diagscope.yml}, in baselines, in
 * waivers, in SARIF, and in dashboards built on {@code result.json}. Renaming or dropping one is a
 * versioned event, never a silent edit. This class is the single place recording that event.</p>
 *
 * <ul>
 *   <li>{@code ACTIVE} — supported and enabled according to project policy;</li>
 *   <li>{@code DEPRECATED} — still evaluated and still reported, but scheduled for removal; the
 *       replacement, when one exists, is stated here;</li>
 *   <li>{@code REMOVED} — no longer evaluated; the identifier stays reserved so configuration that
 *       still references it fails with an explanation instead of an anonymous "unknown rule".</li>
 * </ul>
 *
 * <p>The transition order is fixed: a rule is deprecated for at least one released minor version
 * before it is removed, and a removed identifier is never reused for different semantics.</p>
 */
public final class RuleLifecycle {

    /** Lifecycle state of a rule identifier. */
    public enum Status { ACTIVE, DEPRECATED, REMOVED }

    /**
     * Recorded lifecycle of one rule identifier.
     *
     * @param ruleId     the rule identifier
     * @param status     current lifecycle state
     * @param since      release in which the state was reached, or {@code null} while active
     * @param replacedBy identifier that took over the detection, when there is one
     * @param note       short human explanation shown by the CLI and the documentation
     */
    public record Entry(String ruleId, Status status, String since, String replacedBy, String note) {
        public Entry {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(note, "note");
        }

        /** Returns the replacement rule identifier, when the retirement had one. */
        public Optional<String> replacement() {
            return Optional.ofNullable(replacedBy);
        }
    }

    private static final String ACTIVE_NOTE = "Supported rule; enabled unless disabled by project policy.";

    private static final Map<String, Entry> RETIRED = retired();

    private RuleLifecycle() {
    }

    /** Returns the lifecycle entry of a rule identifier, defaulting to {@link Status#ACTIVE}. */
    public static Entry of(String ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        var retired = RETIRED.get(ruleId);
        if (retired != null) return retired;
        return new Entry(ruleId, Status.ACTIVE, null, null, ACTIVE_NOTE);
    }

    /** Returns {@code true} when the identifier is still evaluated by the engine. */
    public static boolean isEvaluated(String ruleId) {
        return of(ruleId).status() != Status.REMOVED;
    }

    /** Returns the replacement identifier recorded for a deprecated or removed rule. */
    public static Optional<String> replacement(String ruleId) {
        return of(ruleId).replacement();
    }

    /** Returns every deprecated or removed identifier, sorted by rule id. */
    public static Map<String, Entry> retirements() {
        return RETIRED;
    }

    /**
     * Returns every known identifier with its lifecycle entry: the active catalog plus every
     * reserved retired identifier.
     */
    public static Map<String, Entry> all() {
        var lifecycle = new TreeMap<String, Entry>();
        RuleCatalog.all().keySet().forEach(ruleId -> lifecycle.put(ruleId, of(ruleId)));
        lifecycle.putAll(RETIRED);
        return Map.copyOf(lifecycle);
    }

    private static Map<String, Entry> retired() {
        var entries = new LinkedHashMap<String, Entry>();
        // Rules whose premise turned out to be wrong are removed without the usual deprecation period: keeping a
        // detection that reports a non-existent problem does more harm than a one-release configuration error.
        entries.put("AOP_ADVICE_ON_PRIVATE_METHOD", new Entry("AOP_ADVICE_ON_PRIVATE_METHOD", Status.REMOVED, "0.1.2",
                null, "Spring AOP invokes @Around/@Before/... advice methods reflectively after making them"
                + " accessible, so a private advice method does run. The rule confused the advice method with the"
                + " advised method; AOP_ADVICE_NOT_APPLIED covers advice that cannot reach a private target."));
        entries.put("SYNCHRONIZED_ON_SPRING_BEAN", new Entry("SYNCHRONIZED_ON_SPRING_BEAN", Status.REMOVED, "0.1.2",
                null, "A Spring proxy delegates to the target object, so a synchronized method locks the target"
                + " instance, not the proxy. For a singleton bean the synchronisation works as written."));
        entries.put("THREAD_LOCAL_LEAK", new Entry("THREAD_LOCAL_LEAK", Status.REMOVED, "0.1.2",
                "THREAD_LOCAL_NOT_REMOVED", "Superseded: THREAD_LOCAL_NOT_REMOVED reports the same ThreadLocal.set() without"
                + " remove(), so every case was reported twice."));
        var sorted = new TreeMap<String, Entry>(entries);
        return Map.copyOf(sorted);
    }
}
