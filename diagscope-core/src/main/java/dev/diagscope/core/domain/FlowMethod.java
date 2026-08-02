package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * A parser-neutral method reached from a flow entrypoint.
 *
 * @param method method facts extracted by the source adapter
 * @param depth number of call edges between the entrypoint and this method
 * @param confidence confidence of the complete path used to reach the method
 * @param path ordered method identities from the entrypoint to this method
 */
public record FlowMethod(
        MethodModel method,
        int depth,
        Confidence confidence,
        List<MethodId> path
) {
    public FlowMethod {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(path, "path");
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative");
        }
        path = List.copyOf(path);
        if (path.size() != depth + 1) {
            throw new IllegalArgumentException("path must contain exactly depth + 1 methods");
        }
        if (!path.getLast().equals(method.id())) {
            throw new IllegalArgumentException("path must end at the reached method");
        }
    }
}
