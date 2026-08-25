package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports non-atomic check-then-act patterns on {@code Map} or {@code Collection} instances.
 *
 * <p>A pattern such as:
 * <pre>{@code
 * if (!map.containsKey(key)) {
 *     map.put(key, value);        // <-- race: another thread may have put in between
 * }
 * }</pre>
 * is not atomic. Between the {@code containsKey} check and the {@code put} a concurrent
 * thread can insert the same key, causing a lost update, duplicate work, or incorrect
 * state.</p>
 *
 * <p>The Java Collections API provides atomic alternatives:
 * {@link java.util.concurrent.ConcurrentHashMap#putIfAbsent},
 * {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent},
 * {@link java.util.Map#merge}, and
 * {@link java.util.concurrent.CopyOnWriteArrayList#addIfAbsent}.</p>
 *
 * <p>A finding is only emitted when both the check and the mutating call target the
 * <em>same named receiver</em> and no atomic variant is already present on that
 * receiver — this keeps the false-positive rate low.</p>
 */
public final class CheckThenActOnMapRule implements DiagnosticRule {

    public static final String ID = "CHECK_THEN_ACT_ON_MAP";

    /** Methods that test membership without mutating the collection. */
    private static final Set<String> CHECK_METHODS = Set.of(
            "containsKey", "contains", "containsValue");

    /** Methods that mutate the collection and must be atomic with the preceding check. */
    private static final Set<String> MUTATE_METHODS = Set.of(
            "put", "add", "set", "remove", "replace");

    /**
     * Methods that already encode the check-then-act atomically.
     * When the caller also uses one of these we suppress the finding.
     */
    private static final Set<String> ATOMIC_ALTERNATIVES = Set.of(
            "putIfAbsent", "computeIfAbsent", "computeIfPresent", "compute",
            "merge", "addIfAbsent", "replaceAll", "replaceIfPresent");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            var invocations = method.invocations();

            // receivers that are already using an atomic alternative in this method
            var atomicReceivers = new HashSet<String>();
            // receivers where a check method was observed, mapped to the first evidence
            var checkReceivers = new HashMap<String, InvocationEvidence>();

            for (var inv : invocations) {
                String scope = scope(inv);
                if (scope.isBlank()) continue;
                if (ATOMIC_ALTERNATIVES.contains(inv.methodName())) {
                    atomicReceivers.add(scope);
                }
                if (CHECK_METHODS.contains(inv.methodName()) && !checkReceivers.containsKey(scope)) {
                    checkReceivers.put(scope, inv);
                }
            }

            // one finding per receiver (at the location of the check call)
            for (var inv : invocations) {
                if (!MUTATE_METHODS.contains(inv.methodName())) continue;
                String scope = scope(inv);
                if (!checkReceivers.containsKey(scope)) continue;
                if (atomicReceivers.contains(scope)) continue;

                InvocationEvidence checkInv = checkReceivers.get(scope);
                Confidence confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, checkInv.location(),
                        "Non-atomic check-then-act on '" + scope + "': " + checkInv.methodName()
                                + "() followed by " + inv.methodName()
                                + "() is a race condition under concurrent access.",
                        "Replace the check-then-act with an atomic operation: Map.putIfAbsent(),"
                                + " Map.computeIfAbsent(), Map.merge(), or"
                                + " ConcurrentHashMap.compute(). These are guaranteed atomic and"
                                + " eliminate the race window regardless of the collection type.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "receiver", scope,
                                "checkMethod", checkInv.methodName(),
                                "mutateMethod", inv.methodName())
                ));
                // one finding per receiver is enough
                checkReceivers.remove(scope);
            }
        }
        return List.copyOf(findings);
    }

    private static String scope(InvocationEvidence inv) {
        String s = inv.scope();
        return s == null ? "" : s.trim();
    }
}
