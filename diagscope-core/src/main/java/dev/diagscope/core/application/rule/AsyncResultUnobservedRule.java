package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reports asynchronous work whose result — and therefore its failure — is never observed. */
public final class AsyncResultUnobservedRule implements DiagnosticRule {
    public static final String ID = "ASYNC_RESULT_UNOBSERVED";

    private static final Set<String> ASYNC_STARTERS =
            Set.of("supplyAsync", "runAsync", "submit", "schedule", "scheduleAtFixedRate",
                    "scheduleWithFixedDelay", "invokeAsync", "sendAsync", "callAsync");

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
                if (!ASYNC_STARTERS.contains(invocation.methodName())) continue;
                if (invocation.resultUsage() != InvocationResultUsage.IGNORED) continue;
                if (!looksAsync(RuleSupport.hintText(invocation)
                        + ' ' + invocation.methodName())) continue;
                // A method called schedule that schedules is a thin wrapper: its caller owns the task and its failure.
                if (method.id().name().equals(invocation.methodName())) continue;
                // A task that catches its own failure has observed it: the future would only ever hold what escaped.
                if (handlesItsOwnFailure(method, invocation)) continue;
                var instrumentation = DiagnosticSignals.instrumentationAnnotation(method);
                var confidence = Confidence.min(
                        instrumentation.isPresent() ? Confidence.LOW : Confidence.MEDIUM,
                        flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        "Asynchronous result is discarded, so its failure is never observed.",
                        "Keep the returned future: chain whenComplete/exceptionally, await it, or"
                                + " return it so the failure reaches a handler.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(), "call", invocation.methodName(),
                                "instrumentation", instrumentation.map(name -> '@' + name).orElse("none"))
                ));
            }
        }
        return List.copyOf(findings);
    }

    /** True when a catch of Exception, Throwable or RuntimeException sits inside the call, that is in the submitted task. */
    private static boolean handlesItsOwnFailure(
            dev.diagscope.core.domain.MethodModel method, dev.diagscope.core.domain.InvocationEvidence invocation) {
        var call = invocation.location();
        return method.catches().stream().anyMatch(handler -> {
            String type = handler.exceptionType().substring(handler.exceptionType().lastIndexOf('.') + 1);
            var at = handler.location();
            return at.file().equals(call.file()) && at.startLine() >= call.startLine() && at.startLine() <= call.endLine()
                    && (type.equals("Exception") || type.equals("Throwable") || type.equals("RuntimeException"));
        });
    }

    private static boolean looksAsync(String hint) {
        String normalized = hint.toLowerCase(Locale.ROOT);
        return normalized.contains("executor") || normalized.contains("completablefuture")
                || normalized.contains("async") || normalized.contains("pool")
                || normalized.contains("scheduler");
    }
}
