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
 * Reports a value returned by {@code EntityManager.find(...)} that is dereferenced without a
 * syntax-visible null guard.
 */
public final class EntityManagerFindDereferenceRule implements DiagnosticRule {
    public static final String ID = "ENTITY_MANAGER_FIND_DEREFERENCE";

    private static final Set<String> GUARD_METHODS = Set.of(
            "requireNonNull", "requireNonNullElse", "checkNotNull", "notNull",
            "isNull", "nonNull", "ofNullable");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var findCall : method.invocations()) {
                if (!isEntityManagerFind(findCall)) continue;
                String variable = findCall.assignedTo();
                if (variable.isBlank()) continue;

                firstDereferenceAfter(method.invocations(), findCall, variable).ifPresent(dereference -> {
                    if (hasNullGuardBefore(method.invocations(), variable, findCall, dereference)) return;

                    var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                    findings.add(new Finding(
                            ID, Severity.WARNING, confidence, dereference.location(),
                            "EntityManager.find(...) result '" + variable
                                    + "' is dereferenced without a visible null guard.",
                            "Handle the missing row explicitly before dereferencing: check for null,"
                                    + " wrap with Optional.ofNullable(...), or use a repository method"
                                    + " that returns Optional.",
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

    private static boolean isEntityManagerFind(InvocationEvidence invocation) {
        if (!"find".equals(invocation.methodName())) return false;
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("entitymanager");
    }

    private static boolean hasNullGuardBefore(
            List<InvocationEvidence> invocations,
            String variable,
            InvocationEvidence findCall,
            InvocationEvidence dereference
    ) {
        for (var invocation : invocations) {
            int line = invocation.location().startLine();
            if (line < findCall.location().startLine() || line > dereference.location().startLine()) continue;
            if (!GUARD_METHODS.contains(invocation.methodName())) continue;
            if (invocation.arguments().stream().anyMatch(variable::equals)) return true;
        }
        return false;
    }

    private static java.util.Optional<InvocationEvidence> firstDereferenceAfter(
            List<InvocationEvidence> invocations,
            InvocationEvidence findCall,
            String variable
    ) {
        return invocations.stream()
                .filter(invocation -> invocation.location().startLine() >= findCall.location().startLine())
                .filter(invocation -> variable.equals(normalizedReceiver(invocation.scope())))
                .filter(invocation -> invocation != findCall)
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
