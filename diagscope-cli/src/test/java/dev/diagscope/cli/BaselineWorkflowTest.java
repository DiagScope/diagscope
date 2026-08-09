package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void update_creates_a_deterministic_versioned_baseline_and_known_findings_do_not_fail_ci() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path firstOutput = temp.resolve("first-report");

        int updateExit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", firstOutput.toString(),
                "--format", "JSON", "--parallelism", "1", "--update-baseline");

        assertThat(updateExit).isZero();
        Path baseline = project.resolve(FindingBaseline.DEFAULT_FILE_NAME);
        assertThat(baseline).isRegularFile();
        byte[] firstBaseline = Files.readAllBytes(baseline);

        JsonNode baselineJson = JSON.readTree(baseline.toFile());
        assertThat(baselineJson.path("schemaVersion").asText()).isEqualTo(FindingBaseline.SCHEMA_VERSION);
        assertThat(baselineJson.path("fingerprintVersion").asInt()).isEqualTo(Finding.FINGERPRINT_VERSION);
        assertThat(baselineJson.path("findings").size()).isPositive();
        baselineJson.path("findings").fieldNames().forEachRemaining(
                fingerprint -> assertThat(fingerprint).startsWith("sha256:"));

        int secondUpdateExit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("second-report").toString(),
                "--format", "JSON", "--parallelism", "1", "--update-baseline");

        assertThat(secondUpdateExit).isZero();
        assertThat(Files.readAllBytes(baseline)).isEqualTo(firstBaseline);

        Path filteredOutput = temp.resolve("filtered-report");
        int filteredExit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", filteredOutput.toString(),
                "--format", "JSON", "--parallelism", "1", "--baseline", "--fail-on", "INFO");

        assertThat(filteredExit).isZero();
        JsonNode result = JSON.readTree(filteredOutput.resolve("result.json").toFile());
        assertThat(result.path("findings")).isEmpty();
        assertThat(result.path("statistics").path("findings").asLong()).isZero();
        assertThat(result.path("summary").path("totalFindings").asLong()).isZero();
        assertThat(result.path("configuration").path("scanScope").path("baselineFile").asText())
                .isEqualTo(FindingBaseline.DEFAULT_FILE_NAME);
        assertThat(result.path("configuration").path("scanScope")
                .path("baselineSuppressedFindings").asInt()).isEqualTo(baselineJson.path("findings").size());
    }

    @Test
    void custom_baseline_can_be_created_and_consumed() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path baseline = project.resolve("config/accepted-findings.json");

        int updateExit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("first").toString(),
                "--format", "JSON", "--parallelism", "1",
                "--baseline", "config/accepted-findings.json", "--update-baseline");

        assertThat(updateExit).isZero();
        assertThat(baseline).isRegularFile();

        int filteredExit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("second").toString(),
                "--format", "JSON", "--parallelism", "1",
                "--baseline", baseline.toString(), "--fail-on", "INFO");

        assertThat(filteredExit).isZero();
    }

    @Test
    void incompatible_fingerprint_version_is_rejected() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path baseline = project.resolve(FindingBaseline.DEFAULT_FILE_NAME);
        Files.writeString(baseline, """
                {
                  "schemaVersion": "1.0",
                  "fingerprintVersion": 999,
                  "findings": {}
                }
                """);

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("out").toString(),
                "--format", "JSON", "--parallelism", "1", "--baseline");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void missing_baseline_is_rejected_unless_it_is_being_created() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("out").toString(),
                "--format", "JSON", "--parallelism", "1", "--baseline", "missing.json");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void relative_baseline_path_cannot_escape_the_project() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path escaped = temp.resolve("outside-baseline.json");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("out").toString(),
                "--format", "JSON", "--parallelism", "1",
                "--baseline", "../outside-baseline.json", "--update-baseline");

        assertThat(exit).isEqualTo(2);
        assertThat(escaped).doesNotExist();
    }
}
