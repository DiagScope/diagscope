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
        JsonNode unfiltered = JSON.readTree(firstOutput.resolve("result.json").toFile());
        assertThat(result.path("findings")).isEmpty();
        assertThat(result.path("statistics").path("findings").asLong()).isZero();
        assertThat(result.path("summary").path("totalFindings").asLong()).isZero();
        assertThat(result.path("configuration").path("scanScope").path("baselineFile").asText())
                .isEqualTo(FindingBaseline.DEFAULT_FILE_NAME);
        assertThat(result.path("configuration").path("scanScope")
                .path("baselineSuppressedFindings").asInt()).isEqualTo(baselineJson.path("findings").size());
        assertThat(result.path("flows").findValues("diagnosticCoverage"))
                .as("baseline suppression must not improve diagnostic coverage")
                .isEqualTo(unfiltered.path("flows").findValues("diagnosticCoverage"));
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

    @Test
    void update_tracks_removed_findings_and_intentional_fingerprint_migrations() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path baseline = project.resolve(FindingBaseline.DEFAULT_FILE_NAME);
        assertThat(DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("initial").toString(),
                "--format", "JSON", "--parallelism", "1", "--update-baseline")).isZero();

        var document = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(baseline.toFile());
        String migratedSource = "sha256:" + "a".repeat(64);
        String removedSource = "sha256:" + "b".repeat(64);
        var active = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("findings");
        active.putObject(migratedSource).put("ruleId", "OLD_RULE").put("file", "old.kt").put("message", "old");
        active.putObject(removedSource).put("ruleId", "REMOVED_RULE").put("file", "gone.java").put("message", "gone");
        JSON.writerWithDefaultPrettyPrinter().writeValue(baseline.toFile(), document);
        String target = java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(document.path("findings").fieldNames(), 0), false)
                .filter(value -> !value.equals(migratedSource) && !value.equals(removedSource))
                .findFirst().orElseThrow();

        Path output = temp.resolve("updated");
        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--parallelism", "1", "--update-baseline",
                "--baseline-migration", migratedSource + '=' + target);

        assertThat(exit).isZero();
        JsonNode updated = JSON.readTree(baseline.toFile());
        assertThat(updated.path("schemaVersion").asText()).isEqualTo("1.1");
        assertThat(updated.path("removedFindings").path(migratedSource).path("migratedTo").asText())
                .isEqualTo(target);
        assertThat(updated.path("removedFindings").path(removedSource).path("status").asText())
                .isEqualTo("REMOVED");
        assertThat(updated.path("migrations").path(migratedSource).asText()).isEqualTo(target);
        JsonNode scope = JSON.readTree(output.resolve("result.json").toFile())
                .path("configuration").path("scanScope");
        assertThat(scope.path("baselineRemovedFindings").asInt()).isEqualTo(2);
        assertThat(scope.path("baselineFingerprintMigrations").asInt()).isEqualTo(1);

        assertThat(DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("pruned").toString(),
                "--format", "JSON", "--parallelism", "1", "--update-baseline",
                "--prune-removed-baseline")).isZero();
        assertThat(JSON.readTree(baseline.toFile()).path("removedFindings")).isEmpty();
    }
}
