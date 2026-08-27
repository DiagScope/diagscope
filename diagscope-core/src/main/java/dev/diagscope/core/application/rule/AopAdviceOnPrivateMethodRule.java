package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.MethodVisibility;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reports AOP advice annotations ({@code @Around}, {@code @Before}, {@code @After},
 * {@code @AfterReturning}, {@code @AfterThrowing}) placed on {@code private} methods.
 *
 * <p><b>Why it matters:</b> Spring AOP operates through a proxy that intercepts calls to the
 * advised bean. A proxy can only intercept methods it can override — i.e. at minimum
 * package-private visibility. A {@code private} method is invisible to the proxy; the advice
 * is registered in the application context but <em>never executes</em>. There is no startup
 * error, no runtime warning, and no indication in logs that the pointcut is silently dead.
 * This is the AOP equivalent of {@code ASYNC_ON_PRIVATE_METHOD}: an annotation that appears
 * active but does nothing.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Walk all methods in the project.</li>
 *   <li>Check if the method's visibility is {@code PRIVATE} (from {@code ProxyProfile}).</li>
 *   <li>Check if the method carries any of the five standard Spring AOP advice annotations.</li>
 *   <li>Emit ERROR — the advice will never execute regardless of pointcut expression.</li>
 * </ol>
 *
 * <p><b>Note:</b> Unlike most proxy-bypass rules this is implemented as a {@link ProjectRule}
 * rather than a flow-level rule, because AOP advice methods are invoked by the proxy
 * infrastructure — they do not appear as call-chain nodes in any flow derived from REST
 * endpoints, Kafka listeners, or scheduled tasks.</p>
 *
 * <p><b>Confidence:</b> HIGH when the declaring type is a Spring-managed bean; MEDIUM
 * otherwise (the annotation may originate from a framework with different proxy rules,
 * though the same constraint applies to all proxy-based AOP).</p>
 */
public final class AopAdviceOnPrivateMethodRule implements ProjectRule {

    public static final String ID = "AOP_ADVICE_ON_PRIVATE_METHOD";

    /** Standard Spring AOP advice annotations. */
    private static final Set<String> ADVICE_ANNOTATIONS = Set.of(
            "Around", "Before", "After", "AfterReturning", "AfterThrowing"
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        if (method.proxy().visibility() != MethodVisibility.PRIVATE) return;

        Set<String> matched = method.annotations().stream()
                .filter(ADVICE_ANNOTATIONS::contains)
                .collect(Collectors.toSet());
        if (matched.isEmpty()) return;

        String annotationList = matched.stream().sorted()
                .map(a -> "@" + a)
                .collect(Collectors.joining(", "));
        String methodDisplay = method.id().displayName();

        Confidence confidence = method.proxy().springManagedType()
                ? Confidence.HIGH : Confidence.MEDIUM;

        findings.add(new Finding(
                ID, Severity.ERROR, confidence, method.location(),
                annotationList + " on private method '" + methodDisplay
                        + "' — the advice is silently skipped. AOP proxies cannot"
                        + " intercept private methods; the pointcut registers successfully"
                        + " at startup but never matches at runtime.",
                "Change the method visibility to at least package-private (or public)"
                        + " so the proxy can override and intercept the method. If the"
                        + " logic must remain private, delegate from a public advice method"
                        + " to a private helper. Alternatively, configure AspectJ load-time"
                        + " weaving (LTW) which does support private methods — but this"
                        + " requires explicit LTW setup and is rarely appropriate for"
                        + " standard Spring Boot applications.",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "adviceAnnotations", annotationList,
                        "visibility", method.proxy().visibility().displayName()
                )));
    }
}
