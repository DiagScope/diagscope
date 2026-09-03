package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports persistence writes inside a transaction explicitly declared as {@code readOnly = true}.
 */
public final class ReadOnlyTransactionWriteRule implements DiagnosticRule {
    public static final String ID = "READONLY_TRANSACTION_WRITE";

    private static final Set<String> WRITE_METHODS = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
            "delete", "deleteAll", "deleteById", "deleteAllById", "deleteInBatch",
            "deleteAllInBatch", "deleteAllByIdInBatch",
            "persist", "merge", "remove", "flush",
            "executeUpdate", "executeLargeUpdate", "batchUpdate", "update");

    private static final Pattern WRITE_RECEIVER = Pattern.compile(
            "(?i).*(repository|dao|entitymanager|session|jparepository|crudrepository"
                    + "|jpaoperations|persistencecontext|jdbctemplate|namedparameterjdbctemplate"
                    + "|r2dbcclient).*");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            if (!isReadOnlyTransactional(method)) continue;

            for (var invocation : method.invocations()) {
                if (!isPersistenceWrite(invocation)) continue;

                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                String receiver = invocation.scope().isBlank()
                        ? invocation.receiverType() : invocation.scope();
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        "@Transactional(readOnly = true) method performs a persistence write.",
                        "Move the write to a read-write @Transactional method, or remove readOnly = true"
                                + " only after confirming the method is intentionally mutating state.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "writeOperation", invocation.methodName(),
                                "receiver", receiver
                        )));
                break;
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isReadOnlyTransactional(MethodModel method) {
        if (!DiagnosticSignals.hasAnnotation(method, "Transactional")) return false;
        return method.annotationAttributes().entrySet().stream()
                .filter(entry -> annotationMatches(entry.getKey(), "Transactional"))
                .map(entry -> entry.getValue().get("readOnly"))
                .anyMatch(value -> value != null && "true".equalsIgnoreCase(value.trim()))
                || method.annotations().stream().anyMatch(ReadOnlyTransactionWriteRule::containsReadOnlyTrue);
    }

    private static boolean annotationMatches(String annotation, String simpleName) {
        String normalized = annotation.startsWith("@") ? annotation.substring(1) : annotation;
        int parenthesis = normalized.indexOf('(');
        if (parenthesis >= 0) normalized = normalized.substring(0, parenthesis);
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0) normalized = normalized.substring(dot + 1);
        return normalized.trim().equalsIgnoreCase(simpleName);
    }

    private static boolean containsReadOnlyTrue(String annotation) {
        String normalized = annotation.toLowerCase(Locale.ROOT).replace(" ", "");
        return normalized.contains("readonly=true");
    }

    private static boolean isPersistenceWrite(InvocationEvidence invocation) {
        if (!WRITE_METHODS.contains(invocation.methodName())) return false;
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return WRITE_RECEIVER.matcher(hint).matches()
                || hint.contains("repo") || hint.contains("entitymanager") || hint.contains("jdbc");
    }
}
