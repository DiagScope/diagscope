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
 * Reports write operations on repositories or persistence APIs called inside a loop body.
 *
 * <p>A {@code save()}, {@code delete()}, or equivalent call inside a loop issues one separate
 * database round-trip per iteration: N round-trips for N items. Spring Data provides
 * {@code saveAll()}, {@code deleteAll()}, and {@code deleteAllById()} precisely to collapse those
 * into one or two round-trips with a single SQL batch. The performance difference is 10–100× under
 * production data volumes.</p>
 *
 * <p>This rule is complementary to {@link NPlusOneQueryRiskRule}, which flags read operations.
 * Here the focus is on write operations, which carry the additional risk of N independent
 * transactions and N lock acquisitions when the caller has no enclosing transaction.</p>
 *
 * <p>The rule is suppressed when the call targets an explicitly batching method
 * ({@code saveAll}, {@code deleteAll}, {@code deleteAllById}) — those are the recommended
 * replacements and would be misleading to flag.</p>
 */
public final class BulkOperationInLoopRule implements DiagnosticRule {
    public static final String ID = "BULK_OPERATION_IN_LOOP";

    /** Write methods that have a batch alternative outside a loop. */
    private static final Set<String> WRITE_METHODS = Set.of(
            "save", "saveAndFlush",
            "delete", "deleteById",
            "update", "upsert", "insert",
            "persist", "merge", "remove", "flush");

    /** Batch methods that ARE the recommended fix — never flag these. */
    private static final Set<String> BATCH_METHODS = Set.of(
            "saveAll", "saveAllAndFlush",
            "deleteAll", "deleteAllById", "deleteAllByIdInBatch", "deleteAllInBatch",
            "persistAll", "mergeAll");

    /** Types whose name strongly suggests a persistence boundary. */
    private static final Pattern REPO_TYPE = Pattern.compile(
            "(?i).*(repository|dao|jparepository|crudrepository|reactivecrudrepository"
                    + "|mongorepository|r2dbcrepository|entitymanager|jdbctemplate"
                    + "|namedparameterjdbctemplate|mongotemplate|r2dbcclient).*");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var invocation : method.invocations()) {
                if (!invocation.insideLoop()) continue;
                if (!isWriteCall(invocation)) continue;
                if (BATCH_METHODS.contains(invocation.methodName())) continue;

                boolean unambiguous = !invocation.receiverType().isBlank()
                        && REPO_TYPE.matcher(invocation.receiverType()).matches();
                var confidence = Confidence.min(
                        unambiguous ? Confidence.HIGH : Confidence.MEDIUM,
                        flowMethod.confidence());

                String batchAlternative = batchAlternative(invocation.methodName());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Repository write call inside a loop: each iteration is a separate"
                                + " database round-trip and transaction.",
                        "Collect items first, then call " + batchAlternative + " once outside"
                                + " the loop to persist them in a single batch operation.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "call", invocation.scope() + '.' + invocation.methodName(),
                                "receiverType", invocation.receiverType(),
                                "batchAlternative", batchAlternative
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isWriteCall(InvocationEvidence invocation) {
        if (!WRITE_METHODS.contains(invocation.methodName())) return false;
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        // Require a persistence-looking receiver so we do not flag close/write on streams.
        return REPO_TYPE.matcher(hint).matches()
                || hint.contains("entitymanager") || hint.contains("session");
    }

    private static String batchAlternative(String method) {
        return switch (method) {
            case "save", "saveAndFlush" -> "saveAll()";
            case "delete", "deleteById" -> "deleteAllById()";
            case "persist", "merge" -> "saveAll() or a batch JPQL/native query";
            default -> "a batch variant of " + method + "()";
        };
    }
}
