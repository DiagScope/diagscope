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
 * Reports proxy-dependent instrumentation declared on a class that does not look Spring managed.
 *
 * <p>Advice only runs when the instance the caller holds came from the container. On a class with
 * no stereotype annotation and no {@code @Bean} factory in sight, the annotation is likely to be
 * decoration on an object created with {@code new}. Bean registration can also happen in ways this
 * scan cannot see — component scanning of imported modules, XML, registrars — so this rule never
 * claims certainty: it caps confidence at medium and drops to low as soon as a factory method that
 * returns the type is visible.</p>
 */
public final class UnmanagedAdviceTargetRule implements DiagnosticRule {
    public static final String ID = "AOP_UNMANAGED_ADVICE_TARGET";

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
            if (!proxy.proxied() || proxy.springManagedType()) {
                continue;
            }

            Confidence confidence = Confidence.min(
                    proxy.beanFactoryCandidate() ? Confidence.LOW : Confidence.MEDIUM,
                    flowMethod.confidence());
            var evidence = new LinkedHashMap<String, String>();
            evidence.put("method", method.id().displayName());
            evidence.put("declaringType", method.declaringType());
            evidence.put("instrumentation", proxy.concernSummary());
            evidence.put("beanFactoryVisible", Boolean.toString(proxy.beanFactoryCandidate()));
            findings.add(new Finding(
                    ID, Severity.INFO, confidence, method.location(),
                    "Proxy-based instrumentation (" + proxy.concernSummary()
                            + ") is declared on a class with no visible Spring stereotype, so it only"
                            + " applies when the instance comes from the container.",
                    "Confirm the class is registered as a bean, or attach the instrumentation to a"
                            + " managed collaborator instead.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    evidence));
        }
        return List.copyOf(findings);
    }
}
