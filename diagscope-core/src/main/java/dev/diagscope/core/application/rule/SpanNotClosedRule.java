package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reports OpenTelemetry and Brave {@code Span} instances that are started but never ended.
 *
 * <p>A started span that is never explicitly ended leaks in the tracer's in-memory buffer.
 * Under Zipkin and Jaeger it may never be exported, or it is exported with a nonsensical duration.
 * Under high traffic, leaked spans exhaust the tracer's export buffer and cause span drops across
 * all operations, not just the affected one.</p>
 *
 * <p>Detection: this rule looks for invocations that start a span ({@code startSpan},
 * {@code start}, {@code startActive}) on a tracer-looking receiver, then checks whether any
 * invocation in the same method ends a span ({@code end}, {@code finish}) on a span-looking
 * receiver. It also accepts try-with-resources patterns on {@code Scope} — when the result is
 * resource-managed, it is treated as guarded.</p>
 *
 * <p>Note: if the span is stored in a field or handed to another method that is responsible for
 * closing it, this rule will produce a false positive. Add a
 * {@code // diagscope:ignore SPAN_NOT_CLOSED} comment on the {@code start} call to suppress it.</p>
 */
public final class SpanNotClosedRule implements DiagnosticRule {
    public static final String ID = "SPAN_NOT_CLOSED";

    private static final Set<String> START_METHODS = Set.of(
            "startSpan", "start", "startActive", "startWithSampler", "startAndMakeActive",
            "buildAndStart");

    private static final Set<String> END_METHODS = Set.of("end", "finish", "close");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();

            List<InvocationEvidence> starts = spanStartCalls(method.invocations());
            if (starts.isEmpty()) continue;

            boolean hasGuardedEnd = method.invocations().stream()
                    .anyMatch(inv -> isSpanEnd(inv) && (inv.insideFinally() || inv.resourceManaged()));

            if (hasGuardedEnd) continue;

            boolean hasUnguardedEnd = method.invocations().stream()
                    .anyMatch(SpanNotClosedRule::isSpanEnd);

            // Report at the first start that has no corresponding guarded end.
            for (var start : starts) {
                // If the span is resource-managed itself (try-with-resources on Scope), it is safe.
                if (start.resourceManaged()) continue;

                Severity severity = hasUnguardedEnd ? Severity.WARNING : Severity.ERROR;
                var confidence = Confidence.min(
                        hasUnguardedEnd ? Confidence.MEDIUM : Confidence.HIGH,
                        flowMethod.confidence());

                String message = hasUnguardedEnd
                        ? "Span is started and ended, but the end() call is not guarded by a"
                                + " finally block — exceptions on error paths leave the span open."
                        : "Span is started but never ended. An unclosed span leaks in the tracer"
                                + " buffer and may never be exported.";

                findings.add(new Finding(
                        ID, severity, confidence, start.location(),
                        message,
                        "Wrap the span lifecycle in a try/finally and call span.end() in the"
                                + " finally block, or use a try-with-resources on the Scope"
                                + " returned by tracer.withSpan(span).",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "startCall", start.methodName(),
                                "hasUnguardedEnd", String.valueOf(hasUnguardedEnd)
                        )));
                // Report only the first unguarded start per method to avoid noise.
                break;
            }
        }
        return List.copyOf(findings);
    }

    private static List<InvocationEvidence> spanStartCalls(List<InvocationEvidence> invocations) {
        var result = new ArrayList<InvocationEvidence>();
        for (var inv : invocations) {
            if (!START_METHODS.contains(inv.methodName())) continue;
            if (!looksLikeTracer(inv)) continue;
            result.add(inv);
        }
        return result;
    }

    private static boolean isSpanEnd(InvocationEvidence inv) {
        if (!END_METHODS.contains(inv.methodName())) return false;
        return looksLikeSpan(inv);
    }

    /** True when the receiver/scope suggests a tracing tracer (span builder or active tracer). */
    private static boolean looksLikeTracer(InvocationEvidence inv) {
        String hint = (inv.scope() + ' ' + inv.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("tracer") || hint.contains("tracing") || hint.contains("otel")
                || hint.contains("opentelemetry") || hint.contains("brave")
                || hint.contains("span") || hint.contains("scope");
    }

    /** True when the receiver/scope suggests a span that needs to be closed. */
    private static boolean looksLikeSpan(InvocationEvidence inv) {
        String hint = (inv.scope() + ' ' + inv.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("span") || hint.contains("scope") || hint.contains("tracer");
    }
}
