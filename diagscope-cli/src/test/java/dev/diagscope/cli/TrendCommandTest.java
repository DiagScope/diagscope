package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TrendCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "sha256:" + "a".repeat(64);
    private static final String B = "sha256:" + "b".repeat(64);
    private static final String C = "sha256:" + "c".repeat(64);

    @TempDir Path temp;

    @Test
    void compares_compatible_results_by_fingerprint() throws Exception {
        Path base = result("base.json", "1.1-alpha.1", A, B);
        Path current = result("current.json", "1.2-alpha.1", B, C);
        Path output = temp.resolve("trend.json");

        int exit = DiagScopeMain.createCommandLine().execute("trend", "--base", base.toString(),
                "--current", current.toString(), "--format", "JSON", "--output", output.toString());

        assertThat(exit).isZero();
        JsonNode trend = JSON.readTree(output.toFile());
        assertThat(trend.path("summary").path("new").asInt()).isEqualTo(1);
        assertThat(trend.path("summary").path("fixed").asInt()).isEqualTo(1);
        assertThat(trend.path("summary").path("persisting").asInt()).isEqualTo(1);
        assertThat(trend.path("newFindings").get(0).path("fingerprint").asText()).isEqualTo(C);
        assertThat(trend.path("fixedFindings").get(0).path("fingerprint").asText()).isEqualTo(A);
    }

    @Test
    void rejects_results_from_different_projects() throws Exception {
        Path base = result("base.json", "1.2-alpha.1", A);
        String other = Files.readString(base).replace("\"demo\"", "\"other\"");
        Path current = temp.resolve("current.json");
        Files.writeString(current, other);

        int exit = DiagScopeMain.createCommandLine().execute("trend", "--base", base.toString(),
                "--current", current.toString());

        assertThat(exit).isEqualTo(2);
    }

    private Path result(String name, String schema, String... fingerprints) throws Exception {
        var root = JSON.createObjectNode();
        root.put("schemaVersion", schema);
        root.putObject("project").put("name", "demo");
        var findings = root.putArray("findings");
        for (int index = 0; index < fingerprints.length; index++) {
            var finding = findings.addObject();
            finding.put("fingerprint", fingerprints[index]);
            finding.put("fingerprintVersion", 1);
            finding.put("ruleId", "RULE_" + index);
            finding.put("severity", "WARNING");
            finding.put("confidence", "HIGH");
            finding.putObject("location").put("file", "src/Worker.java").put("startLine", index + 1);
            finding.put("message", "message " + index);
        }
        Path file = temp.resolve(name);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }
}
