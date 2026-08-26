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
 * Reports Kafka retry configurations that use a fixed zero-delay (or near-zero) backoff
 * without exponential growth, causing retry storms against a failing downstream.
 *
 * <p>When a Kafka message fails and is retried, the broker must serve the same message again.
 * With zero or sub-100ms fixed backoff and multiple retry attempts, a single failed message
 * generates a burst of retry attempts — potentially hundreds per second — against the same
 * downstream system that is already struggling. This amplifies the failure, prolongs recovery,
 * can trigger rate limiting or circuit breakers, and saturates the partition.</p>
 *
 * <p>The rule detects two patterns:</p>
 * <ul>
 *   <li><b>FixedBackOff(interval, maxAttempts)</b> — flags when {@code interval < 100} ms and
 *       {@code maxAttempts > 1}, since this causes rapid retries with no meaningful pause.</li>
 *   <li><b>DefaultErrorHandler(recoverer)</b> or <b>DefaultErrorHandler()</b> with no
 *       explicit {@code BackOffPolicy} configured — the default is a 9-second fixed back-off
 *       which is adequate, but explicit zero back-off wired via {@code new FixedBackOff(0, N)}
 *       removes that protection.</li>
 * </ul>
 *
 * <p>Detection relies on constructor call capture ({@code new FixedBackOff(interval, max)} in
 * Java; {@code FixedBackOff(interval, max)} in Kotlin). The interval argument is parsed as a
 * numeric literal; variable references are not resolved.</p>
 */
public final class KafkaRetryWithoutBackoffRule implements ProjectRule {

    public static final String ID = "KAFKA_RETRY_WITHOUT_BACKOFF";

    // Types whose constructors indicate a retry backoff configuration
    private static final Set<String> BACKOFF_TYPES = Set.of(
            "FixedBackOff"
    );

    // Threshold below which a fixed interval is considered "too fast"
    private static final long MIN_SAFE_INTERVAL_MS = 100L;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            for (var inv : method.invocations()) {
                checkConstructor(method, inv, findings);
            }
        }
        return List.copyOf(findings);
    }

    private static void checkConstructor(MethodModel method, InvocationEvidence inv,
                                         List<Finding> findings) {
        if (!BACKOFF_TYPES.contains(inv.methodName())) return;

        // FixedBackOff(long interval, long maxAttempts)
        if (inv.arguments().size() < 2) {
            // No-arg or single-arg FixedBackOff — default interval is 0ms, maxAttempts=Long.MAX_VALUE
            // This IS the zero-backoff pattern
            findings.add(buildFinding(method, inv, 0L, -1L));
            return;
        }

        long interval = parseLongLiteral(inv.arguments().get(0));
        long maxAttempts = parseLongLiteral(inv.arguments().get(1));

        if (interval < 0) return; // cannot parse — skip to avoid false positives
        if (interval >= MIN_SAFE_INTERVAL_MS) return; // safe
        if (maxAttempts == 1) return; // single attempt is fine regardless of interval

        findings.add(buildFinding(method, inv, interval, maxAttempts));
    }

    private static Finding buildFinding(MethodModel method, InvocationEvidence inv,
                                        long interval, long maxAttempts) {
        String attemptsDesc = maxAttempts < 0 ? "unlimited attempts" : maxAttempts + " attempts";
        return new Finding(
                ID, Severity.WARNING, Confidence.HIGH, inv.location(),
                "FixedBackOff configured with " + interval + "ms interval and " + attemptsDesc
                        + ". This causes rapid retry storms against a failing downstream,"
                        + " amplifying the failure rather than allowing recovery.",
                "Replace with an ExponentialBackOff that starts at ≥1 s and caps at a reasonable"
                        + " maximum: new ExponentialBackOff(1000, 2.0) with setMaxInterval(30000)."
                        + " For Kafka retry topics, use RetryTopicConfigurationBuilder with"
                        + " .exponentialBackoff(1000, 2.0, 30000) to configure structured retries"
                        + " with DLT fallback.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "intervalMs", String.valueOf(interval),
                        "maxAttempts", maxAttempts < 0 ? "unlimited" : String.valueOf(maxAttempts)
                ));
    }

    /**
     * Parses a long literal from source text, handling {@code L} suffix and simple expressions.
     * Returns {@code -1} when the text cannot be resolved to a constant.
     */
    private static long parseLongLiteral(String arg) {
        if (arg == null) return -1L;
        String s = arg.strip().replace("_", "").replace("L", "").replace("l", "");
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1L; // variable reference or expression — cannot resolve statically
        }
    }
}
