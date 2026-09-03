package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports a {@code @Transactional(propagation = REQUIRES_NEW)} method that is called inside a
 * loop in a caller visible in the same flow.
 *
 * <p>Each loop iteration suspends the outer transaction, opens a brand-new one, commits it, and
 * resumes — creating N independent round-trips to the database coordinator. This pattern saturates
 * the connection pool under any meaningful batch size and causes lock-escalation issues that are
 * invisible in development (small data) and catastrophic in production (large batches).</p>
 *
 * <p><b>Detection strategy:</b> for each method in the flow that declares
 * {@code propagation = REQUIRES_NEW}, resolve its direct caller via the {@code path} field
 * (second-to-last element), then scan that caller's {@code invocations()} for a call with
 * {@code insideLoop = true} whose method name matches the REQUIRES_NEW method. Using the path
 * field (rather than adjacent-index arithmetic) correctly handles flows that contain multiple
 * methods at varying depths reachable from the same entrypoint.</p>
 *
 * <p><b>Known limitation:</b> matching is by method name only — two methods with the same name in
 * different classes may produce a false positive. Confidence is capped at MEDIUM for this reason.
 * The rule only fires when {@code REQUIRES_NEW} is explicitly declared; inherited or default
 * propagation is not flagged.</p>
 */
public final class RequiresNewInLoopRule implements DiagnosticRule {
    public static final String ID = "REQUIRES_NEW_IN_LOOP";

    private static final String REQUIRES_NEW = "REQUIRES_NEW";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();

        // Build an index of all FlowMethods by their MethodId for direct-caller lookup
        var methodIndex = new HashMap<MethodId, FlowMethod>(flow.methods().size() * 2);
        for (var fm : flow.methods()) {
            methodIndex.putIfAbsent(fm.method().id(), fm);
        }

        for (var current : flow.methods()) {
            if (!isRequiresNew(current)) continue;

            // The direct caller is the second-to-last entry in this method's path
            var path = current.path();
            if (path.size() < 2) continue;
            MethodId callerId = path.get(path.size() - 2);
            FlowMethod caller = methodIndex.get(callerId);
            if (caller == null) continue;

            String targetName = current.method().id().name();

            for (var invocation : caller.method().invocations()) {
                if (!invocation.insideLoop()) continue;
                if (!targetName.equals(invocation.methodName())) continue;

                var confidence = Confidence.min(Confidence.MEDIUM, caller.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        "REQUIRES_NEW transactional method '" + targetName
                                + "' is called inside a loop — one new DB transaction per iteration.",
                        "Move the loop body inside the REQUIRES_NEW method so one transaction covers"
                                + " the batch, or restructure to use a bulk-save API. Each iteration"
                                + " suspends the outer transaction, opens a new connection, commits,"
                                + " and releases — scaling linearly with loop size.",
                        List.of(
                                RelatedFlow.from(flow.entrypoint(), caller, confidence),
                                RelatedFlow.from(flow.entrypoint(), current, confidence)),
                        Map.of(
                                "callerMethod", caller.method().id().displayName(),
                                "requiresNewMethod", current.method().id().displayName()
                        )));
                break; // one finding per REQUIRES_NEW method per caller path
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isRequiresNew(FlowMethod flowMethod) {
        return DiagnosticSignals.hasAnnotation(flowMethod.method(), "Transactional")
                && flowMethod.method()
                        .normalizedAnnotationAttribute("Transactional", "propagation")
                        .map(REQUIRES_NEW::equals)
                        .orElse(false);
    }
}
