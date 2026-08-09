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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Reads, writes, and applies the stable-fingerprint baseline used by CI scans. */
final class FindingBaseline {
    static final String DEFAULT_FILE_NAME = "diagscope-baseline.json";
    static final String SCHEMA_VERSION = "1.0";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    Set<String> read(Path file) throws IOException {
        JsonNode document;
        try (var input = Files.newInputStream(file)) {
            document = mapper.readTree(input);
        } catch (JsonProcessingException exception) {
            throw invalid(file, "malformed JSON: " + exception.getOriginalMessage());
        }
        if (document == null || !document.isObject()) {
            throw invalid(file, "root must be a JSON object");
        }
        if (!SCHEMA_VERSION.equals(document.path("schemaVersion").asText())) {
            throw invalid(file, "unsupported schemaVersion; expected " + SCHEMA_VERSION);
        }
        if (document.path("fingerprintVersion").asInt(-1) != Finding.FINGERPRINT_VERSION) {
            throw invalid(file, "unsupported fingerprintVersion; expected " + Finding.FINGERPRINT_VERSION);
        }

        JsonNode findings = document.path("findings");
        if (!findings.isObject()) {
            throw invalid(file, "findings must be an object keyed by fingerprint");
        }
        var fingerprints = new TreeSet<String>();
        findings.fieldNames().forEachRemaining(fingerprints::add);
        if (fingerprints.stream().anyMatch(fingerprint -> !FINGERPRINT.matcher(fingerprint).matches())) {
            throw invalid(file, "every finding key must be a sha256 fingerprint");
        }
        return Set.copyOf(fingerprints);
    }

    void write(Path file, AnalysisResult result) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Baseline path must have a parent directory: " + file);
        }
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, "." + file.getFileName() + '-', ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                mapper.writeValue(output, document(result));
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    BaselineApplication suppress(AnalysisResult result, Set<String> knownFingerprints) {
        var visibleResult = AnalysisResultFilter.retain(result,
                finding -> !knownFingerprints.contains(finding.fingerprint()));
        int suppressed = result.findings().size() - visibleResult.findings().size();
        return new BaselineApplication(visibleResult, suppressed);
    }

    private static Map<String, Object> document(AnalysisResult result) {
        var findings = new TreeMap<String, Object>();
        for (Finding finding : result.findings()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("ruleId", finding.ruleId());
            entry.put("file", Finding.normalizedPath(finding.location()));
            entry.put("message", finding.message());
            findings.put(finding.fingerprint(), entry);
        }

        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", SCHEMA_VERSION);
        document.put("fingerprintVersion", Finding.FINGERPRINT_VERSION);
        var tool = new LinkedHashMap<String, Object>();
        tool.put("name", "DiagScope");
        tool.put("version", BuildInfo.version());
        document.put("tool", tool);
        document.put("project", result.projectName());
        document.put("findings", findings);
        return document;
    }

    private static IllegalArgumentException invalid(Path file, String detail) {
        return new IllegalArgumentException("Invalid baseline " + file + ": " + detail);
    }

    record BaselineApplication(AnalysisResult result, int suppressedFindings) {
    }
}
