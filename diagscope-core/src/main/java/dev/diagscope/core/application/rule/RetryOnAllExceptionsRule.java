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
 * Reports {@code @Retryable} methods that declare no exception filter, meaning they will retry
 * on <em>every</em> thrown exception — including {@code NullPointerException},
 * {@code OutOfMemoryError}, and other programming defects that should never be retried.
 *
 * <p><b>Why it matters:</b> Retrying a programming error (e.g. {@code NullPointerException})
 * delays the failure by the full retry budget without any chance of recovery. Meanwhile, the
 * upstream caller is blocked, the downstream dependency receives redundant requests, and the
 * retry budget is wasted on a scenario it cannot resolve. Retries should be scoped to transient,
 * recoverable failures such as {@code IOException} or {@code SocketTimeoutException}.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods whose {@link MethodModel#annotations()} set contains {@code Retryable}.</li>
 *   <li>Check {@link MethodModel#annotationAttributes()} for the {@code Retryable} key.
 *       If absent (the annotation carries no attributes at all) or present but without
 *       an {@code include}, {@code value}, or {@code retryFor} key, emit WARNING.</li>
 *   <li>Confidence HIGH — the presence or absence of the filter attribute is deterministic.</li>
 * </ol>
 */
public final class RetryOnAllExceptionsRule implements ProjectRule {

    public static final String ID = "RETRY_ON_ALL_EXCEPTIONS";

    /** Attribute keys that scope retries to specific exception types. */
    private static final java.util.Set<String> FILTER_ATTRIBUTES = java.util.Set.of(
            "include", "value", "retryFor"
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
        if (!method.annotations().contains("Retryable")) return;

        // Check if annotationAttributes has a Retryable entry with a filter attribute
        var retryableAttrs = method.annotationAttributes().get("Retryable");
        if (retryableAttrs != null) {
            boolean hasFilter = retryableAttrs.keySet().stream()
                    .anyMatch(FILTER_ATTRIBUTES::contains);
            if (hasFilter) return; // scoped to specific exceptions — safe
        }
        // No Retryable entry in annotationAttributes (no attributes at all) or no filter key → fire

        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "@Retryable on '" + method.id().displayName() + "' has no exception filter"
                        + " (no 'include' or 'value' attribute). The method will be retried on every"
                        + " thrown exception, including NullPointerException and programming errors"
                        + " that can never succeed on retry.",
                "Add an 'include' attribute listing only the transient, recoverable exceptions"
                        + " this method should retry: @Retryable(include = {IOException.class},"
                        + " SocketTimeoutException.class}). Avoid retrying RuntimeException or"
                        + " Exception directly — this is equivalent to retrying everything.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "declaringType", method.id().declaringType(),
                        "filterPresent", "false"
                )));
    }
}
