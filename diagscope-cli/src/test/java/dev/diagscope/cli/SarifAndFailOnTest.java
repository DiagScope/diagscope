package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SarifAndFailOnTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void sarif_report_is_valid_and_carries_fingerprints() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("reports");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "SARIF", "--parallelism", "1");

        assertThat(exit).isZero();
        Path sarif = output.resolve("result.sarif");
        assertThat(sarif).isRegularFile();

        JsonNode document = JSON.readTree(sarif.toFile());
        assertThat(document.path("version").asText()).isEqualTo("2.1.0");
        JsonNode run = document.path("runs").get(0);
        assertThat(run.path("tool").path("driver").path("name").asText()).isEqualTo("DiagScope");
        assertThat(run.path("tool").path("driver").path("rules")).isNotEmpty();
        JsonNode result = run.path("results").get(0);
        assertThat(result.path("ruleId").asText()).isNotBlank();
        assertThat(result.path("level").asText()).isIn("error", "warning", "note");
        assertThat(result.path("locations").get(0).path("physicalLocation")
                .path("artifactLocation").path("uri").asText()).endsWith(".java");
        assertThat(result.path("partialFingerprints").path("diagscopeFingerprint/v1").asText())
                .startsWith("sha256:");
    }

    @Test
    void fail_on_info_breaks_the_build_while_reports_are_still_written() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("reports");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--fail-on", "INFO", "--parallelism", "1");

        assertThat(exit).isEqualTo(1);
        assertThat(output.resolve("result.json")).isRegularFile();
    }

    @Test
    void default_scan_stays_informational() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("out").toString(),
                "--format", "JSON", "--parallelism", "1");

        assertThat(exit).isZero();
    }
}
