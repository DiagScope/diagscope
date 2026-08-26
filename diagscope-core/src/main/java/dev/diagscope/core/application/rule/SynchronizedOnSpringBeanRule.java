package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports a {@code synchronized} method (or a Kotlin method annotated with {@code @Synchronized})
 * declared on a Spring-managed bean. The {@code synchronized} keyword on a Spring bean is almost
 * always a mistake: Spring wraps beans in CGLIB proxies, and the caller interacts with the proxy,
 * not the bean instance. The lock is therefore acquired on the proxy — a different object from the
 * underlying bean — so two threads can execute the "synchronized" method simultaneously on the same
 * logical bean. The synchronisation silently does nothing.
 *
 * <p><b>Why it matters:</b> This pattern appears in legacy code that was migrated from plain Java
 * objects to Spring beans without removing the synchronisation. The developer believes the code is
 * thread-safe; it is not. Under concurrent load, data races and inconsistent state emerge —
 * typically only in production where the concurrency level is high enough to expose the gap.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Check if the method is declared in a Spring-managed bean (the declaring type carries a
 *       Spring stereotype annotation).</li>
 *   <li>Check if the method uses the {@code synchronized} keyword (Java) or the
 *       {@code @Synchronized} annotation (Kotlin).</li>
 *   <li>The parsers synthesise a {@code "Synchronized"} entry in the method's annotation set for
 *       Java's keyword modifier so that both Java and Kotlin are detected uniformly.</li>
 *   <li>Suppress if the class is {@code final} and not proxied (no Spring AOP or transactional
 *       annotations) — in that case CGLIB wrapping is less likely, though still possible.</li>
 * </ol>
 *
 * <p><b>Fix:</b> Use Spring's concurrency abstractions:
 * {@code @Async} with a properly bounded executor for fire-and-forget work;
 * {@code java.util.concurrent.locks.Lock} with explicit locking logic if mutual exclusion is
 * truly required; or redesign to avoid shared mutable state in beans entirely.</p>
 */
public final class SynchronizedOnSpringBeanRule implements ProjectRule {

    public static final String ID = "SYNCHRONIZED_ON_SPRING_BEAN";

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
        for (MethodModel method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        // Must be a synchronized method (Java keyword or Kotlin @Synchronized)
        if (!method.annotations().contains("Synchronized")) return;

        // Must be in a Spring-managed bean (class-level annotation merged into method annotations)
        boolean isSpringBean = method.annotations().stream().anyMatch(SPRING_STEREOTYPES::contains);
        if (!isSpringBean) return;

        String methodDisplay = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.ERROR, Confidence.HIGH, method.location(),
                "'" + methodDisplay + "' is synchronized on a Spring-managed bean."
                        + " Spring proxies the bean, so the lock is acquired on the proxy object,"
                        + " not the bean itself — two threads can run this method concurrently,"
                        + " making the synchronization ineffective.",
                "Remove the 'synchronized' keyword and use an appropriate concurrency"
                        + " mechanism: a java.util.concurrent.locks.Lock for critical sections,"
                        + " an AtomicReference/AtomicLong for simple counters, or redesign the"
                        + " bean to be stateless. If mutual exclusion is truly needed and the"
                        + " class is guaranteed never proxied (e.g., final, no AOP, no"
                        + " @Transactional), document that assumption explicitly.",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "declaringType", method.id().declaringType()
                )));
    }
}
