package dev.diagscope.core.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A method as the rules see it.
 *
 * @param annotationAttributes attributes of the annotations that apply to the method, keyed by the
 *        annotation simple name (for example {@code Transactional -> {propagation=REQUIRES_NEW}}).
 *        Rules that need more than the presence of an annotation read this map.
 */
public record MethodModel(
        MethodId id,
        SourceLocation location,
        Set<String> annotations,
        List<CatchEvidence> catches,
        List<InvocationEvidence> invocations,
        List<MetricTagEvidence> metricTags,
        List<MetricNameEvidence> metricNames,
        List<MethodCall> calls,
        ProxyProfile proxy,
        Map<String, Map<String, String>> annotationAttributes
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
        var copy = new LinkedHashMap<String, Map<String, String>>();
        annotationAttributes.forEach((annotation, attributes) -> copy.put(annotation, Map.copyOf(attributes)));
        annotationAttributes = Map.copyOf(copy);
    }

    public MethodModel(
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
        this(id, location, annotations, catches, invocations, metricTags, metricNames, calls, proxy, Map.of());
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

    /**
     * Returns an attribute of an annotation that applies to this method, matched case-insensitively
     * on the annotation simple name. Empty when the annotation or the attribute is absent.
     */
    public Optional<String> annotationAttribute(String annotation, String attribute) {
        Objects.requireNonNull(annotation, "annotation");
        Objects.requireNonNull(attribute, "attribute");
        for (var entry : annotationAttributes.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(annotation)) continue;
            String value = entry.getValue().get(attribute);
            if (value != null && !value.isBlank()) return Optional.of(value.trim());
        }
        return Optional.empty();
    }

    /** Returns the annotation attribute normalised for comparison (upper case, no qualifier). */
    public Optional<String> normalizedAnnotationAttribute(String annotation, String attribute) {
        return annotationAttribute(annotation, attribute).map(value -> {
            String normalized = value.trim();
            int dot = normalized.lastIndexOf('.');
            if (dot >= 0) normalized = normalized.substring(dot + 1);
            return normalized.toUpperCase(Locale.ROOT);
        });
    }
}
