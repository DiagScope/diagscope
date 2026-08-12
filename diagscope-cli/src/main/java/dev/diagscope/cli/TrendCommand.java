package dev.diagscope.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.diagscope.core.domain.Finding;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/** Compares two compatible result.json files by stable finding fingerprint. */
@Command(name = "trend", mixinStandardHelpOptions = true,
        description = "Compare two compatible result.json files as new, fixed, and persisting findings.")
public final class TrendCommand implements Callable<Integer> {
    private static final Set<String> SUPPORTED_RESULT_SCHEMAS = Set.of(
            "1.0-alpha.1", "1.1-alpha.1", "1.2-alpha.1", "1.3-alpha.1", "1.4-alpha.1");

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    @Option(names = "--base", required = true, description = "Earlier result.json")
    private Path base;

    @Option(names = "--current", required = true, description = "Later result.json")
    private Path current;

    @Option(names = {"-o", "--output"}, description = "Write the trend report to this file; stdout when omitted")
    private Path output;

    @Option(names = "--format", defaultValue = "MARKDOWN", description = "MARKDOWN or JSON")
    private TrendFormat format;

    @Override
    public Integer call() {
        try {
            ResultSnapshot previous = read(base);
            ResultSnapshot latest = read(current);
            validateCompatible(previous, latest);
            Trend trend = compare(previous, latest);
            String rendered = format == TrendFormat.JSON ? json(trend) : markdown(trend);
            if (output == null) {
                System.out.print(rendered);
            } else {
                Path destination = output.toAbsolutePath().normalize();
                Path parent = destination.getParent();
                if (parent == null) throw new IllegalArgumentException("Trend output must have a parent directory");
                writeAtomically(destination, rendered);
                System.out.printf("Trend: %d new, %d fixed, %d persisting | Output: %s%n",
                        trend.added().size(), trend.fixed().size(), trend.persisting().size(), destination);
            }
            return 0;
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid trend input: " + exception.getMessage());
            return 2;
        } catch (Exception exception) {
            System.err.println("DiagScope trend failed: " + exception.getMessage());
            return 2;
        }
    }

    private static void writeAtomically(Path destination, String content) throws IOException {
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + destination.getFileName() + '-', ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private ResultSnapshot read(Path configured) throws IOException {
        Path file = configured.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Result file does not exist: " + file);
        JsonNode document;
        try {
            document = mapper.readTree(file.toFile());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(file + " contains malformed JSON: " + exception.getOriginalMessage());
        }
        if (document == null || !document.isObject()) throw new IllegalArgumentException(file + " root must be an object");
        String schema = document.path("schemaVersion").asText();
        if (!SUPPORTED_RESULT_SCHEMAS.contains(schema)) {
            throw new IllegalArgumentException(file + " uses unsupported schemaVersion " + schema);
        }
        JsonNode findings = document.path("findings");
        if (!findings.isArray()) throw new IllegalArgumentException(file + " findings must be an array");
        var byFingerprint = new TreeMap<String, FindingSnapshot>();
        Integer fingerprintVersion = null;
        for (JsonNode finding : findings) {
            String fingerprint = finding.path("fingerprint").asText();
            if (!fingerprint.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(file + " contains an invalid finding fingerprint");
            }
            int version = finding.path("fingerprintVersion").asInt(-1);
            if (version < 1) throw new IllegalArgumentException(file + " contains no valid fingerprintVersion");
            if (fingerprintVersion != null && fingerprintVersion != version) {
                throw new IllegalArgumentException(file + " mixes fingerprint versions");
            }
            fingerprintVersion = version;
            FindingSnapshot snapshot = snapshot(finding);
            if (byFingerprint.put(fingerprint, snapshot) != null) {
                throw new IllegalArgumentException(file + " contains duplicate fingerprint " + fingerprint);
            }
        }
        String project = document.path("project").path("name").asText("");
        return new ResultSnapshot(file, schema, project, fingerprintVersion, Map.copyOf(byFingerprint));
    }

    private static FindingSnapshot snapshot(JsonNode finding) {
        JsonNode location = finding.path("location");
        return new FindingSnapshot(
                finding.path("fingerprint").asText(),
                finding.path("ruleId").asText(),
                finding.path("severity").asText(),
                finding.path("confidence").asText(),
                location.path("file").asText(),
                location.path("startLine").asInt(),
                finding.path("message").asText()
        );
    }

    private static void validateCompatible(ResultSnapshot base, ResultSnapshot current) {
        if (!base.project().isBlank() && !current.project().isBlank() && !base.project().equals(current.project())) {
            throw new IllegalArgumentException("project names differ: " + base.project() + " versus " + current.project());
        }
        if (base.fingerprintVersion() != null && current.fingerprintVersion() != null
                && !base.fingerprintVersion().equals(current.fingerprintVersion())) {
            throw new IllegalArgumentException("fingerprint versions differ: " + base.fingerprintVersion()
                    + " versus " + current.fingerprintVersion());
        }
    }

    private static Trend compare(ResultSnapshot base, ResultSnapshot current) {
        var added = new java.util.ArrayList<FindingSnapshot>();
        var fixed = new java.util.ArrayList<FindingSnapshot>();
        var persisting = new java.util.ArrayList<FindingSnapshot>();
        current.findings().forEach((fingerprint, finding) -> {
            if (base.findings().containsKey(fingerprint)) persisting.add(finding); else added.add(finding);
        });
        base.findings().forEach((fingerprint, finding) -> {
            if (!current.findings().containsKey(fingerprint)) fixed.add(finding);
        });
        return new Trend(base, current, List.copyOf(added), List.copyOf(fixed), List.copyOf(persisting));
    }

    private String json(Trend trend) throws JsonProcessingException {
        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", "1.0");
        document.put("fingerprintVersion", trend.fingerprintVersion());
        document.put("base", run(trend.base()));
        document.put("current", run(trend.current()));
        document.put("summary", orderedMap(
                "new", trend.added().size(),
                "fixed", trend.fixed().size(),
                "persisting", trend.persisting().size()));
        document.put("newFindings", trend.added().stream().map(TrendCommand::finding).toList());
        document.put("fixedFindings", trend.fixed().stream().map(TrendCommand::finding).toList());
        document.put("persistingFindings", trend.persisting().stream().map(TrendCommand::finding).toList());
        return mapper.writeValueAsString(document) + System.lineSeparator();
    }

    private static String markdown(Trend trend) {
        var builder = new StringBuilder(4096);
        builder.append("# DiagScope Trend\n\n")
                .append("Comparing `").append(escape(trend.base().file().toString())).append("` with `")
                .append(escape(trend.current().file().toString())).append("`.\n\n")
                .append("| New | Fixed | Persisting |\n| ---: | ---: | ---: |\n| ")
                .append(trend.added().size()).append(" | ").append(trend.fixed().size()).append(" | ")
                .append(trend.persisting().size()).append(" |\n\n");
        appendFindings(builder, "New findings", trend.added());
        appendFindings(builder, "Fixed findings", trend.fixed());
        appendFindings(builder, "Persisting findings", trend.persisting());
        return builder.toString();
    }

    private static void appendFindings(StringBuilder builder, String title, List<FindingSnapshot> findings) {
        builder.append("## ").append(title).append("\n\n");
        if (findings.isEmpty()) {
            builder.append("None.\n\n");
            return;
        }
        builder.append("| Rule | Severity | Confidence | Location | Message |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        findings.forEach(finding -> builder.append("| `").append(escape(finding.ruleId())).append("` | `")
                .append(escape(finding.severity())).append("` | `").append(escape(finding.confidence()))
                .append("` | `").append(escape(finding.file())).append(':').append(finding.line())
                .append("` | ").append(escape(finding.message())).append(" |\n"));
        builder.append('\n');
    }

    private static Map<String, Object> run(ResultSnapshot result) {
        return orderedMap("file", result.file().toString(), "schemaVersion", result.schemaVersion(),
                "project", result.project(), "findings", result.findings().size());
    }

    private static Map<String, Object> finding(FindingSnapshot finding) {
        return orderedMap("fingerprint", finding.fingerprint(), "ruleId", finding.ruleId(),
                "severity", finding.severity(), "confidence", finding.confidence(),
                "location", orderedMap("file", finding.file(), "startLine", finding.line()),
                "message", finding.message());
    }

    private static Map<String, Object> orderedMap(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("`", "\\`").replace("\r", " ").replace("\n", " ");
    }

    enum TrendFormat { MARKDOWN, JSON }

    private record ResultSnapshot(Path file, String schemaVersion, String project,
                                  Integer fingerprintVersion, Map<String, FindingSnapshot> findings) {
    }

    private record FindingSnapshot(String fingerprint, String ruleId, String severity, String confidence,
                                   String file, int line, String message) {
    }

    private record Trend(ResultSnapshot base, ResultSnapshot current, List<FindingSnapshot> added,
                         List<FindingSnapshot> fixed, List<FindingSnapshot> persisting) {
        int fingerprintVersion() {
            if (current.fingerprintVersion() != null) return current.fingerprintVersion();
            if (base.fingerprintVersion() != null) return base.fingerprintVersion();
            return Finding.FINGERPRINT_VERSION;
        }
    }
}
