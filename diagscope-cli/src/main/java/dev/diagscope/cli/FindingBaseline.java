package dev.diagscope.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.domain.Finding;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Reads, writes, and applies the stable-fingerprint baseline used by CI scans. */
final class FindingBaseline {
    static final String DEFAULT_FILE_NAME = "diagscope-baseline.json";
    static final String SCHEMA_VERSION = "1.1";
    private static final Set<String> SUPPORTED_SCHEMAS = Set.of("1.0", SCHEMA_VERSION);
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    BaselineState read(Path file) throws IOException {
        JsonNode document;
        try (var input = Files.newInputStream(file)) {
            document = mapper.readTree(input);
        } catch (JsonProcessingException exception) {
            throw invalid(file, "malformed JSON: " + exception.getOriginalMessage());
        }
        if (document == null || !document.isObject()) throw invalid(file, "root must be a JSON object");
        String schema = document.path("schemaVersion").asText();
        if (!SUPPORTED_SCHEMAS.contains(schema)) {
            throw invalid(file, "unsupported schemaVersion; expected one of " + SUPPORTED_SCHEMAS);
        }
        if (document.path("fingerprintVersion").asInt(-1) != Finding.FINGERPRINT_VERSION) {
            throw invalid(file, "unsupported fingerprintVersion; expected " + Finding.FINGERPRINT_VERSION);
        }
        Map<String, BaselineEntry> active = entries(file, document.path("findings"), "findings", false);
        Map<String, BaselineEntry> removed = document.has("removedFindings")
                ? entries(file, document.path("removedFindings"), "removedFindings", true) : Map.of();
        var migrations = new TreeMap<String, String>();
        JsonNode migrationNode = document.path("migrations");
        if (!migrationNode.isMissingNode()) {
            if (!migrationNode.isObject()) throw invalid(file, "migrations must be an object keyed by old fingerprint");
            migrationNode.fields().forEachRemaining(entry -> {
                requireFingerprint(file, entry.getKey(), "migration source");
                String target = entry.getValue().asText();
                requireFingerprint(file, target, "migration target");
                if (entry.getKey().equals(target)) throw invalid(file, "migration source and target must differ");
                migrations.put(entry.getKey(), target);
            });
        }
        return new BaselineState(active, removed, migrations);
    }

    Lifecycle write(Path file, AnalysisResult result, BaselineState previous,
                    Map<String, String> requestedMigrations, boolean pruneRemoved) throws IOException {
        Path parent = file.getParent();
        if (parent == null) throw new IllegalArgumentException("Baseline path must have a parent directory: " + file);
        Files.createDirectories(parent);
        BaselineState next = evolve(result, previous, requestedMigrations, pruneRemoved);

        Path temporary = Files.createTempFile(parent, "." + file.getFileName() + '-', ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                mapper.writeValue(output, document(result, next));
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        long migrated = next.removed().values().stream().filter(entry -> !entry.migratedTo().isBlank()).count();
        return new Lifecycle(next.removed().size(), Math.toIntExact(migrated));
    }

    BaselineApplication suppress(AnalysisResult result, BaselineState baseline) {
        Set<String> accepted = baseline.acceptedFingerprints();
        var visibleResult = AnalysisResultFilter.retain(result,
                finding -> !accepted.contains(finding.fingerprint()));
        int suppressed = result.findings().size() - visibleResult.findings().size();
        return new BaselineApplication(visibleResult, suppressed);
    }

    private static BaselineState evolve(AnalysisResult result, BaselineState previous,
                                        Map<String, String> requestedMigrations, boolean pruneRemoved) {
        var current = new TreeMap<String, BaselineEntry>();
        for (Finding finding : result.findings()) {
            current.put(finding.fingerprint(), BaselineEntry.from(finding));
        }
        var migrations = new TreeMap<>(previous.migrations());
        requestedMigrations.forEach((source, target) -> {
            validateFingerprint(source, "migration source");
            validateFingerprint(target, "migration target");
            if (source.equals(target)) throw new IllegalArgumentException("Baseline migration source and target must differ");
            if (!previous.active().containsKey(source) && !previous.removed().containsKey(source)) {
                throw new IllegalArgumentException("Baseline migration source is not present in baseline: " + source);
            }
            if (!current.containsKey(target)) {
                throw new IllegalArgumentException("Baseline migration target is not a current finding: " + target);
            }
            if (current.containsKey(source)) {
                throw new IllegalArgumentException("Baseline migration source is still a current finding: " + source);
            }
            String existing = migrations.putIfAbsent(source, target);
            if (existing != null && !existing.equals(target)) {
                throw new IllegalArgumentException("Baseline migration source already maps to " + existing);
            }
        });

        var removed = pruneRemoved ? new TreeMap<String, BaselineEntry>() : new TreeMap<>(previous.removed());
        previous.active().forEach((fingerprint, entry) -> {
            if (current.containsKey(fingerprint)) return;
            String migratedTo = migrations.getOrDefault(fingerprint, "");
            removed.put(fingerprint, entry.withMigratedTo(current.containsKey(migratedTo) ? migratedTo : ""));
        });
        current.keySet().forEach(removed::remove);
        return new BaselineState(current, removed, migrations);
    }

    private static Map<String, BaselineEntry> entries(Path file, JsonNode node, String label, boolean removed) {
        if (!node.isObject()) throw invalid(file, label + " must be an object keyed by fingerprint");
        var result = new TreeMap<String, BaselineEntry>();
        node.fields().forEachRemaining(field -> {
            requireFingerprint(file, field.getKey(), label + " key");
            JsonNode value = field.getValue();
            if (!value.isObject()) throw invalid(file, label + " values must be objects");
            String migratedTo = value.path("migratedTo").asText("");
            if (!migratedTo.isBlank()) requireFingerprint(file, migratedTo, "migratedTo");
            result.put(field.getKey(), new BaselineEntry(value.path("ruleId").asText(),
                    value.path("file").asText(), value.path("message").asText(), removed ? migratedTo : ""));
        });
        return result;
    }

    private static Map<String, Object> document(AnalysisResult result, BaselineState state) {
        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", SCHEMA_VERSION);
        document.put("fingerprintVersion", Finding.FINGERPRINT_VERSION);
        var tool = new LinkedHashMap<String, Object>();
        tool.put("name", "DiagScope");
        tool.put("version", BuildInfo.version());
        document.put("tool", tool);
        document.put("project", result.projectName());
        document.put("findings", entryDocument(state.active(), false));
        document.put("removedFindings", entryDocument(state.removed(), true));
        document.put("migrations", new TreeMap<>(state.migrations()));
        return document;
    }

    private static Map<String, Object> entryDocument(Map<String, BaselineEntry> entries, boolean removed) {
        var result = new TreeMap<String, Object>();
        entries.forEach((fingerprint, baseline) -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("ruleId", baseline.ruleId());
            entry.put("file", baseline.file());
            entry.put("message", baseline.message());
            if (removed) entry.put("status", "REMOVED");
            if (!baseline.migratedTo().isBlank()) entry.put("migratedTo", baseline.migratedTo());
            result.put(fingerprint, entry);
        });
        return result;
    }

    private static void requireFingerprint(Path file, String fingerprint, String label) {
        if (!FINGERPRINT.matcher(fingerprint).matches()) throw invalid(file, label + " must be a sha256 fingerprint");
    }

    private static void validateFingerprint(String fingerprint, String label) {
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("Baseline " + label + " must be a sha256 fingerprint: " + fingerprint);
        }
    }

    private static IllegalArgumentException invalid(Path file, String detail) {
        return new IllegalArgumentException("Invalid baseline " + file + ": " + detail);
    }

    record BaselineState(Map<String, BaselineEntry> active, Map<String, BaselineEntry> removed,
                         Map<String, String> migrations) {
        BaselineState {
            active = immutableSorted(active);
            removed = immutableSorted(removed);
            migrations = immutableSorted(migrations);
        }

        static BaselineState empty() { return new BaselineState(Map.of(), Map.of(), Map.of()); }

        Set<String> acceptedFingerprints() {
            var accepted = new TreeSet<>(active.keySet());
            accepted.addAll(migrations.values());
            return Set.copyOf(accepted);
        }

        private static <V> Map<String, V> immutableSorted(Map<String, V> source) {
            return Collections.unmodifiableMap(new TreeMap<>(source));
        }
    }

    record BaselineEntry(String ruleId, String file, String message, String migratedTo) {
        static BaselineEntry from(Finding finding) {
            return new BaselineEntry(finding.ruleId(), Finding.normalizedPath(finding.location()),
                    finding.message(), "");
        }

        BaselineEntry withMigratedTo(String target) { return new BaselineEntry(ruleId, file, message, target); }
    }

    record BaselineApplication(AnalysisResult result, int suppressedFindings) {}
    record Lifecycle(int removedFindings, int migratedFindings) {}
}
