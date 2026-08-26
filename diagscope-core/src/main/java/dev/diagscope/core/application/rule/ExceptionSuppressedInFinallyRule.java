package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code throw} statements inside {@code finally} blocks when the protected ({@code try})
 * block can also throw. A throw in {@code finally} silently discards any exception propagating from
 * the {@code try} body — the original root cause becomes invisible to logs and monitoring.
 *
 * <p><b>Why it matters:</b> During an incident, engineers see the exception from {@code finally}
 * (often a secondary cleanup failure) rather than the root-cause exception. Java's
 * {@link Throwable#addSuppressed} exists precisely to carry both, but it is rarely used correctly
 * in handwritten code. The consequence is a misleading stack trace that wastes investigation time.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>The method has at least one {@code throw} statement inside a {@code finally} block
 *       (captured by the parser as {@link MethodModel#throwsInFinally()}).</li>
 *   <li>The protected block can also throw — conservative heuristic: the method has any
 *       invocation that is <em>not</em> inside the same {@code finally} block, or any
 *       {@code CatchEvidence} (meaning there is a {@code try-catch} protecting the body).</li>
 * </ol>
 *
 * <p>Confidence is HIGH when condition (2) is met, MEDIUM otherwise (the throw-in-finally may be
 * in a finally that guards a trivial body with no throwing potential).</p>
 */
public final class ExceptionSuppressedInFinallyRule implements ProjectRule {

    public static final String ID = "EXCEPTION_SUPPRESSED_IN_FINALLY";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        List<SourceLocation> throwsInFinally = method.throwsInFinally();
        if (throwsInFinally.isEmpty()) return;

        // Confirm the protected block has something that can throw:
        // any catch clause means there is a try block; any non-finally invocation also qualifies.
        boolean hasProtectedBody = !method.catches().isEmpty()
                || method.invocations().stream().anyMatch(inv -> !inv.insideFinally());

        Confidence confidence = hasProtectedBody ? Confidence.HIGH : Confidence.MEDIUM;

        for (SourceLocation throwLocation : throwsInFinally) {
            findings.add(new Finding(
                    ID, Severity.ERROR, confidence, throwLocation,
                    "Method '" + method.id().displayName()
                            + "' throws from a 'finally' block. If the 'try' body also throws, the"
                            + " original exception is silently discarded and replaced by this one,"
                            + " hiding the real root cause from logs and monitoring.",
                    "Use 'Throwable.addSuppressed()' to attach the original exception to the"
                            + " finally exception, or rethrow the original and log the cleanup"
                            + " failure separately. Prefer try-with-resources for resources that"
                            + " must be closed, which handles suppression automatically.",
                    List.of(),
                    Map.of("method", method.id().displayName())));
        }
    }
}
