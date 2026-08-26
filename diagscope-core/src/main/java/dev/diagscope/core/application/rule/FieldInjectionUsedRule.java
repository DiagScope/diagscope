package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports Spring beans that use field injection ({@code @Autowired}, {@code @Inject}, or
 * {@code @Resource} placed directly on a field) instead of constructor injection.
 *
 * <p><b>Why it matters:</b></p>
 * <ul>
 *   <li><b>Hidden dependencies:</b> Field injection hides the list of required collaborators —
 *       they are not visible in the constructor signature. A class with 15 field-injected
 *       dependencies looks identical to one with zero.</li>
 *   <li><b>Circular dependency masking:</b> Constructor injection makes circular dependencies
 *       fail fast at startup; field injection silently defers the cycle until first use.</li>
 *   <li><b>Testability:</b> Field-injected classes require a Spring context or reflection tricks
 *       to instantiate in unit tests. Constructor-injected classes instantiate with
 *       {@code new MyClass(mockDep1, mockDep2)} — no framework needed.</li>
 *   <li><b>Immutability:</b> Constructor injection allows the field to be {@code final}, making
 *       the bean immutable and thread-safe by construction.</li>
 * </ul>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>The parsers detect fields annotated with {@code @Autowired}, {@code @Inject}, or
 *       {@code @Resource} and synthesise a {@code "FieldInjectionPresent"} class-level annotation
 *       that is merged into every method's effective annotation set.</li>
 *   <li>This rule reports one finding per class (emitted on the first method found in that class)
 *       rather than once per injected field, to avoid noise when multiple fields are injected.</li>
 *   <li>Only classes carrying a Spring stereotype annotation are reported — plain Java classes that
 *       use {@code @Inject} or {@code @Resource} for other frameworks are skipped.</li>
 * </ol>
 */
public final class FieldInjectionUsedRule implements ProjectRule {

    public static final String ID = "FIELD_INJECTION_USED";

    private static final Set<String> SPRING_STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController",
            "Configuration", "ControllerAdvice", "RestControllerAdvice"
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        // Emit one finding per class, not once per method.
        var reportedTypes = new HashSet<String>();
        for (MethodModel method : project.methods().values()) {
            check(method, reportedTypes, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, Set<String> reportedTypes, List<Finding> findings) {
        // Synthetic annotation added by parsers when any field in the class uses injection
        if (!method.annotations().contains("FieldInjectionPresent")) return;

        // Only flag Spring-managed beans
        boolean isSpringBean = method.annotations().stream().anyMatch(SPRING_STEREOTYPES::contains);
        if (!isSpringBean) return;

        String declaringType = method.id().declaringType();
        if (!reportedTypes.add(declaringType)) return; // already reported for this class

        String simpleName = declaringType.contains(".")
                ? declaringType.substring(declaringType.lastIndexOf('.') + 1)
                : declaringType;

        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "'" + simpleName + "' uses field injection (@Autowired, @Inject, or @Resource"
                        + " on a field). Field injection hides dependencies, prevents final"
                        + " fields, complicates unit testing, and can mask circular dependencies.",
                "Switch to constructor injection: declare all dependencies as constructor"
                        + " parameters, assign them to 'final' fields, and remove the field-level"
                        + " @Autowired annotations. Spring automatically autowires single-constructor"
                        + " beans without any annotation. For Kotlin, use a primary constructor"
                        + " with 'val' properties. If the number of dependencies is large, consider"
                        + " splitting the class (SRP violation is often the root cause).",
                List.of(),
                Map.of(
                        "declaringType", declaringType,
                        "injectionType", "field"
                )));
    }
}
