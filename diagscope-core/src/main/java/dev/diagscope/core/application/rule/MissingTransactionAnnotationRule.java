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
import java.util.regex.Pattern;

/**
 * Reports JPA / Spring Data write operations in methods that are not annotated with
 * {@code @Transactional} and whose class is not declared {@code @Transactional}.
 *
 * <p>Without a transaction boundary, each JPA operation runs in its own auto-commit
 * context. If any subsequent operation in the same business action fails, previously
 * persisted changes cannot be rolled back atomically. The result is partial writes
 * that leave data in an inconsistent state.</p>
 *
 * <p>The rule recognises repository save/delete operations, direct {@code EntityManager}
 * persist/merge/remove/flush calls, and Spring Data {@code save}, {@code saveAll},
 * {@code delete}, {@code deleteAll} invocations. Reads ({@code find}, {@code get},
 * {@code list}, etc.) are intentionally excluded.</p>
 */
public final class MissingTransactionAnnotationRule implements DiagnosticRule {

    public static final String ID = "MISSING_TRANSACTION_ANNOTATION";

    /** Annotations that provide a transaction boundary for the method or its class. */
    private static final Set<String> TRANSACTION_ANNOTATIONS = Set.of(
            "Transactional", "ReactiveTransactional", "TransactionAttribute");

    /** Write operations on Spring Data repositories or JPA EntityManager. */
    private static final Pattern WRITE_METHOD = Pattern.compile(
            "(?i)^(save|saveAll|saveAndFlush|saveAllAndFlush"
                    + "|delete|deleteAll|deleteById|deleteAllById|deleteInBatch|deleteAllInBatch"
                    + "|persist|merge|remove|flush|detach|refresh)$");

    /** Types that own JPA write operations (matched case-insensitively against simple class name). */
    private static final Pattern WRITE_RECEIVER = Pattern.compile(
            "(?i).*(repository|dao|entitymanager|jparepository|crudrepository"
                    + "|jpaoperations|persistencecontext).*");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        var byId = new java.util.HashMap<dev.diagscope.core.domain.MethodId, dev.diagscope.core.domain.FlowMethod>();
        flow.methods().forEach(reached -> byId.put(reached.method().id(), reached));
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            if (isTransactional(method)) continue;
            // A caller on the path may already hold the transaction this method joins.
            if (flowMethod.path().stream().map(byId::get)
                    .anyMatch(reached -> reached != null && isTransactional(reached.method()))) continue;

            var writes = method.invocations().stream().filter(MissingTransactionAnnotationRule::isWriteOperation).toList();
            // One Spring Data call is transactional on its own; the risk is several writes that can half-complete.
            long repositoryWrites = writes.stream().filter(write -> !isEntityManager(write)).count();
            boolean entityManagerWrite = writes.stream().anyMatch(MissingTransactionAnnotationRule::isEntityManager);
            if (!entityManagerWrite && repositoryWrites < 2) continue;

            for (var inv : writes) {

                Confidence confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                String receiver = inv.scope().isBlank() ? inv.receiverType() : inv.scope();
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, inv.location(),
                        "Write operation " + inv.methodName() + "() on '" + receiver
                                + "' is not inside a @Transactional boundary.",
                        "Annotate the calling method (or its class) with @Transactional."
                                + " Without a transaction, concurrent failures can leave data"
                                + " partially written and rollback is impossible.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "writeOperation", inv.methodName(),
                                "receiver", receiver)
                ));
                break; // one finding per method is enough to prompt the fix
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isTransactional(dev.diagscope.core.domain.MethodModel method) {
        return hasTransactionAnnotation(method.annotations())
                || method.proxy().proxiedAnnotations().contains("Transactional");
    }

    private static boolean isEntityManager(InvocationEvidence inv) {
        String hint = RuleSupport.hintText(inv).toLowerCase(Locale.ROOT);
        return hint.contains("entitymanager") || java.util.Arrays.asList(hint.split("[^a-z0-9]+")).contains("em");
    }

    private static boolean hasTransactionAnnotation(Set<String> annotations) {
        return annotations.stream().anyMatch(annotation -> {
            String normalized = annotation.startsWith("@") ? annotation.substring(1) : annotation;
            int paren = normalized.indexOf('(');
            if (paren >= 0) normalized = normalized.substring(0, paren);
            int dot = normalized.lastIndexOf('.');
            if (dot >= 0) normalized = normalized.substring(dot + 1);
            return TRANSACTION_ANNOTATIONS.contains(normalized.trim());
        });
    }

    private static boolean isWriteOperation(InvocationEvidence inv) {
        if (!WRITE_METHOD.matcher(inv.methodName()).matches()) return false;
        String hint = (RuleSupport.hintText(inv)).toLowerCase(Locale.ROOT);
        return WRITE_RECEIVER.matcher(hint).matches() || isEntityManager(inv);
    }
}
