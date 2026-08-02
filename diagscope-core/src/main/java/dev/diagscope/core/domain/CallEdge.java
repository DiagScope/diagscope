package dev.diagscope.core.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * One resolved or terminal call hop discovered while constructing a flow.
 *
 * @param caller parser-neutral identity of the calling method
 * @param callee resolved local target, or empty when traversal stopped
 * @param callSite source range of the invocation
 * @param displayName human-readable invocation name
 * @param depth depth of the caller in the flow
 * @param confidence confidence assigned to this resolution step
 * @param resolutionReason reason the call was resolved or traversal stopped
 */
public record CallEdge(
        MethodId caller,
        Optional<MethodId> callee,
        SourceLocation callSite,
        String displayName,
        int depth,
        Confidence confidence,
        ResolutionReason resolutionReason
) {
    public CallEdge {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(callee, "callee");
        Objects.requireNonNull(callSite, "callSite");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(resolutionReason, "resolutionReason");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative");
        }
    }
}
