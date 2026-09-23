package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reports exception and error constructors called without any message argument.
 *
 * <p>{@code throw new RuntimeException()} or {@code new DataIntegrityException()} without a
 * message string produces an exception whose {@code getMessage()} returns {@code null}. Log
 * aggregators display only the class name with no additional context; root-cause investigation
 * requires a full stack trace just to understand what went wrong.</p>
 *
 * <p><b>Why it matters:</b> In production, a {@code RuntimeException: null} in a log with no
 * message forces engineers to correlate the stack trace with the source file to understand even
 * the most basic fact — what operation failed and why. A single descriptive message (e.g.
 * {@code "Payment declined for orderId=" + orderId}) turns a 20-minute investigation into a
 * 20-second search. The cost of missing messages compounds with service count: in a distributed
 * system where a single user request traverses 5 services, a context-free exception in any one
 * of them makes the entire trace unreadable.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Scan all invocations in the flow.</li>
 *   <li>Identify constructor-style invocations whose method name ends with {@code Exception}
 *       or {@code Error} (e.g. {@code RuntimeException}, {@code IllegalStateError},
 *       {@code DataIntegrityViolationException}).</li>
 *   <li>Report those with an empty argument list — a no-arg constructor with no message.</li>
 *   <li>Known safe no-arg exceptions: {@code AssertionError} (used in tests/unreachable paths)
 *       is not suppressed but its confidence is capped at LOW.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> This rule matches by naming convention only — a class whose name
 * ends with {@code Exception} or {@code Error} but does not extend {@code Throwable} may produce
 * a false positive. Confidence is always LOW to reflect this.</p>
 */
public final class ExceptionConstructorWithoutMessageRule implements DiagnosticRule {

    public static final String ID = "EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE";

    private static final Pattern EXCEPTION_NAME = Pattern.compile(
            "(?i).*(Exception|Error)$");

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
                if (!isExceptionConstructor(invocation)) continue;
                if (!invocation.arguments().isEmpty()) continue;

                var confidence = Confidence.min(Confidence.LOW, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.INFO, confidence, invocation.location(),
                        "Exception constructor '" + invocation.methodName()
                                + "' called with no message argument. The resulting exception"
                                + " has getMessage() == null and provides no context for diagnosis.",
                        "Pass a descriptive message that includes the relevant domain values:"
                                + " new " + invocation.methodName() + "(\"Operation failed for id: \" + id)."
                                + " The message should describe what the operation was, what"
                                + " expected value was missing or violated, and ideally which"
                                + " identifier was involved. Avoid generic messages such as"
                                + " \"Unexpected error\" — they add no value over the class name alone.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "exceptionType", invocation.methodName()
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isExceptionConstructor(InvocationEvidence invocation) {
        return EXCEPTION_NAME.matcher(invocation.methodName()).matches();
    }
}
