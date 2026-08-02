package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SystemOutputRule implements DiagnosticRule {
    public static final String ID = "SYSTEM_OUTPUT";
    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var invocation : method.invocations()) {
                boolean systemOutput = ("System.out".equals(invocation.scope()) || "System.err".equals(invocation.scope()))
                        && ("print".equals(invocation.methodName()) || "println".equals(invocation.methodName()));
                if (!systemOutput) continue;
                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(ID, Severity.WARNING, confidence, invocation.location(),
                        "System output is used instead of application logging.",
                        "Use the configured logger so the message participates in structured production telemetry.",
                        List.of(RelatedFlow.from(flow.entrypoint(), confidence)),
                        Map.of("method", method.id().displayName())));
            }
        }
        return List.copyOf(findings);
    }
}
