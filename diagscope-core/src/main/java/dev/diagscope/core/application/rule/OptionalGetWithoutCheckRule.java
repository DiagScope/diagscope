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
 * Reports {@code Optional.get()} calls in methods that do not also call
 * {@code isPresent()}, {@code isEmpty()}, or {@code ifPresent()} on an Optional, which throws
 * {@link java.util.NoSuchElementException} at runtime when the value is absent.
 *
 * <p>The correct null-safe idioms are {@code optional.orElse(default)},
 * {@code optional.orElseThrow(SomeException::new)}, {@code optional.ifPresent(…)}, or
 * {@code optional.map(…)}. Calling {@code get()} directly is only safe after an explicit
 * {@code isPresent()} guard — and even then the idiomatic style is to use {@code orElseThrow}
 * instead so the intent is self-documenting.</p>
 */
public final class OptionalGetWithoutCheckRule implements DiagnosticRule {
    public static final String ID = "OPTIONAL_GET_WITHOUT_CHECK";

    private static final String GET_METHOD = "get";

    /** Methods that safely handle the absent case without throwing. */
    private static final Set<String> SAFE_METHODS = Set.of(
            "isPresent", "isEmpty", "ifPresent", "ifPresentOrElse",
            "orElse", "orElseGet", "orElseThrow", "map", "flatMap", "filter", "stream");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            var invocations = method.invocations();

            for (var getCall : invocations) {
                if (!GET_METHOD.equals(getCall.methodName())) continue;
                if (!looksLikeOptional(getCall)) continue;

                // Is there any safe Optional accessor in this method for the same receiver?
                boolean hasSafeCheck = invocations.stream()
                        .filter(u -> SAFE_METHODS.contains(u.methodName()))
                        .filter(u -> looksLikeOptional(u) || sameReceiver(getCall, u))
                        .anyMatch(u -> sameReceiver(getCall, u));

                if (hasSafeCheck) continue;

                // Confidence: MEDIUM because the guard may be in a caller not visible here
                Confidence confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());

                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, getCall.location(),
                        "Optional.get() called without a preceding isPresent() or isEmpty() check.",
                        "Use orElse(), orElseGet(), orElseThrow(), or map() instead of get()."
                                + " If a missing value is truly illegal here, orElseThrow() makes the intent explicit.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "receiver", getCall.scope().isBlank()
                                        ? getCall.receiverType() : getCall.scope())
                ));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean looksLikeOptional(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("optional") || hint.contains("opt");
    }

    private static boolean sameReceiver(InvocationEvidence a, InvocationEvidence b) {
        String ra = a.scope().isBlank() ? a.receiverType() : a.scope();
        String rb = b.scope().isBlank() ? b.receiverType() : b.scope();
        if (ra.isBlank() || rb.isBlank()) return false;
        return ra.equals(rb);
    }
}
