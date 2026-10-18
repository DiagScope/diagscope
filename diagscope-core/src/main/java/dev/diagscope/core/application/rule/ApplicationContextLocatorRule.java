package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code applicationContext.getBean(...)} calls inside Spring-managed beans outside
 * {@code @Bean} factory methods.
 *
 * <p><b>Why it matters:</b> {@code BeanFactory}'s own Javadoc says it is "generally better to rely on
 * Dependency Injection" than on lookup. A class that calls {@code applicationContext.getBean(OrderService.class)}
 * hides its dependency from its constructor, so callers and tests cannot see it and need a whole context to
 * supply it. The rule does not claim more than that: a runtime lookup is not checked at startup, but it is also
 * the way to break a circular dependency, so this is a design finding, not a defect.</p>
 *
 * <p>Constructor injection or {@code @Autowired} field injection makes all dependencies
 * explicit, verifiable at startup, and mockable in tests.</p>
 */
public final class ApplicationContextLocatorRule implements ProjectRule {

    public static final String ID = "APPLICATION_CONTEXT_LOCATOR";

    private static final Set<String> SPRING_STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController",
            "Configuration", "ControllerAdvice", "RestControllerAdvice"
    );

    private static final Set<String> SUPPRESSED_ANNOTATIONS = Set.of(
            "Bean", "TestConfiguration", "BeforeEach", "BeforeAll", "Before", "Test"
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
        if (method.annotations().stream().noneMatch(SPRING_STEREOTYPES::contains)) return;
        if (method.annotations().stream().anyMatch(SUPPRESSED_ANNOTATIONS::contains)) return;
        // A main method bootstraps the application: there is no constructor to inject into yet.
        if ("main".equals(method.id().name()) && method.id().parameterTypes().size() == 1) return;

        for (InvocationEvidence inv : method.invocations()) {
            if (!"getBean".equals(inv.methodName()) || !looksLikeContext(inv)) continue;

            String display = method.id().displayName();
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.HIGH, inv.location(),
                    "'" + display + "' calls getBean() — the Service Locator pattern. The dependency is"
                            + " hidden from the constructor, so callers and tests cannot see it and need an"
                            + " application context to supply it.",
                    "Declare the dependency as a constructor parameter and let Spring inject it."
                            + " If you need a lazily resolved or prototype-scoped bean, inject"
                            + " ObjectProvider<T> or ApplicationContext only when the method IS a"
                            + " @Bean factory — never from application logic inside a service.",
                    List.of(),
                    Map.of(
                            "method", display,
                            "declaringType", method.id().declaringType()
                    )));
            return; // one finding per method
        }
    }

    /** True when the receiver is recognisably a Spring context or bean factory rather than some other getBean(). */
    private static boolean looksLikeContext(InvocationEvidence inv) {
        String hint = ((inv.scope() == null ? "" : inv.scope()) + ' '
                + (inv.receiverType() == null ? "" : inv.receiverType())).toLowerCase(java.util.Locale.ROOT);
        return hint.contains("context") || hint.contains("beanfactory") || hint.contains("ctx");
    }
}
