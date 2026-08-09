package dev.diagscope.core.application;

import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Severity;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Effective project policy loaded from configuration, independent of its serialization format. */
public record AnalysisPolicy(
        Set<String> ignoredPathPatterns,
        Set<String> customLoggerTypes,
        Map<EntrypointType, Set<String>> customEntrypointAnnotations,
        Set<String> sensitiveFieldNames,
        Set<String> disabledRules,
        Map<String, Severity> severityOverrides
) {
    public AnalysisPolicy {
        ignoredPathPatterns = normalizedValues(ignoredPathPatterns, "ignoredPathPatterns");
        ignoredPathPatterns.forEach(AnalysisPolicy::validatePathPattern);
        customLoggerTypes = normalizedValues(customLoggerTypes, "customLoggerTypes");
        sensitiveFieldNames = normalizedValues(sensitiveFieldNames, "sensitiveFieldNames");
        disabledRules = normalizedValues(disabledRules, "disabledRules");

        var entrypoints = new EnumMap<EntrypointType, Set<String>>(EntrypointType.class);
        if (customEntrypointAnnotations == null) {
            throw new NullPointerException("customEntrypointAnnotations");
        }
        customEntrypointAnnotations.forEach((type, annotations) ->
                entrypoints.put(type, normalizedValues(annotations, "customEntrypointAnnotations." + type)));
        customEntrypointAnnotations = Collections.unmodifiableMap(entrypoints);

        if (severityOverrides == null) throw new NullPointerException("severityOverrides");
        var severities = new TreeMap<String, Severity>();
        severityOverrides.forEach((rule, severity) -> {
            String normalized = normalizedValue(rule, "severityOverrides rule");
            if (severity == null) throw new NullPointerException("severityOverrides." + normalized);
            severities.put(normalized, severity);
        });
        severityOverrides = Collections.unmodifiableMap(severities);
    }

    public static AnalysisPolicy defaults() {
        return new AnalysisPolicy(Set.of(), Set.of(), Map.of(), Set.of(), Set.of(), Map.of());
    }

    /** Matches a root-relative source path against the configured portable glob expressions. */
    public boolean ignores(Path relativePath) {
        String normalized = relativePath.normalize().toString().replace('\\', '/');
        return ignoredPathPatterns.stream().anyMatch(glob -> globPattern(glob).matcher(normalized).matches());
    }

    /** True when syntax identifies the receiver as one of the configured logger types. */
    public boolean isCustomLogger(String scope, String receiverType) {
        String receiver = simpleName(receiverType);
        String scopedType = simpleName(scope);
        return customLoggerTypes.stream().map(AnalysisPolicy::simpleName)
                .anyMatch(type -> type.equalsIgnoreCase(receiver) || type.equalsIgnoreCase(scopedType));
    }

    public Set<String> customEntrypointAnnotations(EntrypointType type) {
        return customEntrypointAnnotations.getOrDefault(type, Set.of());
    }

    private static Set<String> normalizedValues(Set<String> values, String name) {
        if (values == null) throw new NullPointerException(name);
        var normalized = new TreeSet<String>();
        values.forEach(value -> normalized.add(normalizedValue(value, name)));
        return Collections.unmodifiableSet(normalized);
    }

    private static String normalizedValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not contain blank values");
        }
        return value.trim();
    }

    private static void validatePathPattern(String value) {
        String normalized = value.replace('\\', '/');
        boolean traversal = java.util.Arrays.stream(normalized.split("/"))
                .anyMatch(".."::equals);
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*") || traversal) {
            throw new IllegalArgumentException("Ignored path pattern must be project-relative: " + value);
        }
        globPattern(value);
    }

    private static Pattern globPattern(String glob) {
        glob = glob.replace('\\', '/');
        var regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                if (doubleStar) {
                    index++;
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                if (".()[]{}+$^|\\".indexOf(current) >= 0) regex.append('\\');
                regex.append(current == '\\' ? '/' : current);
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static String simpleName(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().replace("?", "").replace("!", "");
        int generic = normalized.indexOf('<');
        if (generic >= 0) normalized = normalized.substring(0, generic);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }
}
