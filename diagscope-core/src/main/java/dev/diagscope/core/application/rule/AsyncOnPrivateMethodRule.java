package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.MethodVisibility;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code @Async} annotations placed on private methods.
 *
 * <p>Spring's {@code @Async} support works through a proxy. A proxy can only intercept calls to
 * methods it can override, which requires at minimum package-private (or protected/public)
 * visibility. A {@code private} method cannot be overridden, so the proxy never sees the call —
 * the method executes synchronously on the calling thread, with no error or warning at startup
 * or at runtime.</p>
 *
 * <p>This is a hard proxy-bypass: confidence is HIGH when the declaring type is a Spring-managed
 * bean, MEDIUM otherwise (the annotation may be from a different framework with different
 * semantics, though the same proxy constraint typically applies).</p>
 */
public final class AsyncOnPrivateMethodRule implements DiagnosticRule {
    public static final String ID = "ASYNC_ON_PRIVATE_METHOD";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            if (!DiagnosticSignals.hasAnnotation(method, "Async")) continue;
            if (method.proxy().visibility() != MethodVisibility.PRIVATE) continue;

            Confidence confidence = Confidence.min(
                    method.proxy().springManagedType() ? Confidence.HIGH : Confidence.MEDIUM,
                    flowMethod.confidence());

            findings.add(new Finding(
                    ID, Severity.ERROR, confidence, method.location(),
                    "@Async on a private method is ignored by the Spring proxy."
                            + " The method runs synchronously on the calling thread.",
                    "Change the method visibility to public (or at minimum protected), or move"
                            + " the async logic to a public method that the private method delegates to.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    Map.of(
                            "method", method.id().displayName(),
                            "visibility", method.proxy().visibility().displayName(),
                            "springManaged", String.valueOf(method.proxy().springManagedType())
                    )));
        }
        return List.copyOf(findings);
    }
}
