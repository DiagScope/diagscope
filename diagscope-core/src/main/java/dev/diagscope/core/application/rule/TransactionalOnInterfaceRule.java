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
 * Reports {@code @Transactional} annotations placed on interface methods.
 *
 * <p>Spring's default proxy strategy since Spring Boot 2 is <em>CGLIB subclass proxying</em>.
 * CGLIB creates a subclass of the concrete bean class — it does not read annotations from
 * implemented interfaces. An interface method annotated with {@code @Transactional} is
 * therefore silently ignored: no transaction is opened, no rollback occurs on exception,
 * and the method runs without a persistence context boundary. Developers discover this only
 * when a rollback fails to happen in production.</p>
 *
 * <p>Even under JDK dynamic proxy mode ({@code proxyTargetClass=false}), relying on
 * interface-level transactions makes the application sensitive to proxy mode changes — a
 * configuration difference between environments that should be transparent to business logic.</p>
 *
 * <p>Suppression: a method is not flagged when its declaring type is not an interface. The
 * rule has no false negatives for the CGLIB case.</p>
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

        findings.add(new Finding(
                ID, Severity.ERROR, Confidence.HIGH, method.location(),
                "@Transactional declared on interface method '" + method.id().displayName()
                        + "'. Under Spring's default CGLIB proxying, annotations on interfaces"
                        + " are ignored and no transaction boundary is opened.",
                "Move @Transactional to the implementing class method."
                        + " Interface-level transaction declarations are not picked up by CGLIB"
                        + " proxies (the default since Spring Boot 2). The annotation belongs on"
                        + " the concrete implementation, not on the interface contract.",
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
