package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.ProxyProfile;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reports methods that depend on a proxy the runtime cannot build for them.
 *
 * <p>A Spring proxy can only intercept public, non-static, non-final methods of a non-final class.
 * Advice attached to anything else is accepted at compile time and silently never runs, which is
 * exactly the kind of invisible instrumentation gap DiagScope looks for.</p>
 */
public final class NonProxyableAdviceTargetRule implements DiagnosticRule {
    public static final String ID = "AOP_ADVICE_NOT_APPLIED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            ProxyProfile proxy = method.proxy();
            if (!proxy.proxied() || proxy.interceptable()) {
                continue;
            }

            Confidence confidence = Confidence.min(confidenceFor(proxy), flowMethod.confidence());
            var evidence = new LinkedHashMap<String, String>();
            evidence.put("method", method.id().displayName());
            evidence.put("instrumentation", proxy.concernSummary());
            evidence.put("blocker", proxy.nonInterceptableReason());
            evidence.put("visibility", proxy.visibility().displayName());
            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, method.location(),
                    "Proxy-based instrumentation (" + proxy.concernSummary()
                            + ") cannot be applied to this " + proxy.nonInterceptableReason() + ".",
                    "Make the advised method public and non-final on a non-final class, or move the"
                            + " behaviour to a method the proxy can intercept.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    evidence));
        }
        return List.copyOf(findings);
    }

    /**
     * Private and static methods are never intercepted by a Spring proxy, whichever proxy mode is
     * configured. Final methods and final classes only break CGLIB proxies, and a project may be
     * weaving at load time instead, so those are reported one level lower.
     */
    private static Confidence confidenceFor(ProxyProfile proxy) {
        boolean structural = !proxy.visibility().proxyable() || proxy.staticMethod();
        Confidence base = structural ? Confidence.HIGH : Confidence.MEDIUM;
        if (!proxy.springManagedType() && !proxy.beanFactoryCandidate()) {
            return Confidence.min(base, Confidence.MEDIUM);
        }
        return base;
    }
}
