package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reports a value returned by {@code Map.get(key)} (or similar map-like structures) that is
 * assigned to a local variable and later used as a method-call receiver without a visible null
 * guard. {@code Map.get(key)} returns {@code null} when the key is absent.
 *
 * <p>The detection strategy mirrors {@link EntityManagerFindDereferenceRule}: find a {@code get}
 * call on a map-looking receiver with an assigned result, then look for a later dereference of
 * that variable without an intervening guard.</p>
 */
public final class MapGetDereferencedWithoutCheckRule implements DiagnosticRule {
    public static final String ID = "MAP_GET_DEREFERENCED_WITHOUT_CHECK";

    /** Receiver type / scope hints that identify Map-like structures. */
    private static final Set<String> MAP_HINTS = Set.of(
            "map", "cache", "registry", "store", "index", "lookup",
            "hashmap", "treemap", "linkedhashmap", "concurrenthashmap",
            "concurrentmap", "multimap", "hashtable");

    /**
     * Methods called on the value variable itself that make the missing-key case explicit.
     * Must appear between the get() call and the dereference.
     */
    private static final Set<String> VALUE_GUARD_METHODS = Set.of(
            "requireNonNull", "requireNonNullElse", "checkNotNull", "notNull",
            "isNull", "nonNull", "ofNullable");

    /**
     * Methods called on the map receiver (same scope as the get()) that confirm key presence.
     * These typically appear BEFORE the get() and still guard the value.
     */
    private static final Set<String> RECEIVER_GUARD_METHODS = Set.of(
            "containsKey", "containsValue", "getOrDefault", "computeIfAbsent", "putIfAbsent");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var getCall : method.invocations()) {
                if (!isMapGet(getCall)) continue;
                String variable = getCall.assignedTo();
                if (variable.isBlank()) continue;

                firstDereferenceAfter(method.invocations(), getCall, variable).ifPresent(dereference -> {
                    if (hasNullGuardBefore(method.invocations(), variable, getCall, dereference)) return;

                    var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                    findings.add(new Finding(
                            ID, Severity.WARNING, confidence, dereference.location(),
                            "Map.get() result '" + variable
                                    + "' is dereferenced without a visible null guard.",
                            "Guard against a missing key: use getOrDefault(key, fallback),"
                                    + " containsKey(key) before dereferencing,"
                                    + " computeIfAbsent(key, fn), or wrap with Optional.ofNullable(...).",
                            List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                            Map.of(
                                    "method", method.id().displayName(),
                                    "assignedTo", variable,
                                    "dereference", variable + '.' + dereference.methodName()
                            )));
                });
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isMapGet(InvocationEvidence invocation) {
        if (!"get".equals(invocation.methodName())) return false;
        // Require exactly one argument (the key) — distinguishes Map.get(k) from getBean(name, type)
        if (invocation.arguments().size() != 1) return false;
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return MAP_HINTS.stream().anyMatch(hint::contains);
    }

    private static boolean hasNullGuardBefore(
            List<InvocationEvidence> invocations,
            String variable,
            InvocationEvidence getCall,
            InvocationEvidence dereference
    ) {
        int derefLine = dereference.location().startLine();
        int getLine = getCall.location().startLine();
        String mapScope = getCall.scope();

        for (var invocation : invocations) {
            int line = invocation.location().startLine();
            if (line > derefLine) continue;

            // Value-level guards: must appear after the get() and reference the result variable
            if (VALUE_GUARD_METHODS.contains(invocation.methodName()) && line >= getLine) {
                if (variable.equals(invocation.scope())) return true;
                if (invocation.arguments().stream().anyMatch(variable::equals)) return true;
            }

            // Receiver-level guards: containsKey / getOrDefault etc. on the same map scope,
            // can appear anywhere before the dereference (including before the get())
            if (RECEIVER_GUARD_METHODS.contains(invocation.methodName())
                    && !mapScope.isBlank()
                    && mapScope.equals(invocation.scope())) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Optional<InvocationEvidence> firstDereferenceAfter(
            List<InvocationEvidence> invocations,
            InvocationEvidence getCall,
            String variable
    ) {
        return invocations.stream()
                .filter(inv -> inv.location().startLine() > getCall.location().startLine())
                .filter(inv -> variable.equals(normalizedReceiver(inv.scope())))
                .findFirst();
    }

    private static String normalizedReceiver(String scope) {
        if (scope == null) return "";
        String normalized = scope.trim();
        while (normalized.endsWith("!!")) {
            normalized = normalized.substring(0, normalized.length() - 2).trim();
        }
        return normalized;
    }
}
