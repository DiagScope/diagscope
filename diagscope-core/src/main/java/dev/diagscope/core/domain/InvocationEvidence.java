package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

public record InvocationEvidence(
        SourceLocation location,
        String scope,
        String receiverType,
        String methodName,
        List<String> arguments,
        InvocationResultUsage resultUsage,
        boolean producerListenerVisible
) {
    public InvocationEvidence {
        Objects.requireNonNull(location, "location");
        scope = scope == null ? "" : scope;
        receiverType = receiverType == null ? "" : receiverType;
        Objects.requireNonNull(methodName, "methodName");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(resultUsage, "resultUsage");
    }

    public InvocationEvidence(
            SourceLocation location,
            String scope,
            String receiverType,
            String methodName,
            List<String> arguments,
            InvocationResultUsage resultUsage
    ) {
        this(location, scope, receiverType, methodName, arguments, resultUsage, false);
    }

    public InvocationEvidence(
            SourceLocation location,
            String scope,
            String methodName,
            List<String> arguments,
            boolean resultIgnored
    ) {
        this(location, scope, scope, methodName, arguments,
                resultIgnored ? InvocationResultUsage.IGNORED : InvocationResultUsage.UNKNOWN, false);
    }

    public boolean resultIgnored() {
        return resultUsage == InvocationResultUsage.IGNORED;
    }

    /** Returns a copy that records a syntax-visible {@code ProducerListener} in the same project. */
    public InvocationEvidence withProducerListenerVisible(boolean visible) {
        return visible == producerListenerVisible
                ? this
                : new InvocationEvidence(location, scope, receiverType, methodName, arguments, resultUsage, visible);
    }
}
