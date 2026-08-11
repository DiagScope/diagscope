package dev.diagscope.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Severity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Strict adapter from the versioned project YAML file to the parser-neutral analysis policy. */
final class ProjectConfigurationLoader {
    static final String DEFAULT_FILE_NAME = "diagscope.yml";
    static final String SCHEMA_VERSION = "1.0";

    private final YAMLMapper mapper = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    LoadedConfiguration load(Path file) throws IOException {
        ConfigurationDocument document;
        try (var input = Files.newInputStream(file)) {
            document = mapper.readValue(input, ConfigurationDocument.class);
        } catch (JsonProcessingException exception) {
            throw invalid(file, exception.getOriginalMessage());
        }
        if (document == null) throw invalid(file, "document must not be empty");
        if (!SCHEMA_VERSION.equals(document.schemaVersion())) {
            throw invalid(file, "unsupported schemaVersion; expected " + SCHEMA_VERSION);
        }

        var disabledRules = new TreeSet<String>();
        var severities = new TreeMap<String, Severity>();
        values(document.rules()).forEach((ruleId, rule) -> {
            if (!RuleCatalog.all().containsKey(ruleId)) {
                throw invalid(file, "unknown rule id: " + ruleId);
            }
            if (rule == null) throw invalid(file, "rule policy must not be null: " + ruleId);
            if (Boolean.FALSE.equals(rule.enabled())) disabledRules.add(ruleId);
            if (rule.severity() != null) severities.put(ruleId, rule.severity());
        });

        var customEntrypoints = new EnumMap<EntrypointType, Set<String>>(EntrypointType.class);
        values(document.customEntrypointAnnotations()).forEach((configuredType, annotations) -> {
            EntrypointType type;
            try {
                type = EntrypointType.valueOf(configuredType.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (RuntimeException exception) {
                throw invalid(file, "unknown entrypoint type: " + configuredType);
            }
            var simpleAnnotations = new TreeSet<String>();
            values(annotations).forEach(annotation -> simpleAnnotations.add(simpleName(annotation)));
            customEntrypoints.put(type, Set.copyOf(simpleAnnotations));
        });

        try {
            var policy = new AnalysisPolicy(
                    Set.copyOf(values(document.ignoredPaths())),
                    Set.copyOf(values(document.customLoggerTypes())),
                    customEntrypoints,
                    Set.copyOf(values(document.sensitiveFields())),
                    disabledRules,
                    severities
            );
            return new LoadedConfiguration(policy, file, waivers(file, document.suppressions()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid(file, exception.getMessage());
        }
    }

    static LoadedConfiguration defaults() {
        return new LoadedConfiguration(AnalysisPolicy.defaults(), null, List.of());
    }

    private static List<FindingSuppressions.Waiver> waivers(Path file, List<SuppressionEntry> entries) {
        var waivers = new java.util.ArrayList<FindingSuppressions.Waiver>();
        for (SuppressionEntry entry : values(entries)) {
            if (entry == null) throw invalid(file, "suppression entry must not be null");
            java.time.LocalDate expires = null;
            if (entry.expires() != null && !entry.expires().isBlank()) {
                try {
                    expires = java.time.LocalDate.parse(entry.expires().trim());
                } catch (RuntimeException exception) {
                    throw invalid(file, "suppression expires must be an ISO date (YYYY-MM-DD): " + entry.expires());
                }
            }
            try {
                waivers.add(new FindingSuppressions.Waiver(
                        entry.fingerprint() == null ? "" : entry.fingerprint().trim(),
                        entry.reason() == null ? "" : entry.reason().trim(),
                        expires));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw invalid(file, exception.getMessage());
            }
        }
        return List.copyOf(waivers);
    }

    private static String simpleName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("custom entrypoint annotation must not be blank");
        }
        String normalized = value.trim();
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }

    private static <T> List<T> values(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <K, V> Map<K, V> values(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    private static IllegalArgumentException invalid(Path file, String detail) {
        return new IllegalArgumentException("Invalid configuration " + file + ": " + detail);
    }

    record LoadedConfiguration(AnalysisPolicy policy, Path source, List<FindingSuppressions.Waiver> waivers) {
    }

    private record ConfigurationDocument(
            String schemaVersion,
            Map<String, RulePolicy> rules,
            List<String> ignoredPaths,
            List<String> sensitiveFields,
            List<String> customLoggerTypes,
            Map<String, List<String>> customEntrypointAnnotations,
            List<SuppressionEntry> suppressions
    ) {
    }

    private record SuppressionEntry(String fingerprint, String reason, String expires) {
    }

    private record RulePolicy(Boolean enabled, Severity severity) {
    }
}
