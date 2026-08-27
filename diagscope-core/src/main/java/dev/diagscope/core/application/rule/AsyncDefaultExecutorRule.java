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
 * Reports {@code @Async} methods that do not specify a named executor, relying on Spring's
 * default {@code SimpleAsyncTaskExecutor}.
 *
 * <p><b>Why it matters:</b> {@code SimpleAsyncTaskExecutor} creates a <em>new thread</em> for
 * every method invocation — it performs no thread pooling or reuse. Under any non-trivial load
 * this means one OS thread per call, consuming stack memory (typically 256KB–1MB per thread),
 * exhausting file descriptors, and causing context-switch overhead that degrades throughput for
 * the entire JVM. The problem does not appear in development (low call rate) and surfaces in
 * production as a gradual increase in thread count visible in JMX, followed by
 * {@code OutOfMemoryError: unable to create new native thread} during traffic spikes.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods annotated with {@code @Async}.</li>
 *   <li>Check that the annotation has no {@code value} attribute (the executor name).</li>
 *   <li>Emit WARNING — the default executor is rarely appropriate for production load.</li>
 * </ol>
 *
 * <p><b>Fix:</b> Declare a named {@code ThreadPoolTaskExecutor} bean and reference it:
 * {@code @Async("myTaskExecutor")}. Configure pool size, queue capacity, and rejection policy
 * appropriate for the workload. Without an explicit pool, throughput is unbounded and
 * unmonitored.</p>
 */
public final class AsyncDefaultExecutorRule implements ProjectRule {

    public static final String ID = "ASYNC_DEFAULT_EXECUTOR";

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
        if (!method.annotations().contains("Async")) return;

        Map<String, String> asyncAttrs = method.annotationAttributes().get("Async");
        // If a value (executor bean name) is specified, the developer has chosen an executor
        if (asyncAttrs != null && asyncAttrs.containsKey("value")
                && !asyncAttrs.get("value").isBlank()) {
            return;
        }

        String methodDisplay = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "'" + methodDisplay + "' is @Async without a named executor. Spring uses"
                        + " SimpleAsyncTaskExecutor by default, which creates a new OS thread"
                        + " for every invocation — no pooling, no reuse, no backpressure.",
                "Declare a ThreadPoolTaskExecutor @Bean and reference it by name:"
                        + " @Async(\"myTaskExecutor\"). Configure corePoolSize, maxPoolSize,"
                        + " queueCapacity, and a RejectedExecutionHandler that suits the workload."
                        + " This bounds thread creation, enables monitoring via JMX/Micrometer,"
                        + " and applies backpressure when the queue is full.",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "missingExecutor", "true"
                )));
    }
}
