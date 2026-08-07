package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared syntax-level signals used by the observability rules.
 *
 * <p>The rules in this package stay deterministic: they only read what the analyzer already
 * extracted from the source (receiver names, method names, argument text, annotations). This helper
 * centralises the heuristics so every rule agrees on what "a logger call" or "an exception
 * variable" looks like.</p>
 */
public final class DiagnosticSignals {

    /** Logger methods that report a failure, where the throwable is expected to travel along. */
    public static final Set<String> FAILURE_LOG_METHODS = Set.of("error", "warn", "fatal", "severe");

    /** Every logger method, including the informational levels. */
    public static final Set<String> LOG_METHODS =
            Set.of("error", "warn", "fatal", "severe", "info", "debug", "trace", "log");

    private static final Pattern EXCEPTION_VARIABLE = Pattern.compile(
            "(?i).*\\b(e|ex|exc|err|error|t|throwable|cause|exception|failure)\\b.*");

    private static final Pattern GENERIC_MESSAGE = Pattern.compile(
            "(?i)^[\\s\\p{Punct}]*(erro|error|errore|falha|failed|failure|fail|exception|problema"
                    + "|problem|deu ruim|oops|unexpected error|something went wrong|erro inesperado)"
                    + "[\\s\\p{Punct}]*$");

    private static final Pattern SENSITIVE_TERM = Pattern.compile(
            "(?i).*\\b(password|passwd|senha|secret|token|apikey|api_key|authorization|bearer"
                    + "|credit_?card|cartao|cpf|cnpj|ssn|pin|private_?key|client_?secret"
                    + "|access_?token|refresh_?token)\\b.*");

    private static final Set<String> METRIC_REGISTRATION_METHODS = Set.of(
            "counter", "gauge", "timer", "summary", "register", "more", "longTaskTimer",
            "distributionSummary", "newCounter", "newTimer");

    private DiagnosticSignals() {
    }

    /** True when the invocation looks like a call on an application logger. */
    public static boolean isLoggerCall(InvocationEvidence invocation) {
        if (!LOG_METHODS.contains(invocation.methodName())) return false;
        return looksLikeLogger(invocation);
    }

    /** True when the invocation reports a failure through a logger (error/warn level). */
    public static boolean isFailureLogCall(InvocationEvidence invocation) {
        if (!FAILURE_LOG_METHODS.contains(invocation.methodName())) return false;
        return looksLikeLogger(invocation);
    }

    private static boolean looksLikeLogger(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("logger") || hint.matches(".*\\blog\\b.*") || hint.contains("slf4j");
    }

    /** True when any argument references something that reads like the caught exception. */
    public static boolean mentionsThrowable(List<String> arguments) {
        return arguments.stream().anyMatch(DiagnosticSignals::mentionsThrowable);
    }

    /** True when the expression references something that reads like a throwable. */
    public static boolean mentionsThrowable(String expression) {
        if (expression == null || expression.isBlank()) return false;
        String stripped = stripStringLiterals(expression);
        return EXCEPTION_VARIABLE.matcher(stripped).matches()
                || stripped.contains("getCause")
                || stripped.contains("getStackTrace")
                || stripped.contains("Throwable");
    }

    /** True when the text is a bare, context-free failure message such as {@code "error"}. */
    public static boolean isGenericMessage(String literal) {
        return literal != null && GENERIC_MESSAGE.matcher(literal).matches();
    }

    /** True when the expression carries a word commonly used for sensitive data. */
    public static boolean isSensitive(String expression) {
        return expression != null && SENSITIVE_TERM.matcher(expression).matches();
    }

    /** True when the invocation registers or creates a metric instrument. */
    public static boolean isMetricRegistration(InvocationEvidence invocation) {
        if (!METRIC_REGISTRATION_METHODS.contains(invocation.methodName())) return false;
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("registry") || hint.contains("meter") || hint.contains("metric")
                || hint.contains("counter") || hint.contains("timer") || hint.contains("micrometer");
    }

    /** True when the method carries an annotation with the given simple name. */
    public static boolean hasAnnotation(MethodModel method, String simpleName) {
        return method.annotations().stream().anyMatch(annotation -> matches(annotation, simpleName));
    }

    private static boolean matches(String annotation, String simpleName) {
        String normalized = annotation.startsWith("@") ? annotation.substring(1) : annotation;
        int parenthesis = normalized.indexOf('(');
        if (parenthesis >= 0) normalized = normalized.substring(0, parenthesis);
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0) normalized = normalized.substring(dot + 1);
        return normalized.trim().equalsIgnoreCase(simpleName);
    }

    /** Returns the first string literal in the expression text, or an empty string. */
    public static String firstStringLiteral(String expression) {
        if (expression == null) return "";
        int start = expression.indexOf('"');
        if (start < 0) return "";
        int end = expression.indexOf('"', start + 1);
        return end < 0 ? "" : expression.substring(start + 1, end);
    }

    /** True when the method body contains at least one logger call. */
    public static boolean logsAnything(MethodModel method) {
        return method.invocations().stream().anyMatch(DiagnosticSignals::isLoggerCall);
    }

    /** True when the method body records at least one metric. */
    public static boolean recordsMetric(MethodModel method) {
        return !method.metricNames().isEmpty()
                || !method.metricTags().isEmpty()
                || method.invocations().stream().anyMatch(DiagnosticSignals::isMetricRegistration);
    }

    private static String stripStringLiterals(String expression) {
        return expression.replaceAll("\"[^\"]*\"", "\"\"");
    }
}
