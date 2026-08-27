package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code @Scheduled} methods that declare a non-void return type.
 *
 * <p><b>Why it matters:</b> Spring's task scheduler invokes {@code @Scheduled} methods
 * reflectively and discards the return value without any warning. A developer who adds a
 * return type expecting the result to be used (for logging, chaining, or storage) will
 * find the value silently dropped every time the task runs. This is a correctness bug
 * disguised as a compilation success.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods annotated with {@code @Scheduled}.</li>
 *   <li>Check if the declared return type is anything other than {@code void} (Java) or
 *       {@code Unit} (Kotlin).</li>
 *   <li>Emit WARNING — the return value will never be observed by any caller.</li>
 * </ol>
 *
 * <p><b>Fix:</b> Change the return type to {@code void}. If a value must be produced,
 * publish it via {@code ApplicationEventPublisher}, write it to a cache or database,
 * or refactor into a non-scheduled method that the caller explicitly invokes.</p>
 */
public final class ScheduledNonVoidReturnRule implements ProjectRule {

    public static final String ID = "SCHEDULED_NON_VOID_RETURN";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        if (!method.annotations().contains("Scheduled")) return;

        String returnType = method.returnType();
        // void (Java) and Unit (Kotlin) are the only valid return types for @Scheduled
        if (returnType.isEmpty() || returnType.equals("void") || returnType.equals("Unit")) return;

        String methodDisplay = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "'" + methodDisplay + "' is @Scheduled but declares a return type of '"
                        + returnType + "'. Spring silently discards the return value"
                        + " — it is never observed by any caller or framework component.",
                "Change the return type to void. If the computed value is needed downstream,"
                        + " write it to a shared store (cache, database, or in-memory structure),"
                        + " publish it as an ApplicationEvent, or refactor the logic into a"
                        + " non-scheduled method that callers invoke explicitly.",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "declaredReturnType", returnType
                )));
    }
}
