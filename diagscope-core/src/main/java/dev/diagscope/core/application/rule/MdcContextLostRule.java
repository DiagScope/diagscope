package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reports places where the logging context (MDC) is lost.
 *
 * <p>The MDC lives in a thread local. When a method fills it and then hands the work to another
 * thread without copying the context, the logs produced by the asynchronous task carry no
 * correlation id, so an incident cannot be followed across the boundary. The mirror problem is an
 * MDC key written on a pooled thread and never removed: the value leaks into the next unrelated
 * request and the logs point at the wrong transaction.</p>
 */
public final class MdcContextLostRule implements DiagnosticRule {
    public static final String ID = "MDC_CONTEXT_LOST";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            List<InvocationEvidence> writes = method.invocations().stream()
                    .filter(DiagnosticSignals::writesMdc)
                    .toList();
            if (writes.isEmpty()) continue;

            boolean cleared = method.invocations().stream().anyMatch(DiagnosticSignals::clearsMdc)
                    || writes.stream().allMatch(write -> "putCloseable".equals(write.methodName()));
            for (var dispatch : method.invocations()) {
                if (!DiagnosticSignals.isAsyncDispatch(dispatch)) continue;
                if (dispatch.arguments().stream().anyMatch(DiagnosticSignals::propagatesMdc)) continue;
                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                var evidence = new LinkedHashMap<String, String>();
                evidence.put("method", method.id().displayName());
                evidence.put("contextKeySetAtLine", String.valueOf(writes.getFirst().location().startLine()));
                evidence.put("dispatch", dispatch.methodName());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, dispatch.location(),
                        "Work is handed to another thread without copying the MDC, so the logging"
                                + " context set in this method does not reach it.",
                        "Copy the context explicitly (MDC.getCopyOfContextMap/setContextMap) or"
                                + " install a TaskDecorator so the correlation id survives the hop.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        evidence));
            }

            if (!cleared) {
                var first = writes.getFirst();
                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                var evidence = new LinkedHashMap<String, String>();
                evidence.put("method", method.id().displayName());
                evidence.put("contextKey", first.arguments().isEmpty() ? "" : first.arguments().getFirst());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, first.location(),
                        "MDC key is written but never removed, so it leaks into the next task that"
                                + " reuses this thread.",
                        "Remove the key in a finally block (or use MDC.putCloseable) so the context"
                                + " belongs to a single unit of work.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        evidence));
            }
        }
        return List.copyOf(findings);
    }
}
