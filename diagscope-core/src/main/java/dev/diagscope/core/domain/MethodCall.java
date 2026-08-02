package dev.diagscope.core.domain;

import java.util.Objects;
import java.util.Optional;

public record MethodCall(
        SourceLocation location,
        String scope,
        String methodName,
        int argumentCount,
        Optional<MethodId> target,
        ResolutionReason resolutionReason
) {
    public MethodCall {
        Objects.requireNonNull(location, "location");
        scope = scope == null ? "" : scope;
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(resolutionReason, "resolutionReason");
    }
}
