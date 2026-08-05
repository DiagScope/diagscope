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
        List<MetricNameEvidence> metricNames,
        List<MethodCall> calls,
        ProxyProfile proxy
) {
    public MethodModel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(proxy, "proxy");
        annotations = Set.copyOf(annotations);
        catches = List.copyOf(catches);
        invocations = List.copyOf(invocations);
        metricTags = List.copyOf(metricTags);
        metricNames = List.copyOf(metricNames);
        calls = List.copyOf(calls);
    }

    public MethodModel(
            MethodId id,
            SourceLocation location,
            Set<String> annotations,
            List<CatchEvidence> catches,
            List<InvocationEvidence> invocations,
            List<MetricTagEvidence> metricTags,
            List<MetricNameEvidence> metricNames,
            List<MethodCall> calls
    ) {
        this(id, location, annotations, catches, invocations, metricTags, metricNames, calls,
                ProxyProfile.unknown());
    }

    public MethodModel(
            MethodId id,
            SourceLocation location,
            Set<String> annotations,
            List<CatchEvidence> catches,
            List<InvocationEvidence> invocations,
            List<MetricTagEvidence> metricTags,
            List<MethodCall> calls
    ) {
        this(id, location, annotations, catches, invocations, metricTags, List.of(), calls);
    }

    /** Convenience accessor: the class that declares this method. */
    public String declaringType() {
        return id.declaringType();
    }
}
