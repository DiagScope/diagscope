package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reports a catch block that throws a new exception without passing the caught one as its cause.
 *
 * <p>{@code throw new ServiceException("failed")} or {@code throw new ServiceException(e.getMessage())} replaces the
 * original failure with a new one that has no cause. The stack trace of the real error, where it was raised and
 * through which calls, is gone before it reaches any log. A handler that also logs is left to
 * {@code DUPLICATE_DIAGNOSTIC_SIGNAL}, which already reports that case.</p>
 */
public final class CauseLostOnRethrowRule implements DiagnosticRule {
    public static final String ID = "CAUSE_LOST_ON_RETHROW";

    /** Invalid input rethrown as a client error: the parse failure itself says little, and the input is the cause. */
    private static final Set<String> INPUT_FAILURES = Set.of("NumberFormatException", "DateTimeParseException");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var evidence : method.catches()) {
                if (!evidence.losesCauseOnRethrow() || evidence.hasLog() || isInputFailure(evidence.exceptionType())) {
                    continue;
                }
                // With e.getMessage() the text survives but not the stack trace; with nothing at all even the text is gone.
                Confidence ruleConfidence = evidence.preservesCause() ? Confidence.MEDIUM : Confidence.HIGH;
                Confidence confidence = Confidence.min(ruleConfidence, flowMethod.confidence());
                var details = new LinkedHashMap<String, String>();
                details.put("method", method.id().displayName());
                details.put("exceptionType", evidence.exceptionType());
                details.put("messageKept", String.valueOf(evidence.preservesCause()));
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, evidence.location(),
                        "A new exception is thrown here without the caught " + simpleName(evidence.exceptionType())
                                + " as its cause, so the original stack trace is lost.",
                        "Pass the caught exception as the cause: new X(message, e), or call initCause(e).",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        details));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isInputFailure(String exceptionType) {
        return INPUT_FAILURES.contains(simpleName(exceptionType));
    }

    private static String simpleName(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }
}
