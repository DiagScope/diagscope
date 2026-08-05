package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * A stable reference from a finding to a business flow that reaches it.
 *
 * <p>Phase 2 enriches this reference with the deterministic call path that connects the flow
 * entrypoint to the method holding the evidence. The path is navigation metadata: it never takes
 * part in the finding fingerprint, so moving code between callers does not invalidate baselines.</p>
 *
 * @param id stable flow identity
 * @param displayName human readable entrypoint name
 * @param entrypointType kind of entrypoint the flow starts from
 * @param confidence confidence of the path used to reach the evidence
 * @param depth number of call edges between the entrypoint and the evidence method
 * @param path ordered method display names from the entrypoint to the evidence method
 */
public record RelatedFlow(
        String id,
        String displayName,
        EntrypointType entrypointType,
        Confidence confidence,
        int depth,
        List<String> path
) {
    public static final String PATH_SEPARATOR = " -> ";

    public RelatedFlow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(entrypointType, "entrypointType");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(path, "path");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative");
        }
        path = List.copyOf(path);
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path must contain at least the entrypoint method");
        }
        if (path.size() != depth + 1) {
            throw new IllegalArgumentException("path must contain exactly depth + 1 methods");
        }
        path.forEach(method -> Objects.requireNonNull(method, "path must not contain null"));
    }

    /** Reference without a traced call path: the entrypoint itself holds the evidence. */
    public static RelatedFlow from(Entrypoint entrypoint, Confidence confidence) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        return new RelatedFlow(id(entrypoint), entrypoint.displayName(), entrypoint.type(), confidence,
                0, List.of(entrypoint.method().displayName()));
    }

    /** Reference carrying the traced call path from the entrypoint to the evidence method. */
    public static RelatedFlow from(Entrypoint entrypoint, FlowMethod flowMethod, Confidence confidence) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(flowMethod, "flowMethod");
        return new RelatedFlow(id(entrypoint), entrypoint.displayName(), entrypoint.type(), confidence,
                flowMethod.depth(), flowMethod.path().stream().map(MethodId::displayName).toList());
    }

    /** Method holding the evidence reached through this flow. */
    public String affectedMethod() {
        return path.getLast();
    }

    /** Callers between the entrypoint and the evidence method, entrypoint first. */
    public List<String> callers() {
        return path.subList(0, path.size() - 1);
    }

    /** Human readable rendering of the traced call path. */
    public String callPath() {
        return String.join(PATH_SEPARATOR, path);
    }

    private static String id(Entrypoint entrypoint) {
        return entrypoint.type().name() + ':' + entrypoint.method().displayName();
    }
}
