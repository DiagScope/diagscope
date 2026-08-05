package dev.diagscope.javaparser;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import dev.diagscope.core.domain.MetricNameEvidence;
import dev.diagscope.core.domain.MetricTagEvidence;
import dev.diagscope.core.domain.MetricValueProvenance;
import dev.diagscope.core.domain.SourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Syntax-only recognition of Micrometer meter registrations and tags.
 *
 * <p>The extractor never claims runtime semantics: it reports what the local syntax proves about
 * the receiver, the meter name, and each tag key, value expression, value type, and provenance.
 */
final class MetricEvidenceExtractor {
    /** Static builder entrypoints such as {@code Counter.builder("x")}. */
    private static final Set<String> BUILDER_TYPES = Set.of(
            "Counter", "Timer", "Gauge", "DistributionSummary", "LongTaskTimer", "FunctionCounter",
            "FunctionTimer", "TimeGauge", "Metrics"
    );
    /** Micrometer registry types; matched exactly so unrelated {@code *MeterRegistry}-ish names are ignored. */
    private static final Set<String> REGISTRY_TYPES = Set.of(
            "MeterRegistry", "SimpleMeterRegistry", "CompositeMeterRegistry", "PrometheusMeterRegistry",
            "OtlpMeterRegistry", "StatsdMeterRegistry", "LoggingMeterRegistry", "JmxMeterRegistry",
            "GraphiteMeterRegistry", "DatadogMeterRegistry", "NewRelicMeterRegistry", "CloudWatchMeterRegistry",
            "ElasticMeterRegistry", "DynatraceMeterRegistry", "WavefrontMeterRegistry", "SignalFxMeterRegistry"
    );
    /** Static tag factories such as {@code Tags.of("k", v)}. */
    private static final Set<String> TAG_FACTORY_TYPES = Set.of("Tag", "Tags");
    /** Registration methods available on a {@code MeterRegistry} or on {@code Metrics}. */
    private static final Set<String> REGISTRATION_METHODS = Set.of(
            "counter", "timer", "gauge", "summary", "more", "timeGauge", "moreTypes"
    );
    private static final Set<String> TAG_METHODS = Set.of("tag", "tags");
    private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern UNBOUNDED_IDENTIFIER = Pattern.compile(
            "(?i).*(uuid|email|token|requestid|traceid|spanid|correlationid|sessionid)$"
                    + "|.*Id$|(?i)^(id|ids)$"
    );

    private MetricEvidenceExtractor() {}

    /** Declared names visible inside the analyzed method, used for provenance classification. */
    record Names(
            Map<String, String> variableTypes,
            Set<String> parameters,
            Set<String> locals,
            Set<String> fields,
            Set<String> constantFields
    ) {}

    /** Returns every tag this call declares, or an empty list when the call is not Micrometer syntax. */
    static List<MetricTagEvidence> tags(SourceLocation location, MethodCallExpr call, Names names) {
        String method = call.getNameAsString();
        List<Expression> arguments = List.copyOf(call.getArguments());
        var tags = new ArrayList<MetricTagEvidence>();

        if (TAG_METHODS.contains(method) && isMicrometerReceiver(call, names)) {
            addPairs(tags, location, arguments, 0, names);
            return List.copyOf(tags);
        }
        if ("of".equals(method) && TAG_FACTORY_TYPES.contains(simpleScopeName(call))) {
            addPairs(tags, location, arguments, 0, names);
            return List.copyOf(tags);
        }
        if (REGISTRATION_METHODS.contains(method) && isRegistryReceiver(call, names) && arguments.size() > 2) {
            addPairs(tags, location, arguments, 1, names);
        }
        return List.copyOf(tags);
    }

    /** Returns the meter registration this call performs, when the syntax proves one. */
    static Optional<MetricNameEvidence> meter(SourceLocation location, MethodCallExpr call, Names names) {
        String method = call.getNameAsString();
        if (call.getArguments().isEmpty()) return Optional.empty();
        Expression name = call.getArgument(0);

        if ("builder".equals(method) && BUILDER_TYPES.contains(simpleScopeName(call))) {
            return Optional.of(new MetricNameEvidence(location, simpleScopeName(call), name.toString(),
                    provenance(name, names)));
        }
        if (REGISTRATION_METHODS.contains(method) && isRegistryReceiver(call, names)) {
            String meterType = Character.toUpperCase(method.charAt(0)) + method.substring(1);
            return Optional.of(new MetricNameEvidence(location, meterType, name.toString(),
                    provenance(name, names)));
        }
        return Optional.empty();
    }

    private static void addPairs(
            List<MetricTagEvidence> target,
            SourceLocation location,
            List<Expression> arguments,
            int firstIndex,
            Names names
    ) {
        for (int index = firstIndex; index + 1 < arguments.size(); index += 2) {
            Expression key = arguments.get(index);
            if (!(key instanceof StringLiteralExpr) && !(key instanceof NameExpr) && !(key instanceof FieldAccessExpr)) {
                continue;
            }
            target.add(tagEvidence(location, key, arguments.get(index + 1), names));
        }
    }

    private static MetricTagEvidence tagEvidence(
            SourceLocation location,
            Expression key,
            Expression value,
            Names names
    ) {
        String tagName = key instanceof StringLiteralExpr literal ? literal.asString() : key.toString();
        MetricValueProvenance provenance = provenance(value, names);
        String valueType = valueType(value, names);
        boolean uuid = "UUID".equals(simpleTypeName(valueType))
                || value.toString().toLowerCase(Locale.ROOT).contains("uuid");
        boolean unbounded = uuid
                || (!provenance.bounded() && mentionsUnboundedIdentifier(value))
                || provenance == MetricValueProvenance.CONCATENATION;
        return new MetricTagEvidence(location, tagName, value.toString(), true, uuid, unbounded,
                provenance, valueType);
    }

    /** Classifies where the expression value comes from, using only local syntax. */
    static MetricValueProvenance provenance(Expression expression, Names names) {
        Expression current = unwrap(expression);
        if (current instanceof StringLiteralExpr || current instanceof CharLiteralExpr
                || current instanceof BooleanLiteralExpr || current instanceof LiteralExpr) {
            return MetricValueProvenance.LITERAL;
        }
        if (current instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            return MetricValueProvenance.CONCATENATION;
        }
        if (current instanceof MethodCallExpr) {
            return MetricValueProvenance.METHOD_CALL;
        }
        if (current instanceof FieldAccessExpr fieldAccess) {
            String field = fieldAccess.getNameAsString();
            if (!CONSTANT_NAME.matcher(field).matches()) return MetricValueProvenance.FIELD;
            String scope = fieldAccess.getScope().toString();
            boolean typeScope = !scope.isEmpty() && Character.isUpperCase(scope.charAt(0)) && !scope.contains("(");
            return typeScope ? MetricValueProvenance.ENUM_CONSTANT : MetricValueProvenance.CONSTANT_FIELD;
        }
        if (current instanceof NameExpr nameExpression) {
            String name = nameExpression.getNameAsString();
            if (names.constantFields().contains(name)) return MetricValueProvenance.CONSTANT_FIELD;
            if (names.parameters().contains(name)) return MetricValueProvenance.PARAMETER;
            if (names.locals().contains(name)) return MetricValueProvenance.LOCAL_VARIABLE;
            if (names.fields().contains(name)) return MetricValueProvenance.FIELD;
        }
        return MetricValueProvenance.UNKNOWN;
    }

    private static boolean mentionsUnboundedIdentifier(Expression expression) {
        return expression.findAll(NameExpr.class).stream()
                .map(NameExpr::getNameAsString)
                .map(name -> name.replace("_", "").replace("-", ""))
                .anyMatch(name -> UNBOUNDED_IDENTIFIER.matcher(name).matches())
                || expression.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .anyMatch(name -> UNBOUNDED_IDENTIFIER.matcher(name).matches());
    }

    private static String valueType(Expression expression, Names names) {
        Expression current = unwrap(expression);
        if (current instanceof StringLiteralExpr) return "String";
        if (current instanceof BooleanLiteralExpr) return "boolean";
        if (current instanceof NameExpr nameExpression) {
            return names.variableTypes().getOrDefault(nameExpression.getNameAsString(), "");
        }
        if (current instanceof MethodCallExpr call) {
            return call.getScope()
                    .filter(NameExpr.class::isInstance)
                    .map(scope -> names.variableTypes().getOrDefault(scope.toString(), ""))
                    .orElse("");
        }
        return "";
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current instanceof EnclosedExpr enclosed) {
            current = enclosed.getInner();
        }
        return current;
    }

    /** Returns whether the receiver is provably a Micrometer registry, builder, or tag holder. */
    static boolean isMicrometerReceiver(MethodCallExpr call, Names names) {
        if (isRegistryReceiver(call, names)) return true;
        String rawScope = call.getScope().map(Object::toString).orElse("");
        String receiverType = simpleTypeName(names.variableTypes().getOrDefault(rawScope, ""));
        if (BUILDER_TYPES.contains(receiverType) || TAG_FACTORY_TYPES.contains(receiverType)) return true;
        if (isMicrometerBuilderType(names.variableTypes().getOrDefault(rawScope, ""))) return true;
        for (String builder : BUILDER_TYPES) {
            if (rawScope.startsWith(builder + ".builder") || rawScope.contains(builder + ".builder(")) return true;
        }
        for (String factory : TAG_FACTORY_TYPES) {
            if (rawScope.startsWith(factory + ".of")) return true;
        }
        return rawScope.contains(".tags(") || rawScope.contains(".tag(");
    }

    /** Returns whether the receiver is provably a {@code MeterRegistry} or the {@code Metrics} facade. */
    static boolean isRegistryReceiver(MethodCallExpr call, Names names) {
        String rawScope = call.getScope().map(Object::toString).orElse("");
        if (rawScope.isEmpty()) return false;
        if ("Metrics".equals(rawScope)) return true;
        String declared = names.variableTypes().get(rawScope);
        if (declared == null && rawScope.startsWith("this.")) {
            declared = names.variableTypes().get(rawScope.substring("this.".length()));
        }
        String simple = simpleTypeName(declared == null ? "" : declared);
        return REGISTRY_TYPES.contains(simple)
                || (declared != null && declared.startsWith("io.micrometer.") && simple.endsWith("MeterRegistry"));
    }

    /** Recognizes nested Micrometer builder types such as {@code Counter.Builder}, not any {@code *Builder}. */
    private static boolean isMicrometerBuilderType(String declaredType) {
        String normalized = declaredType == null ? "" : declaredType.trim();
        int generic = normalized.indexOf('<');
        if (generic >= 0) normalized = normalized.substring(0, generic);
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot < 0 || !"Builder".equals(normalized.substring(lastDot + 1))) return false;
        return BUILDER_TYPES.contains(simpleTypeName(normalized.substring(0, lastDot)));
    }

    private static String simpleScopeName(MethodCallExpr call) {
        return simpleTypeName(call.getScope().map(Object::toString).orElse(""));
    }

    private static String simpleTypeName(String type) {
        String normalized = type == null ? "" : type.trim();
        int generic = normalized.indexOf('<');
        if (generic >= 0) normalized = normalized.substring(0, generic);
        int lastDot = normalized.lastIndexOf('.');
        return lastDot >= 0 ? normalized.substring(lastDot + 1) : normalized;
    }
}
