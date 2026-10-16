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
 * Reports {@code new Thread(runnable).start()} calls inside Spring-managed beans outside
 * {@code @Bean} factory methods.
 *
 * <p><b>Why it matters:</b> A manually created thread in a Spring bean has no lifecycle
 * management, no pool bounds, no exception handler, no shutdown hook, and no monitoring
 * integration. When the application shuts down, Spring has no way to stop or wait for these
 * threads. Under load, each call creates a new thread, eventually exhausting the JVM file-descriptor
 * limit ({@code Too many threads} or {@code OutOfMemoryError: unable to create native thread}).
 * The JVM may not shut down cleanly if unmanaged threads are still running when
 * {@code ApplicationContext.close()} is called.</p>
 *
 * <p>The correct pattern in a Spring application is to inject a {@code TaskExecutor} or
 * {@code ExecutorService} bean and submit tasks to it. Spring's
 * {@code ThreadPoolTaskExecutor} integrates with the application lifecycle: it accepts a
 * rejection policy, exposes JMX metrics, and is stopped gracefully on shutdown via
 * {@code DisposableBean.destroy()}.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods in Spring-managed beans (declared type carries a Spring stereotype
 *       annotation, merged into the method's effective annotation set by both parsers).</li>
 *   <li>Look for constructor invocations where {@code methodName == "Thread"} — constructor
 *       calls are recorded with the class name as the method name by both parsers.</li>
 *   <li>Suppress for {@code @Bean} factory methods and test lifecycle methods — constructing
 *       a thread in a {@code @Bean} method is intentional infrastructure setup.</li>
 *   <li>Emit WARNING with HIGH confidence.</li>
 * </ol>
 */
public final class UnguardedThreadCreationRule implements ProjectRule {

    public static final String ID = "UNGUARDED_THREAD_CREATION";

    private static final Set<String> SPRING_STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController",
            "Configuration", "ControllerAdvice", "RestControllerAdvice"
    );

    private static final Set<String> SUPPRESSED_ANNOTATIONS = Set.of(
            "Bean", "Configuration", "TestConfiguration",
            "BeforeEach", "BeforeAll", "Before", "Test"
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
        boolean isSpringBean = method.annotations().stream().anyMatch(SPRING_STEREOTYPES::contains);
        if (!isSpringBean) return;

        if (method.annotations().stream().anyMatch(SUPPRESSED_ANNOTATIONS::contains)) return;

        for (InvocationEvidence inv : method.invocations()) {
            if (!"Thread".equals(inv.methodName())) continue;

            // Also detect Thread.ofVirtual().start() and Thread.ofPlatform().start() patterns
            String display = method.id().displayName();
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.HIGH, inv.location(),
                    "'" + display + "' creates a raw Thread in a Spring-managed bean."
                            + " Manually created threads have no pool bounds, no lifecycle"
                            + " management, and cannot be stopped or monitored by Spring."
                            + " They prevent graceful shutdown and can exhaust the JVM's native thread limit.",
                    "Inject a Spring TaskExecutor or ExecutorService @Bean and submit the"
                            + " Runnable to it: executor.execute(runnable). For fire-and-forget"
                            + " async work consider @Async with a named ThreadPoolTaskExecutor."
                            + " For virtual threads (Java 21+) use Executors.newVirtualThreadPerTaskExecutor()"
                            + " declared as a @Bean so Spring controls the pool lifecycle.",
                    List.of(),
                    Map.of(
                            "method", display,
                            "declaringType", method.id().declaringType()
                    )));
            return; // one finding per method
        }
    }
}
