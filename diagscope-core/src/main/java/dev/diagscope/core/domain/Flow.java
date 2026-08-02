package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

public record Flow(Entrypoint entrypoint, List<FlowMethod> methods, List<CallEdge> edges) {
    public Flow {
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(edges, "edges");
        methods = List.copyOf(methods);
        edges = List.copyOf(edges);
    }

    /** Returns the weakest confidence among reached methods. */
    public Confidence confidence() {
        Confidence confidence = Confidence.HIGH;
        for (var method : methods) {
            confidence = Confidence.min(confidence, method.confidence());
        }
        return confidence;
    }

    /** Returns calls at which local traversal stopped or became uncertain. */
    public List<CallEdge> boundaries() {
        return edges.stream().filter(Flow::isBoundary).toList();
    }

    private static boolean isBoundary(CallEdge edge) {
        if (edge.callee().isEmpty()) {
            return true;
        }
        return switch (edge.resolutionReason()) {
            case AMBIGUOUS, EXTERNAL, MAX_DEPTH, UNRESOLVED -> true;
            case SAME_CLASS, DECLARED_RECEIVER, SINGLE_IMPLEMENTATION -> false;
        };
    }
}
