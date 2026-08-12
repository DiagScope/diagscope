package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SuppressionWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void reviewed_waiver_hides_one_finding_and_an_expired_waiver_brings_it_back() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path firstOutput = temp.resolve("baseline-run");

        assertThat(scan(project, firstOutput)).isZero();
        JsonNode unfiltered = JSON.readTree(firstOutput.resolve("result.json").toFile());
        int totalFindings = unfiltered.path("findings").size();
        assertThat(totalFindings).isPositive();
        String fingerprint = unfiltered.path("findings").get(0).path("fingerprint").asText();
        assertThat(fingerprint).startsWith("sha256:");

        writeConfiguration(project, fingerprint, LocalDate.now().plusDays(30));
        Path waivedOutput = temp.resolve("waived-run");
        assertThat(scan(project, waivedOutput)).isZero();

        JsonNode waived = JSON.readTree(waivedOutput.resolve("result.json").toFile());
        JsonNode scope = waived.path("configuration").path("scanScope");
        assertThat(waived.path("findings").size()).isEqualTo(totalFindings - 1);
        assertThat(scope.path("waivedFindings").asInt()).isEqualTo(1);
        assertThat(scope.path("expiredWaivers").asInt()).isZero();
        assertThat(scope.path("unusedWaivers").asInt()).isZero();
        assertThat(waived.path("schemaVersion").asText()).isEqualTo("1.4-alpha.1");

        writeConfiguration(project, fingerprint, LocalDate.now().minusDays(1));
        Path expiredOutput = temp.resolve("expired-run");
        assertThat(scan(project, expiredOutput)).isZero();

        JsonNode expired = JSON.readTree(expiredOutput.resolve("result.json").toFile());
        assertThat(expired.path("findings").size()).isEqualTo(totalFindings);
        assertThat(expired.path("configuration").path("scanScope").path("expiredWaivers").asInt()).isEqualTo(1);
    }

    @Test
    void unused_waiver_is_reported_so_the_configuration_cannot_rot() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        writeConfiguration(project, "sha256:" + "a".repeat(64), null);
        Path output = temp.resolve("unused-run");

        assertThat(scan(project, output)).isZero();
        JsonNode scope = JSON.readTree(output.resolve("result.json").toFile())
                .path("configuration").path("scanScope");
        assertThat(scope.path("waivedFindings").asInt()).isZero();
        assertThat(scope.path("unusedWaivers").asInt()).isEqualTo(1);
    }

    @Test
    void waiver_without_a_reason_is_rejected() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Files.writeString(project.resolve("diagscope.yml"), """
                schemaVersion: "1.0"
                suppressions:
                  - fingerprint: "%s"
                    reason: ""
                """.formatted("b".repeat(64)));

        assertThat(scan(project, temp.resolve("invalid-run"))).isEqualTo(2);
    }

    private static int scan(Path project, Path output) {
        return DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--parallelism", "1");
    }

    private static void writeConfiguration(Path project, String fingerprint, LocalDate expires) throws Exception {
        String expiry = expires == null ? "" : "\n    expires: \"" + expires + "\"";
        Files.writeString(project.resolve("diagscope.yml"), """
                schemaVersion: "1.0"
                suppressions:
                  - fingerprint: "%s"
                    reason: "Reviewed on the incident retrospective; handled by the platform gateway."%s
                """.formatted(fingerprint, expiry));
    }
}
