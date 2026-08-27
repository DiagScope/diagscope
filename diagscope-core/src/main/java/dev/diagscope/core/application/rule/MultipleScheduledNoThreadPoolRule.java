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
 * Reports projects that declare two or more {@code @Scheduled} methods without a
 * {@code TaskScheduler} or {@code ThreadPoolTaskScheduler} bean.
 *
 * <p><b>Why it matters:</b> Spring's default scheduler uses a single thread for all
 * {@code @Scheduled} tasks (backed by {@code ThreadPoolTaskScheduler} with pool size 1).
 * When more than one task exists, they execute <em>sequentially</em> on that one thread.
 * A slow or blocked task (network I/O, lock wait, expensive computation) delays every
 * other task behind it. A task that hangs indefinitely makes all others stop running.
 * This manifests as "the nightly cleanup job stopped running" — because the 2 AM report
 * task is still running. The problem is invisible during development where tasks are
 * short-lived and loads are low.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Count all methods annotated with {@code @Scheduled} across the project.</li>
 *   <li>Check whether any {@code @Bean} method has a return type that contains
 *       {@code TaskScheduler} or {@code ScheduledExecutorService} — either name signals
 *       an explicit thread pool configuration.</li>
 *   <li>If two or more scheduled tasks exist and no thread pool bean is found, emit
 *       one WARNING on the first scheduled method's location.</li>
 * </ol>
 *
 * <p><b>Threshold:</b> Two tasks. A single task never contends with another, so the
 * single-thread default is harmless there.</p>
 */
public final class MultipleScheduledNoThreadPoolRule implements ProjectRule {

    public static final String ID = "MULTIPLE_SCHEDULED_NO_THREAD_POOL";

    private static final int SCHEDULED_THRESHOLD = 2;

    private static final Set<String> FACTORY_ANNOTATIONS = Set.of(
            "Bean", "Configuration", "TestConfiguration"
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var scheduledMethods = new ArrayList<MethodModel>();
        boolean hasThreadPoolBean = false;

        for (MethodModel method : project.methods().values()) {
            if (method.annotations().contains("Scheduled")) {
                scheduledMethods.add(method);
            }
            if (!hasThreadPoolBean
                    && method.annotations().stream().anyMatch(FACTORY_ANNOTATIONS::contains)) {
                String rt = method.returnType();
                if (rt.contains("TaskScheduler") || rt.contains("ScheduledExecutorService")) {
                    hasThreadPoolBean = true;
                }
            }
        }

        if (scheduledMethods.size() < SCHEDULED_THRESHOLD || hasThreadPoolBean) {
            return List.of();
        }

        MethodModel first = scheduledMethods.get(0);
        int count = scheduledMethods.size();
        return List.of(new Finding(
                ID, Severity.WARNING, Confidence.MEDIUM, first.location(),
                "Project has " + count + " @Scheduled methods but no TaskScheduler"
                        + " @Bean. All tasks share a single thread — a slow or blocked task"
                        + " prevents every other task from running.",
                "Declare a ThreadPoolTaskScheduler @Bean with a pool size matching"
                        + " the expected task concurrency:\n"
                        + "  @Bean\n"
                        + "  public ThreadPoolTaskScheduler taskScheduler() {\n"
                        + "      var scheduler = new ThreadPoolTaskScheduler();\n"
                        + "      scheduler.setPoolSize(" + count + ");\n"
                        + "      scheduler.setThreadNamePrefix(\"scheduled-\");\n"
                        + "      scheduler.setErrorHandler(t -> log.error(\"Task failed\", t));\n"
                        + "      return scheduler;\n"
                        + "  }\n"
                        + "This allows independent tasks to run concurrently and isolates"
                        + " a slow task from delaying the rest.",
                List.of(),
                Map.of(
                        "scheduledMethodCount", String.valueOf(count),
                        "threadPoolConfigured", "false"
                )));
    }
}
