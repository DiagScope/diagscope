package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reports failure logs and thrown messages that carry no identifying context. */
public final class GenericExceptionMessageRule implements DiagnosticRule {
    public static final String ID = "GENERIC_EXCEPTION_MESSAGE";

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
                if (!DiagnosticSignals.isFailureLogCall(invocation)) continue;
                if (invocation.arguments().isEmpty()) continue;
                String first = invocation.arguments().getFirst();
                String literal = DiagnosticSignals.firstStringLiteral(first);
                if (literal.isBlank() || !DiagnosticSignals.isGenericMessage(literal)) continue;
                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Failure message carries no context: \"" + literal + "\".",
                        "Name the operation and include the identifiers needed to find the affected"
                                + " request, plus the exception itself.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(), "message", literal)
                ));
            }
        }
        return List.copyOf(findings);
    }
}
