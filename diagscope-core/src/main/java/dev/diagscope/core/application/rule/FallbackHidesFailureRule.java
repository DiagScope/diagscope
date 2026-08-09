package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reports fallback and recovery methods that return a default without recording the failure. */
public final class FallbackHidesFailureRule implements DiagnosticRule {
    public static final String ID = "FALLBACK_HIDES_FAILURE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            if (!isFallback(method)) continue;
            if (DiagnosticSignals.isInstrumented(method)) continue;
            if (DiagnosticSignals.logsAnything(method) || DiagnosticSignals.recordsMetric(method)) {
                continue;
            }
            var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, method.location(),
                    "Fallback returns a default value without recording the failure it replaces.",
                    "Log the recovered exception or increment a fallback metric, otherwise degraded"
                            + " responses are indistinguishable from healthy ones.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    Map.of("method", method.id().displayName())
            ));
        }
        return List.copyOf(findings);
    }

    private static boolean isFallback(MethodModel method) {
        if (DiagnosticSignals.hasAnnotation(method, "Recover")) return true;
        String name = method.id().displayName().toLowerCase(Locale.ROOT);
        int separator = name.lastIndexOf('#');
        String simpleName = separator < 0 ? name : name.substring(separator + 1);
        return simpleName.startsWith("fallback") || simpleName.contains("fallback");
    }
}
