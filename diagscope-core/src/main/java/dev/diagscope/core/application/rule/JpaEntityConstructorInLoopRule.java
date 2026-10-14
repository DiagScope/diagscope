package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports an entity constructed inside a loop that also saves inside the loop.
 *
 * <p>Building one entity per iteration and calling {@code save}/{@code persist} for each issues one statement
 * (and often one flush) per row. Collecting the entities and calling {@code saveAll} once lets the persistence
 * layer batch the inserts. This is the construction-side view of the pattern {@code BULK_OPERATION_IN_LOOP}
 * reports on the save call, so the two usually appear together on the same loop.</p>
 *
 * <p>An entity type is recognised from an {@code @Entity} annotation on a type declared in the project; an
 * entity with no methods is not seen.</p>
 */
public final class JpaEntityConstructorInLoopRule implements ProjectRule {

    public static final String ID = "JPA_ENTITY_CONSTRUCTOR_IN_LOOP";

    private static final Set<String> PERSIST_CALLS = Set.of("save", "persist", "saveAndFlush");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var entities = new java.util.HashSet<String>();
        for (MethodModel method : project.methods().values()) {
            if (DiagnosticSignals.hasAnnotation(method, "Entity")) entities.add(RuleSupport.simpleName(method.id().declaringType()));
        }
        var findings = new ArrayList<Finding>();
        if (entities.isEmpty()) return List.of();
        for (MethodModel method : project.methods().values()) {
            boolean persistsInLoop = method.invocations().stream()
                    .anyMatch(call -> call.insideLoop() && PERSIST_CALLS.contains(call.methodName()));
            if (!persistsInLoop) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (!invocation.insideLoop() || !entities.contains(invocation.methodName())) continue;
                if (!invocation.scope().isBlank() || !invocation.receiverType().isBlank()) continue;
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.MEDIUM, invocation.location(),
                        "'" + method.id().displayName() + "' creates a " + invocation.methodName() + " entity and saves it inside the"
                                + " same loop, producing one INSERT per row.",
                        "Collect the new entities in a list inside the loop and call saveAll(list) once after it, with"
                                + " hibernate.jdbc.batch_size set so the inserts are batched.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "entity", invocation.methodName())));
            }
        }
        return List.copyOf(findings);
    }
}
