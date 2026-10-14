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
 * <p><b>Why it matters:</b> The Service Locator anti-pattern hides dependencies. When a class
 * calls {@code applicationContext.getBean(OrderService.class)}, its dependency on
 * {@code OrderService} is invisible to the framework and to callers. Spring cannot detect
 * circular dependencies at startup, the dependency cannot be mocked in unit tests without
 * mocking the entire context, and the class cannot be used outside a Spring context at all.
 * The pattern also bypasses Spring's lifecycle guarantees: the bean is retrieved after the
 * context is fully initialized, which means scoped beans (request-scoped, session-scoped)
 * may not behave as expected.</p>
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

        for (InvocationEvidence inv : method.invocations()) {
            if (!"getBean".equals(inv.methodName())) continue;

            String display = method.id().displayName();
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.HIGH, inv.location(),
                    "'" + display + "' calls applicationContext.getBean() — the Service Locator"
                            + " anti-pattern. Dependencies obtained this way are invisible to the"
                            + " framework (no circular-dependency detection at startup) and"
                            + " cannot be mocked in unit tests without a full application context.",
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
}
