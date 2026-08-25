package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports methods with an excessively large parameter list.
 *
 * <p>A method with more than {@value #MAX_PARAMETERS} parameters is hard to call correctly,
 * easy to mis-order, and often indicates the method does too much or that several related
 * parameters should be grouped. The idiomatic fix is to introduce a dedicated parameter
 * object (a {@code record} in Java 16+, a Kotlin data class, or a simple value class).</p>
 *
 * <p>Constructor parameters for dependency injection are not flagged: they reflect the
 * number of collaborators the class needs, not the internal complexity of a single operation.</p>
 */
public final class ExcessiveMethodParametersRule implements DiagnosticRule {

    public static final String ID = "EXCESSIVE_METHOD_PARAMETERS";

    /** Maximum number of parameters before a finding is emitted. */
    static final int MAX_PARAMETERS = 5;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            if (isConstructorLike(method.id())) continue;
            // Vararg methods have variable arity; the declared param count
            // would be misleading so skip them to avoid false positives.
            if (method.callableShape().varargIndex() >= 0) continue;

            int paramCount = method.id().parameterTypes().size();
            if (paramCount <= MAX_PARAMETERS) continue;

            Confidence confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
            findings.add(new Finding(
                    ID, Severity.INFO, confidence, method.location(),
                    "Method " + method.id().name() + " declares " + paramCount
                            + " parameters (recommended maximum: " + MAX_PARAMETERS + ").",
                    "Group related parameters into a dedicated parameter object (record or data class)."
                            + " This improves readability, eliminates argument-order bugs, and makes"
                            + " future extensions backward-compatible.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    Map.of(
                            "method", method.id().displayName(),
                            "paramCount", String.valueOf(paramCount),
                            "maxAllowed", String.valueOf(MAX_PARAMETERS))
            ));
        }
        return List.copyOf(findings);
    }

    /**
     * Returns true for constructors, which are intentionally excluded.
     * Handles both the bytecode name {@code <init>} and source-level constructors
     * (whose name matches the simple class name).
     */
    private static boolean isConstructorLike(MethodId id) {
        if ("<init>".equals(id.name())) return true;
        String type = id.declaringType();
        // Extract the simple class name (strip package and outer-class prefix)
        String simpleName = type;
        int lastDot = simpleName.lastIndexOf('.');
        if (lastDot >= 0) simpleName = simpleName.substring(lastDot + 1);
        int lastDollar = simpleName.lastIndexOf('$');
        if (lastDollar >= 0) simpleName = simpleName.substring(lastDollar + 1);
        return simpleName.equals(id.name());
    }
}
