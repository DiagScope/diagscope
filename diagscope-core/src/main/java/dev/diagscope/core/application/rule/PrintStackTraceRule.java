package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PrintStackTraceRule implements DiagnosticRule {
    public static final String ID = "PRINT_STACK_TRACE";
    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var invocation : method.invocations()) {
                if (!"printStackTrace".equals(invocation.methodName())) continue;
                String receiverType = invocation.receiverType().toLowerCase(java.util.Locale.ROOT);
                if (!receiverType.isBlank() && !receiverType.endsWith("exception")
                        && !receiverType.endsWith("throwable") && !receiverType.endsWith("error")) continue;
                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(ID, Severity.WARNING, confidence, invocation.location(),
                        "printStackTrace() is used in production code.",
                        "Use the configured application logger and preserve structured context.",
                        List.of(RelatedFlow.from(flow.entrypoint(), confidence)),
                        Map.of("method", method.id().displayName())));
            }
        }
        return List.copyOf(findings);
    }
}
