package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MethodModel(
        MethodId id,
        SourceLocation location,
        Set<String> annotations,
        List<CatchEvidence> catches,
        List<InvocationEvidence> invocations,
        List<MetricTagEvidence> metricTags,
        List<MethodCall> calls
) {
    public MethodModel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(location, "location");
        annotations = Set.copyOf(annotations);
        catches = List.copyOf(catches);
        invocations = List.copyOf(invocations);
        metricTags = List.copyOf(metricTags);
        calls = List.copyOf(calls);
    }
}
