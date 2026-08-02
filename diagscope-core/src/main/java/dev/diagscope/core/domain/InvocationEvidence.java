package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

public record InvocationEvidence(
        SourceLocation location,
        String scope,
        String receiverType,
        String methodName,
        List<String> arguments,
        InvocationResultUsage resultUsage
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
            String methodName,
            List<String> arguments,
            boolean resultIgnored
    ) {
        this(location, scope, scope, methodName, arguments,
                resultIgnored ? InvocationResultUsage.IGNORED : InvocationResultUsage.UNKNOWN);
    }

    public boolean resultIgnored() {
        return resultUsage == InvocationResultUsage.IGNORED;
    }
}
