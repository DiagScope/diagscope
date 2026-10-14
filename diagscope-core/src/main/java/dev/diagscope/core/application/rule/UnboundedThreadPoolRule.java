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
import java.util.regex.Pattern;

/**
 * Reports thread pools with no upper bound on threads or on queued work.
 *
 * <p>A pool is the application's back-pressure. {@code Executors.newCachedThreadPool()} starts a new thread for
 * every task that finds no idle one, so a burst or a slow dependency turns into thousands of threads and an
 * {@code OutOfMemoryError: unable to create native thread}. A {@code ThreadPoolExecutor} with a
 * {@code LinkedBlockingQueue()} created without a capacity accepts unlimited work, so under overload memory grows
 * and latency climbs without any rejection to signal it. {@code maximumPoolSize = Integer.MAX_VALUE} is the same
 * mistake spelled differently.</p>
 *
 * <p>Both the {@code Executors} factory and an explicit {@code ThreadPoolExecutor}/Spring
 * {@code ThreadPoolTaskExecutor} configuration are inspected. A bounded queue with a rejection policy, a fixed pool
 * and virtual-thread executors are not reported.</p>
 */
public final class UnboundedThreadPoolRule implements ProjectRule {

    public static final String ID = "UNBOUNDED_THREAD_POOL";

    private static final Pattern UNBOUNDED_QUEUE = Pattern.compile(
            "LinkedBlockingQueue\\s*(?:<[^()]*>)?\\s*\\(\\s*\\)|LinkedBlockingDeque\\s*(?:<[^()]*>)?\\s*\\(\\s*\\)");
    private static final Pattern MAX_VALUE = Pattern.compile("(?:Integer|Int)\\s*\\.\\s*MAX_VALUE");
    private static final Set<String> POOL_SETTERS = Set.of("setMaxPoolSize", "setQueueCapacity");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                String problem = null;
                Confidence confidence = Confidence.HIGH;
                String arguments = String.join(" ", invocation.arguments());
                if (invocation.methodName().equals("newCachedThreadPool") && RuleSupport.isCallOn(invocation, "Executors")) {
                    problem = "Executors.newCachedThreadPool() starts a new thread for every task that finds no idle one";
                } else if (RuleSupport.isConstructorOf(invocation, "ThreadPoolExecutor")) {
                    if (UNBOUNDED_QUEUE.matcher(arguments).find()) {
                        problem = "this ThreadPoolExecutor queues work in a LinkedBlockingQueue with no capacity";
                        confidence = Confidence.MEDIUM;
                    } else if (MAX_VALUE.matcher(arguments).find()) {
                        problem = "this ThreadPoolExecutor allows Integer.MAX_VALUE threads";
                        confidence = Confidence.MEDIUM;
                    }
                } else if (POOL_SETTERS.contains(invocation.methodName()) && MAX_VALUE.matcher(arguments).find()) {
                    problem = invocation.methodName() + "(Integer.MAX_VALUE) removes the bound of this pool";
                    confidence = Confidence.MEDIUM;
                }
                if (problem == null) continue;
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "'" + method.id().displayName() + "': " + problem + ". Under a burst or a slow dependency the pool grows"
                                + " (threads or queue) without limit instead of applying back-pressure.",
                        "Use a bounded pool: a fixed thread count with a bounded ArrayBlockingQueue and a RejectedExecutionHandler"
                                + " (CallerRunsPolicy is a simple back-pressure), or a virtual-thread executor for blocking I/O.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", invocation.methodName())));
            }
        }
        return List.copyOf(findings);
    }
}
