package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports a catch block that turns an exception into a normal return value and leaves no trace of it. A handler that
 * uses the exception (prints it, wraps it, passes it on), logs, rethrows or returns a stable failure code is not
 * reported, and neither is a failed parse of a number or a date: the input is invalid, nothing is being hidden.
 */
public final class SilentFailureConversionRule implements DiagnosticRule {
    public static final String ID = "SILENT_FAILURE_CONVERSION";

    private static final Set<String> PARSE_FAILURES = Set.of(
            "NumberFormatException", "DateTimeParseException");

    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var evidence : method.catches()) {
                if (!evidence.hasReturn() || evidence.hasLog() || evidence.hasThrow()
                        || evidence.preservesCause() || evidence.hasStableFailureCode()
                        || isParseFailure(evidence.exceptionType())) continue;
                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, evidence.location(),
                        "Exception is converted to a normal return value without preserving diagnostic evidence.",
                        "Preserve the cause, emit a diagnostic signal, or return a result containing a stable failure code.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("returnedExpression", evidence.returnedExpression(), "method", method.id().displayName())
                ));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isParseFailure(String exceptionType) {
        return PARSE_FAILURES.contains(exceptionType.substring(exceptionType.lastIndexOf('.') + 1));
    }
}
