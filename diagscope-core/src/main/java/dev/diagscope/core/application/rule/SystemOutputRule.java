package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code System.out}/{@code System.err} printing in place of a logger. A command-line program (a picocli or
 * Spring Shell {@code @Command} class, or a {@code main} method) prints because its output is the product, so those
 * are skipped.
 */
public final class SystemOutputRule implements DiagnosticRule {
    public static final String ID = "SYSTEM_OUTPUT";
    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            if (isCommandLineOutput(method)) continue;
            for (var invocation : method.invocations()) {
                boolean systemOutput = ("System.out".equals(invocation.scope()) || "System.err".equals(invocation.scope()))
                        && ("print".equals(invocation.methodName()) || "println".equals(invocation.methodName()));
                if (!systemOutput) continue;
                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(ID, Severity.WARNING, confidence, invocation.location(),
                        "System output is used instead of application logging.",
                        "Use the configured logger so the message participates in structured production telemetry.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName())));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isCommandLineOutput(MethodModel method) {
        return method.annotations().contains("Command") || "main".equals(method.id().name());
    }
}
