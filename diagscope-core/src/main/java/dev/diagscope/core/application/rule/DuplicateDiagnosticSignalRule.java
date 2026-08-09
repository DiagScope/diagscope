package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reports failures that are reported twice, or reported in two contradictory ways.
 *
 * <p>Logging a failure and rethrowing it means the same incident is written by this method and
 * again by whoever handles the exception. The duplicated stack traces inflate the error rate and
 * make it impossible to count how many failures actually happened. Worse, when the rethrown
 * exception drops the cause, the two records tell different stories about the same event.</p>
 */
public final class DuplicateDiagnosticSignalRule implements DiagnosticRule {
    public static final String ID = "DUPLICATE_DIAGNOSTIC_SIGNAL";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            for (var evidence : method.catches()) {
                if (!evidence.hasLog() || !evidence.hasThrow()) continue;
                boolean contradictory = !evidence.preservesCause();
                var confidence = Confidence.min(
                        contradictory ? Confidence.MEDIUM : Confidence.HIGH, flowMethod.confidence());
                var details = new LinkedHashMap<String, String>();
                details.put("method", method.id().displayName());
                details.put("exceptionType", evidence.exceptionType());
                details.put("signal", contradictory ? "contradictory" : "duplicate");
                details.put("preservesCause", String.valueOf(evidence.preservesCause()));
                findings.add(new Finding(
                        ID,
                        contradictory ? Severity.WARNING : Severity.INFO,
                        confidence,
                        evidence.location(),
                        contradictory
                                ? "The failure is logged here and rethrown without the original"
                                        + " cause, so the two records disagree about what happened."
                                : "The failure is logged here and rethrown, so the same incident is"
                                        + " reported twice.",
                        contradictory
                                ? "Rethrow with the original cause attached, and let a single layer"
                                        + " own the logging."
                                : "Log at the boundary that handles the failure, or rethrow without"
                                        + " logging — pick one owner for the record.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        details));
            }
        }
        return List.copyOf(findings);
    }
}
