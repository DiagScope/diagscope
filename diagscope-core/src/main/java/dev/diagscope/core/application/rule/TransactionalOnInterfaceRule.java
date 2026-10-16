package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code @Transactional} placed on interface methods instead of on the implementing class.
 *
 * <p>Since Spring Framework 5.0 the annotation <em>is</em> honoured on interface methods for both interface-based
 * and class-based (CGLIB) proxies, so this is no longer a guaranteed bug. The Spring reference still recommends
 * annotating concrete classes, because Java annotations are not inherited from interfaces: in AspectJ weaving mode,
 * and before Spring 5.0, the declaration is silently ignored and the code "works" until someone tests a rollback.
 * The rule therefore reports an INFO with LOW confidence: source alone cannot show the proxy mode or the Spring
 * version.</p>
 *
 * <p>Spring Data repository interfaces are skipped: the container builds their proxy from the interface itself.</p>
 */
public final class TransactionalOnInterfaceRule implements ProjectRule {

    public static final String ID = "TRANSACTIONAL_ON_INTERFACE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        if (!method.declaringTypeIsInterface()) return;
        if (!hasTransactionalAnnotation(method)) return;
        // Spring Data repositories are JDK proxies built from the interface, which honours its @Transactional.
        if (method.annotations().contains("Repository")) return;

        findings.add(new Finding(
                ID, Severity.INFO, Confidence.LOW, method.location(),
                "@Transactional is declared on interface method '" + method.id().displayName()
                        + "'. Spring honours it for proxy-based transactions since 5.0, but it is silently ignored"
                        + " in AspectJ mode and before Spring 5.0, so the transaction can appear to work until a"
                        + " rollback is tested.",
                "Move @Transactional to the implementing class method, as the Spring reference recommends."
                        + " Annotations are not inherited from interfaces, so the class is the declaration that"
                        + " every proxy mode recognises.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "declaringType", method.id().declaringType()
                )));
    }

    private static boolean hasTransactionalAnnotation(MethodModel method) {
        return method.annotations().stream().anyMatch(a ->
                a.equalsIgnoreCase("Transactional")
                        || a.equalsIgnoreCase("javax.transaction.Transactional")
                        || a.equalsIgnoreCase("jakarta.transaction.Transactional"));
    }
}
