package dev.diagscope.kotlinparser;

import dev.diagscope.core.domain.MetricNameEvidence;
import dev.diagscope.core.domain.MetricTagEvidence;
import dev.diagscope.core.domain.MetricValueProvenance;
import dev.diagscope.core.domain.SourceLocation;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.KtBinaryExpression;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtConstantExpression;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtParenthesizedExpression;
import org.jetbrains.kotlin.psi.KtQualifiedExpression;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;
import org.jetbrains.kotlin.psi.ValueArgument;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Syntax-only Micrometer evidence extraction for Kotlin PSI expressions. */
final class KotlinMetricEvidenceExtractor {
    private static final Set<String> BUILDER_TYPES = Set.of(
            "Counter", "Timer", "Gauge", "DistributionSummary", "LongTaskTimer", "FunctionCounter",
            "FunctionTimer", "TimeGauge", "Metrics"
    );
    private static final Set<String> REGISTRY_TYPES = Set.of(
            "MeterRegistry", "SimpleMeterRegistry", "CompositeMeterRegistry", "PrometheusMeterRegistry",
            "OtlpMeterRegistry", "StatsdMeterRegistry", "LoggingMeterRegistry", "JmxMeterRegistry",
            "GraphiteMeterRegistry", "DatadogMeterRegistry", "NewRelicMeterRegistry", "CloudWatchMeterRegistry",
            "ElasticMeterRegistry", "DynatraceMeterRegistry", "WavefrontMeterRegistry", "SignalFxMeterRegistry"
    );
    private static final Set<String> TAG_FACTORY_TYPES = Set.of("Tag", "Tags");
    private static final Set<String> REGISTRATION_METHODS = Set.of(
            "counter", "timer", "gauge", "summary", "more", "timeGauge", "moreTypes"
    );
    private static final Set<String> TAG_METHODS = Set.of("tag", "tags");
    private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern UNBOUNDED_IDENTIFIER = Pattern.compile(
            "(?i).*(uuid|email|token|requestid|traceid|spanid|correlationid|sessionid)$"
                    + "|.*Id$|(?i)^(id|ids)$"
    );

    private KotlinMetricEvidenceExtractor() {
    }

    /** Declared names visible in the function, used for local provenance classification. */
    record Names(
            Map<String, String> variableTypes,
            Set<String> parameters,
            Set<String> locals,
            Set<String> fields,
            Set<String> constantFields
    ) {
        Names {
            variableTypes = Map.copyOf(variableTypes);
            parameters = Set.copyOf(parameters);
            locals = Set.copyOf(locals);
            fields = Set.copyOf(fields);
            constantFields = Set.copyOf(constantFields);
        }
    }

    static List<MetricTagEvidence> tags(SourceLocation location, KtCallExpression call, Names names) {
        String method = methodName(call);
        List<KtExpression> arguments = arguments(call);
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

    static Optional<MetricNameEvidence> meter(SourceLocation location, KtCallExpression call, Names names) {
        List<KtExpression> arguments = arguments(call);
        if (arguments.isEmpty()) return Optional.empty();
        String method = methodName(call);
        KtExpression name = arguments.getFirst();

        if ("builder".equals(method) && BUILDER_TYPES.contains(simpleScopeName(call))) {
            return Optional.of(new MetricNameEvidence(location, simpleScopeName(call), name.getText(),
                    provenance(name, names)));
        }
        if (REGISTRATION_METHODS.contains(method) && isRegistryReceiver(call, names)) {
            String meterType = Character.toUpperCase(method.charAt(0)) + method.substring(1);
            return Optional.of(new MetricNameEvidence(location, meterType, name.getText(),
                    provenance(name, names)));
        }
        return Optional.empty();
    }

    private static List<KtExpression> arguments(KtCallExpression call) {
        return call.getValueArguments().stream()
                .map(ValueArgument::getArgumentExpression)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static void addPairs(
            List<MetricTagEvidence> target,
            SourceLocation location,
            List<KtExpression> arguments,
            int firstIndex,
            Names names
    ) {
        for (int index = firstIndex; index + 1 < arguments.size(); index += 2) {
            KtExpression key = unwrap(arguments.get(index));
            if (!(key instanceof KtStringTemplateExpression)
                    && !(key instanceof KtNameReferenceExpression)
                    && !(key instanceof KtQualifiedExpression)) {
                continue;
            }
            target.add(tagEvidence(location, key, arguments.get(index + 1), names));
        }
    }

    private static MetricTagEvidence tagEvidence(
            SourceLocation location,
            KtExpression key,
            KtExpression value,
            Names names
    ) {
        String tagName = key instanceof KtStringTemplateExpression template && !template.hasInterpolation()
                ? stringLiteral(template.getText()) : key.getText();
        MetricValueProvenance provenance = provenance(value, names);
        String valueType = valueType(value, names);
        boolean uuid = "UUID".equals(simpleTypeName(valueType))
                || value.getText().toLowerCase(Locale.ROOT).contains("uuid");
        boolean unbounded = uuid
                || (!provenance.bounded() && mentionsUnboundedIdentifier(value))
                || provenance == MetricValueProvenance.CONCATENATION;
        return new MetricTagEvidence(location, tagName, value.getText(), true, uuid, unbounded,
                provenance, valueType);
    }

    static MetricValueProvenance provenance(KtExpression expression, Names names) {
        KtExpression current = unwrap(expression);
        if (current instanceof KtStringTemplateExpression template) {
            return template.hasInterpolation()
                    ? MetricValueProvenance.CONCATENATION : MetricValueProvenance.LITERAL;
        }
        if (current instanceof KtConstantExpression) {
            return MetricValueProvenance.LITERAL;
        }
        if (current instanceof KtBinaryExpression binary
                && "+".equals(binary.getOperationReference().getText())) {
            return MetricValueProvenance.CONCATENATION;
        }
        if (current instanceof KtCallExpression) {
            return MetricValueProvenance.METHOD_CALL;
        }
        if (current instanceof KtQualifiedExpression qualified) {
            KtExpression selector = qualified.getSelectorExpression();
            if (selector instanceof KtCallExpression) return MetricValueProvenance.METHOD_CALL;
            String field = selector == null ? "" : selector.getText();
            if (!CONSTANT_NAME.matcher(field).matches()) return MetricValueProvenance.FIELD;
            String receiver = qualified.getReceiverExpression().getText();
            boolean typeScope = !receiver.isBlank() && Character.isUpperCase(receiver.charAt(0));
            return typeScope ? MetricValueProvenance.ENUM_CONSTANT : MetricValueProvenance.CONSTANT_FIELD;
        }
        if (current instanceof KtNameReferenceExpression nameExpression) {
            String name = nameExpression.getReferencedName();
            if (names.constantFields().contains(name)) return MetricValueProvenance.CONSTANT_FIELD;
            if (names.parameters().contains(name)) return MetricValueProvenance.PARAMETER;
            if (names.locals().contains(name)) return MetricValueProvenance.LOCAL_VARIABLE;
            if (names.fields().contains(name)) return MetricValueProvenance.FIELD;
        }
        return MetricValueProvenance.UNKNOWN;
    }

    private static boolean mentionsUnboundedIdentifier(KtExpression expression) {
        if (expression instanceof KtNameReferenceExpression reference
                && unbounded(reference.getReferencedName())) {
            return true;
        }
        if (expression instanceof KtCallExpression call && unbounded(methodName(call))) {
            return true;
        }
        return PsiTreeUtil.findChildrenOfType(expression, KtNameReferenceExpression.class).stream()
                .map(KtNameReferenceExpression::getReferencedName)
                .anyMatch(KotlinMetricEvidenceExtractor::unbounded)
                || PsiTreeUtil.findChildrenOfType(expression, KtCallExpression.class).stream()
                .map(KotlinMetricEvidenceExtractor::methodName)
                .anyMatch(KotlinMetricEvidenceExtractor::unbounded);
    }

    private static boolean unbounded(String name) {
        String normalized = name.replace("_", "").replace("-", "");
        return UNBOUNDED_IDENTIFIER.matcher(normalized).matches();
    }

    private static String valueType(KtExpression expression, Names names) {
        KtExpression current = unwrap(expression);
        if (current instanceof KtStringTemplateExpression) return "String";
        if (current instanceof KtConstantExpression constant) {
            String text = constant.getText();
            if ("true".equals(text) || "false".equals(text)) return "Boolean";
            if (text.startsWith("'") && text.endsWith("'")) return "Char";
            return "Number";
        }
        if (current instanceof KtNameReferenceExpression nameExpression) {
            return names.variableTypes().getOrDefault(nameExpression.getReferencedName(), "");
        }
        if (current instanceof KtQualifiedExpression qualified) {
            String receiver = qualified.getReceiverExpression().getText();
            if ("UUID".equals(simpleTypeName(receiver))) return "UUID";
            return names.variableTypes().getOrDefault(receiver, "");
        }
        return "";
    }

    private static KtExpression unwrap(KtExpression expression) {
        KtExpression current = expression;
        while (current instanceof KtParenthesizedExpression parenthesized
                && parenthesized.getExpression() != null) {
            current = parenthesized.getExpression();
        }
        return current;
    }

    private static boolean isMicrometerReceiver(KtCallExpression call, Names names) {
        if (isRegistryReceiver(call, names)) return true;
        String rawScope = scope(call);
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

    private static boolean isRegistryReceiver(KtCallExpression call, Names names) {
        String rawScope = scope(call);
        if (rawScope.isBlank()) return false;
        if ("Metrics".equals(rawScope)) return true;
        String declared = names.variableTypes().get(rawScope);
        if (declared == null && rawScope.startsWith("this.")) {
            declared = names.variableTypes().get(rawScope.substring("this.".length()));
        }
        String simple = simpleTypeName(declared == null ? "" : declared);
        return REGISTRY_TYPES.contains(simple)
                || (declared != null && declared.startsWith("io.micrometer.") && simple.endsWith("MeterRegistry"));
    }

    private static boolean isMicrometerBuilderType(String declaredType) {
        String normalized = baseTypeName(declaredType);
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot < 0 || !"Builder".equals(normalized.substring(lastDot + 1))) return false;
        return BUILDER_TYPES.contains(simpleTypeName(normalized.substring(0, lastDot)));
    }

    private static String methodName(KtCallExpression call) {
        return call.getCalleeExpression() == null ? "" : call.getCalleeExpression().getText();
    }

    private static String scope(KtCallExpression call) {
        PsiElement parent = call.getParent();
        if (parent instanceof KtQualifiedExpression qualified && qualified.getSelectorExpression() == call) {
            return qualified.getReceiverExpression().getText();
        }
        return "";
    }

    private static String simpleScopeName(KtCallExpression call) {
        return simpleTypeName(scope(call));
    }

    private static String simpleTypeName(String type) {
        String normalized = baseTypeName(type);
        int lastDot = normalized.lastIndexOf('.');
        return lastDot >= 0 ? normalized.substring(lastDot + 1) : normalized;
    }

    private static String baseTypeName(String type) {
        String normalized = type == null ? "" : type.trim();
        int generic = normalized.indexOf('<');
        if (generic >= 0) normalized = normalized.substring(0, generic);
        while (normalized.endsWith("?")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String stringLiteral(String text) {
        String value = text.trim();
        if (value.startsWith("\"\"\"") && value.endsWith("\"\"\"") && value.length() >= 6) {
            return value.substring(3, value.length() - 3);
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
