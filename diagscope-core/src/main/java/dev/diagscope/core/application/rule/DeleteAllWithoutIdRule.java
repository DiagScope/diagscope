package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports a no-argument {@code deleteAll()} on a repository.
 *
 * <p>Spring Data JPA's {@code deleteAll()} loads every entity into memory and deletes them one at a time, so
 * it grows with the table: an OutOfMemoryError or a very long transaction on a large one, plus one
 * {@code DELETE} per row. {@code deleteAllInBatch()} issues a single statement. Reactive repositories stream
 * and are not reported.</p>
 */
public final class DeleteAllWithoutIdRule implements ProjectRule {

    public static final String ID = "DELETE_ALL_WITHOUT_ID";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (!invocation.methodName().equals("deleteAll") || !invocation.arguments().isEmpty()) continue;
                String hint = RuleSupport.hint(invocation);
                if (hint.contains("reactive")) continue;
                String receiver = ExecutionContexts.simpleTypeName(invocation.receiverType());
                boolean typed = receiver.endsWith("Repository") || receiver.endsWith("Dao");
                if (!typed && !(hint.contains("repositor") || hint.contains("repo") || hint.contains("dao"))) continue;
                findings.add(new Finding(
                        ID, Severity.WARNING, typed ? Confidence.HIGH : Confidence.MEDIUM, invocation.location(),
                        "'" + method.id().displayName() + "' calls deleteAll() with no argument. Spring Data loads"
                                + " every entity into memory and deletes them one by one.",
                        "Use deleteAllInBatch() (one DELETE statement) when entity callbacks and cascades are not"
                                + " needed, or a @Modifying @Query delete. If cascades are required, delete in bounded pages.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "receiver", invocation.scope())));
            }
        }
        return List.copyOf(findings);
    }
}
