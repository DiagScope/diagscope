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
 * Reports calls to {@code LocalDateTime.now()}, {@code Instant.now()}, {@code ZonedDateTime.now()},
 * {@code LocalDate.now()}, {@code OffsetDateTime.now()}, {@code new Date()}, and
 * {@code System.currentTimeMillis()} inside Spring-managed beans.
 *
 * <p><b>Why it matters:</b> Direct calls to the system clock embed an implicit dependency on
 * wall-clock time that cannot be controlled in tests. Code that calls {@code LocalDateTime.now()}
 * directly cannot be deterministically unit-tested for time-sensitive logic: you cannot advance
 * the clock, freeze it at a specific instant, or simulate a future date without hacks like
 * {@code Thread.sleep} or fragile assertions with tolerances.</p>
 *
 * <p>The standard Java pattern is to inject a {@code java.time.Clock} instance as a dependency
 * and call {@code LocalDateTime.now(clock)} everywhere. In tests the clock is replaced with
 * {@code Clock.fixed(instant, zone)}, giving full control over time without touching the
 * real system clock. This pattern is well-established and costs nothing at runtime.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods in Spring-managed beans (those whose annotations include a Spring stereotype).</li>
 *   <li>Inside those methods, find invocations of known "current time" methods:
 *       {@code now()}, {@code currentTimeMillis()}, and the {@code Date} constructor.</li>
 *   <li>Suppress if the {@code now()} call has an argument — a {@code Clock} or {@code ZoneId}
 *       argument means the clock source is externally controlled.</li>
 *   <li>Suppress for {@code @Bean} and test lifecycle methods.</li>
 *   <li>Confidence: HIGH for zero-argument {@code now()} calls; MEDIUM for {@code new Date()}
 *       and {@code currentTimeMillis()} which have no parameterised variant.</li>
 * </ol>
 */
public final class ClockDependencyNotInjectedRule implements ProjectRule {

    public static final String ID = "CLOCK_DEPENDENCY_NOT_INJECTED";

    private static final Set<String> SPRING_STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController",
            "Configuration", "ControllerAdvice", "RestControllerAdvice"
    );

    private static final Set<String> FACTORY_AND_TEST_ANNOTATIONS = Set.of(
            "Bean", "Configuration", "TestConfiguration",
            "BeforeEach", "BeforeAll", "Before", "Test"
    );

    private static final Set<String> DATETIME_TYPE_HINTS = Set.of(
            "LocalDateTime", "LocalDate", "Instant", "ZonedDateTime", "OffsetDateTime",
            "LocalTime", "MonthDay", "Year", "YearMonth"
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
        // Only flag Spring-managed beans
        boolean isSpringBean = method.annotations().stream().anyMatch(SPRING_STEREOTYPES::contains);
        if (!isSpringBean) return;

        // Suppress factory / test lifecycle methods
        if (method.annotations().stream().anyMatch(FACTORY_AND_TEST_ANNOTATIONS::contains)) return;

        for (InvocationEvidence inv : method.invocations()) {
            if (isClockCall(inv)) {
                String display = method.id().displayName();
                String callDescription = inv.methodName().equals("Date")
                        ? "new Date()"
                        : inv.scope() + "." + inv.methodName() + "()";

                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.MEDIUM, inv.location(),
                        "'" + display + "' calls " + callDescription + " directly, embedding"
                                + " a hard dependency on the system clock. Time-sensitive business"
                                + " logic in this method cannot be deterministically tested without"
                                + " controlling the clock.",
                        "Inject a java.time.Clock bean and pass it to the now() call:"
                                + " LocalDateTime.now(clock). In tests use Clock.fixed(instant, zone)"
                                + " to freeze time at any point. Declare the Clock as a @Bean in a"
                                + " @Configuration class: @Bean public Clock clock() { return Clock.systemUTC(); }",
                        List.of(),
                        Map.of(
                                "method", display,
                                "clockCall", callDescription,
                                "declaringType", method.id().declaringType()
                        )));
                return; // one finding per method
            }
        }
    }

    private static boolean isClockCall(InvocationEvidence inv) {
        String name = inv.methodName();

        // new Date() — constructor call with class name as methodName
        if ("Date".equals(name) && inv.arguments().isEmpty()) return true;

        // System.currentTimeMillis()
        if ("currentTimeMillis".equals(name)) {
            String scope = inv.scope() == null ? "" : inv.scope();
            String receiver = inv.receiverType() == null ? "" : inv.receiverType();
            return scope.contains("System") || receiver.contains("System");
        }

        // LocalDateTime.now(), Instant.now(), etc. — suppress if they already have a Clock argument
        if ("now".equals(name)) {
            String scope = inv.scope() == null ? "" : inv.scope();
            String receiver = inv.receiverType() == null ? "" : inv.receiverType();
            boolean isDateTimeType = DATETIME_TYPE_HINTS.stream()
                    .anyMatch(hint -> scope.contains(hint) || receiver.contains(hint));
            if (!isDateTimeType) return false;
            // Suppress if a Clock or ZoneId argument is already passed
            return inv.arguments().isEmpty();
        }

        return false;
    }
}
