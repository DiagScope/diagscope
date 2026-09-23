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
 * Reports {@code @Scheduled(fixedRate = N)} methods where the rate is below 500 ms and
 * the method body contains more than one invocation, indicating non-trivial work.
 *
 * <p>{@code fixedRate} fires the task at a fixed wall-clock interval, regardless of how
 * long the previous execution took. If the task takes longer than the rate, Spring
 * (with the default single-threaded scheduler) queues the next execution — scheduled
 * tasks pile up in memory. With a thread-pool scheduler, concurrent executions run in
 * parallel and fight over shared resources. Either way, a sub-500ms rate with a
 * non-trivial body creates a pattern that degrades under load.</p>
 *
 * <p><b>Why it matters:</b> Tasks scheduled at very high frequency are almost always
 * polling for something — a queue, a flag, a database record. Polling at 100ms with
 * a database query per tick issues 600 queries per minute per instance, even when there
 * is nothing to process. This baseline load prevents the database from entering idle
 * states, inflates connection pool utilisation, and bloats query logs. The correct
 * alternative for event-driven scenarios is a message broker; for true periodic work,
 * a rate above 1 second is almost always sufficient.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods annotated with {@code @Scheduled} that have a {@code fixedRate}
 *       attribute parseable as a long value below 500.</li>
 *   <li>Check that the method has at least 2 invocations (skip trivial setters / counters).</li>
 *   <li>Skip {@code fixedRateString} — the value may be a property placeholder that
 *       resolves to a reasonable value at runtime.</li>
 *   <li>Emit WARNING with MEDIUM confidence.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> The threshold is heuristic. A 100ms cache-refresh
 * that calls only an in-memory lookup is not problematic; a 1000ms task that opens
 * a database connection is. The rule uses invocation count as a proxy for cost.
 * Suppress with {@code diagscope:ignore} when the high rate is intentional and the
 * cost per tick is demonstrably low.</p>
 */
public final class ScheduledFixedRateTooAggressiveRule implements ProjectRule {

    public static final String ID = "SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE";

    /** Threshold in milliseconds below which the rate is considered too aggressive. */
    private static final long RATE_THRESHOLD_MS = 500L;

    /** Minimum invocation count to distinguish non-trivial from trivial methods. */
    private static final int MIN_INVOCATIONS = 2;

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
        if (!method.annotations().contains("Scheduled")) return;

        Map<String, String> scheduledAttrs = method.annotationAttributes().get("Scheduled");
        if (scheduledAttrs == null) return;

        String rateValue = scheduledAttrs.get("fixedRate");
        if (rateValue == null || rateValue.isBlank()) return;

        long rateMs = parseLong(rateValue);
        if (rateMs <= 0 || rateMs >= RATE_THRESHOLD_MS) return;

        if (method.invocations().size() < MIN_INVOCATIONS) return;

        String methodDisplay = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.MEDIUM, method.location(),
                "@Scheduled method '" + methodDisplay + "' has fixedRate = " + rateMs + " ms"
                        + " — below the " + RATE_THRESHOLD_MS + " ms threshold — with "
                        + method.invocations().size() + " invocations in the body."
                        + " If any iteration takes longer than the rate, executions will"
                        + " overlap or queue indefinitely.",
                "Increase the rate to at least 1000 ms, or redesign the polling loop as"
                        + " a message-driven consumer that reacts to events rather than polling."
                        + " If the rate must remain low, verify the method body is genuinely"
                        + " O(1) and never blocks on I/O. Consider fixedDelay instead of"
                        + " fixedRate to guarantee a pause between executions.",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "fixedRateMs", String.valueOf(rateMs),
                        "invocationCount", String.valueOf(method.invocations().size())
                )));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim().replaceAll("[lL_]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
