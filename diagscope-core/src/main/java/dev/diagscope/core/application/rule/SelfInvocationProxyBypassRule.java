package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports internal calls that bypass the Spring proxy.
 *
 * <p>When a bean calls one of its own methods through {@code this}, the call never leaves the
 * target object, so the proxy that carries {@code @Transactional}, {@code @Async}, retries, caching
 * or aspect advice is not involved. The behaviour the code advertises silently does not happen on
 * that path, and neither the caller nor the callee shows anything unusual at the call site.</p>
 */
public final class SelfInvocationProxyBypassRule implements DiagnosticRule {
    public static final String ID = "AOP_SELF_INVOCATION";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        Map<MethodId, FlowMethod> reached = index(flow);
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel caller = flowMethod.method();
            for (var call : caller.calls()) {
                if (call.resolutionReason() != ResolutionReason.SAME_CLASS || call.target().isEmpty()) {
                    continue;
                }
                if (!call.scope().isBlank() && !"this".equals(call.scope())) {
                    continue;
                }
                MethodId targetId = call.target().orElseThrow();
                if (targetId.equals(caller.id())) {
                    continue;
                }
                FlowMethod target = reached.get(targetId);
                if (target == null) {
                    continue;
                }
                var proxy = target.method().proxy();
                if (!proxy.proxied()) {
                    continue;
                }

                Confidence confidence = Confidence.min(confidenceFor(proxy), flowMethod.confidence());
                var evidence = new LinkedHashMap<String, String>();
                evidence.put("method", caller.id().displayName());
                evidence.put("bypassedMethod", targetId.displayName());
                evidence.put("bypassedInstrumentation", proxy.concernSummary());
                evidence.put("callExpression", (call.scope().isBlank() ? "" : call.scope() + '.')
                        + call.methodName() + "()");
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, call.location(),
                        "Internal call to " + targetId.name()
                                + "() bypasses the Spring proxy, so " + proxy.concernSummary()
                                + " does not apply on this path.",
                        "Call the method through an injected reference to the bean (or move it to a"
                                + " collaborator) so the proxy stays in the call path.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        evidence));
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Explicit annotations on the callee are visible in source and unambiguous, so the bypass is
     * reported with high confidence. A pointcut match is an approximation of what the AspectJ
     * matcher would decide at runtime, so it is reported one confidence level lower.
     */
    private static Confidence confidenceFor(dev.diagscope.core.domain.ProxyProfile proxy) {
        if (!proxy.proxiedAnnotations().isEmpty()) {
            return proxy.springManagedType() ? Confidence.HIGH : Confidence.MEDIUM;
        }
        return proxy.springManagedType() ? Confidence.MEDIUM : Confidence.LOW;
    }

    private static Map<MethodId, FlowMethod> index(Flow flow) {
        var index = new HashMap<MethodId, FlowMethod>(flow.methods().size());
        for (var flowMethod : flow.methods()) {
            index.merge(flowMethod.method().id(), flowMethod,
                    (left, right) -> left.depth() <= right.depth() ? left : right);
        }
        return index;
    }
}
