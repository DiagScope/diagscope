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
 * @param returnType           the declared return type of the method as a simple string, e.g.
 *        {@code "List<Order>"}, {@code "void"}, {@code "String"}. Empty when the type could not be
 *        determined (constructors, lambdas, or adapter gaps).
 * @param declaringTypeIsInterface {@code true} when the method is declared inside an interface
 *        (not an abstract class). Used by rules that check annotations on interface-level methods.
 * @param throwsInFinally      source locations of {@code throw} statements that appear directly
 *        inside a {@code finally} block of this method. Non-empty when the method contains at least
 *        one such throw, which can suppress the original exception from the protected block.
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
        Map<String, Map<String, String>> annotationAttributes,
        CallableShape callableShape,
        String returnType,
        boolean declaringTypeIsInterface,
        List<SourceLocation> throwsInFinally
) {
    /** The same method under another identity, used to qualify a repeat declared in another module. */
    public MethodModel withId(MethodId other) {
        return new MethodModel(other, location, annotations, catches, invocations, metricTags, metricNames, calls,
                proxy, annotationAttributes, callableShape, returnType, declaringTypeIsInterface, throwsInFinally);
    }

    /**
     * Synthetic annotation the Kotlin adapter adds to {@code suspend} functions, so rules can tell a
     * coroutine body from a plain one without a new field on every constructor. Mirrors the synthetic
     * {@code Final} annotation used for effectively final Kotlin methods.
     */
    public static final String SUSPEND_ANNOTATION = "Suspend";
    /** Synthetic marker on an interface method that has a body (a Java default method). */
    public static final String IMPLEMENTED_IN_INTERFACE_ANNOTATION = "ImplementedInInterface";

    /**
     * Synthetic annotation carrying the method's McCabe cyclomatic complexity in its {@code value} attribute:
     * one plus the number of branches, loops, catch clauses, case labels, ternaries and short-circuit operators.
     */
    public static final String CYCLOMATIC_ANNOTATION = "Cyclomatic";

    /**
     * Synthetic annotation listing, per parameter index ({@code p0}, {@code p1}, ...), the simple names of the
     * annotations on that parameter, comma separated. Parameters without annotations are absent.
     */
    /**
     * Synthetic class-level annotation listing, in its {@code fields} attribute, the injected fields declared with
     * {@code @Autowired(required = false)} that stay {@code null} when no bean matches (comma separated).
     */
    public static final String OPTIONAL_AUTOWIRED_ANNOTATION = "OptionalAutowired";

    public static final String JAKARTA_TRANSACTIONAL_ANNOTATION = "JakartaTransactional";

    public static final String PARAMETER_ANNOTATIONS = "ParameterAnnotations";

    /** Names of the annotations on the parameter at {@code index}, or an empty set. */
    public Set<String> parameterAnnotations(int index) {
        String names = annotationAttributes.getOrDefault(PARAMETER_ANNOTATIONS, Map.of()).getOrDefault("p" + index, "");
        return names.isEmpty() ? Set.of() : Set.copyOf(java.util.Arrays.asList(names.split(",")));
    }

    /** Whether the method is declared in a test source root ({@code src/test}). */
    public boolean testSource() {
        return SourceScope.isTestSource(location.file());
    }

    public MethodModel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(callableShape, "callableShape");
        returnType = returnType == null ? "" : returnType;
        annotations = Set.copyOf(annotations);
        catches = List.copyOf(catches);
        invocations = List.copyOf(invocations);
        metricTags = List.copyOf(metricTags);
        metricNames = List.copyOf(metricNames);
        calls = List.copyOf(calls);
        throwsInFinally = List.copyOf(throwsInFinally == null ? List.of() : throwsInFinally);
        var copy = new LinkedHashMap<String, Map<String, String>>();
        annotationAttributes.forEach((annotation, attributes) -> copy.put(annotation, Map.copyOf(attributes)));
        annotationAttributes = Map.copyOf(copy);
    }

    /**
     * Full-featured constructor with all parser-populated fields; used by both parsers.
     * The simpler overloads below default {@code throwsInFinally} to empty and
     * {@code declaringTypeIsInterface} to {@code false}.
     */
    public MethodModel(
            MethodId id,
            SourceLocation location,
            Set<String> annotations,
            List<CatchEvidence> catches,
            List<InvocationEvidence> invocations,
            List<MetricTagEvidence> metricTags,
            List<MetricNameEvidence> metricNames,
            List<MethodCall> calls,
            ProxyProfile proxy,
            Map<String, Map<String, String>> annotationAttributes,
            CallableShape callableShape,
            String returnType,
            boolean declaringTypeIsInterface
    ) {
        this(id, location, annotations, catches, invocations, metricTags, metricNames, calls, proxy,
                annotationAttributes, callableShape, returnType, declaringTypeIsInterface, List.of());
    }

    /** Convenience: returnType set, declaringTypeIsInterface and throwsInFinally default. */
    public MethodModel(
            MethodId id,
            SourceLocation location,
            Set<String> annotations,
            List<CatchEvidence> catches,
            List<InvocationEvidence> invocations,
            List<MetricTagEvidence> metricTags,
            List<MetricNameEvidence> metricNames,
            List<MethodCall> calls,
            ProxyProfile proxy,
            Map<String, Map<String, String>> annotationAttributes,
            CallableShape callableShape,
            String returnType
    ) {
        this(id, location, annotations, catches, invocations, metricTags, metricNames, calls, proxy,
                annotationAttributes, callableShape, returnType, false, List.of());
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
            ProxyProfile proxy,
            Map<String, Map<String, String>> annotationAttributes
    ) {
        this(id, location, annotations, catches, invocations, metricTags, metricNames, calls, proxy,
                annotationAttributes, CallableShape.fixed(id.parameterTypes().size()), "", false, List.of());
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
        this(id, location, annotations, catches, invocations, metricTags, metricNames, calls, proxy, Map.of(),
                CallableShape.fixed(id.parameterTypes().size()), "", false, List.of());
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

    /**
     * Synthetic annotation listing, in its {@code names} attribute (comma separated), the variables the method
     * visibly checks for null: compared with {@code null}, used as the receiver of a Kotlin safe call, or on the left
     * of an elvis. Rules that look for an unguarded dereference use it, because a {@code == null} test is not an
     * invocation and would otherwise be invisible.
     */
    public static final String NULL_CHECKED_ANNOTATION = "NullChecked";

    /** The method's cyclomatic complexity as recorded by the parsers, or empty when it is unknown. */
    public Optional<Integer> cyclomaticComplexity() {
        return annotationAttribute(CYCLOMATIC_ANNOTATION, "value").flatMap(value -> {
            try {
                return Optional.of(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        });
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
