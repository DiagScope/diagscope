package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code Thread.sleep()} called in the flow of a web, Kafka, or scheduled entrypoint.
 *
 * <p>{@code Thread.sleep()} blocks the calling thread unconditionally for the specified duration.
 * In a servlet-model or platform-thread web server, every request is served by a pooled thread.
 * A sleeping thread holds that slot, making it unavailable to other incoming requests for the
 * entire sleep duration.</p>
 *
 * <p><b>Why it matters:</b> Under normal load a single sleeping thread may go unnoticed. Under
 * any spike — a slow downstream, a misconfigured retry loop, a burst of concurrent requests —
 * all available threads can be pinned simultaneously, saturating the thread pool. The result is
 * a complete service outage: new requests queue indefinitely, health checks time out, and the
 * load balancer marks the instance unhealthy. The root cause (a {@code Thread.sleep} that should
 * never have been in production) is often buried in a utility method and invisible in the
 * stack trace shown to on-call engineers.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Identify invocations of {@code Thread.sleep()} by method name {@code sleep} and
 *       scope/receiverType hint containing {@code thread}.</li>
 *   <li>The rule fires on any method in the flow, not just the entrypoint method itself —
 *       a sleep in a helper utility called from a REST controller is equally harmful.</li>
 *   <li>Emit WARNING with HIGH confidence — the call is unambiguous.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> Does not distinguish between a sleep intended for retry
 * back-off (where a scheduled task or explicit retry logic is the right abstraction) and one
 * used for testing. Suppress with {@code diagscope:ignore} when the sleep is intentional and
 * the thread lifecycle is controlled (e.g. a dedicated background thread not serving requests).</p>
 */
public final class ThreadSleepInFlowRule implements DiagnosticRule {

    public static final String ID = "THREAD_SLEEP_IN_FLOW";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var invocation : method.invocations()) {
                if (!isThreadSleep(invocation)) continue;

                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Thread.sleep() is called in the flow reachable from '"
                                + flow.entrypoint().method().displayName() + "'."
                                + " Sleeping blocks the server thread for the full duration,"
                                + " preventing it from serving other requests. Under concurrent"
                                + " load all available threads can be pinned simultaneously.",
                        "Replace Thread.sleep() with a non-blocking alternative:"
                                + " for retry back-off use Spring Retry with @Retryable(backoff = @Backoff(...));"
                                + " for polling use @Scheduled with a fixedDelay instead of an inline loop;"
                                + " for reactive flows use Mono.delay() or Flux.delayElements()."
                                + " If the sleep is inside a dedicated background thread not handling"
                                + " requests, add a diagscope:ignore comment.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "entrypoint", flow.entrypoint().method().displayName()
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isThreadSleep(InvocationEvidence invocation) {
        if (!invocation.methodName().equals("sleep")) return false;
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase();
        return hint.contains("thread");
    }
}
