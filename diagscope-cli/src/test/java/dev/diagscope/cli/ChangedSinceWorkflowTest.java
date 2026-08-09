package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

class ChangedSinceWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void reports_only_findings_in_files_changed_since_the_revision() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        initializeRepository(project);

        Path initialOutput = temp.resolve("initial");
        assertThat(scan(project, initialOutput)).isZero();
        JsonNode initial = JSON.readTree(initialOutput.resolve("result.json").toFile());
        String changedFile = initial.path("findings").get(0).path("location").path("file").asText();

        Path unchangedOutput = temp.resolve("unchanged");
        int unchangedExit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", unchangedOutput.toString(),
                "--format", "JSON", "--parallelism", "1", "--changed-since", "HEAD",
                "--fail-on", "INFO");
        assertThat(unchangedExit).isZero();
        assertThat(JSON.readTree(unchangedOutput.resolve("result.json").toFile()).path("findings")).isEmpty();

        Files.writeString(project.resolve(changedFile), System.lineSeparator() + "// pull-request change",
                StandardOpenOption.APPEND);

        Path changedOutput = temp.resolve("changed");
        int changedExit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", changedOutput.toString(),
                "--format", "JSON", "--parallelism", "1", "--changed-since", "HEAD",
                "--fail-on", "INFO", "--update-baseline");

        assertThat(changedExit).isEqualTo(1);
        JsonNode changed = JSON.readTree(changedOutput.resolve("result.json").toFile());
        assertThat(changed.path("findings")).isNotEmpty();
        changed.path("findings").forEach(finding -> assertThat(
                finding.path("location").path("file").asText()).isEqualTo(changedFile));
        assertThat(changed.path("configuration").path("scanScope").path("changedSince").asText())
                .isEqualTo("HEAD");
        assertThat(changed.path("configuration").path("scanScope")
                .path("changeScopeExcludedFindings").asInt()).isPositive();
        JsonNode baseline = JSON.readTree(project.resolve(FindingBaseline.DEFAULT_FILE_NAME).toFile());
        assertThat(baseline.path("findings").size())
                .as("baseline updates retain findings outside the changed-file scope")
                .isEqualTo(initial.path("findings").size());
    }

    @Test
    void invalid_or_unknown_revision_is_rejected() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        initializeRepository(project);

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("out").toString(),
                "--format", "JSON", "--parallelism", "1", "--changed-since", "not-a-ref");

        assertThat(exit).isEqualTo(2);
    }

    private static int scan(Path project, Path output) {
        return DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--parallelism", "1");
    }

    private static void initializeRepository(Path project) throws IOException, InterruptedException {
        git(project, "init", "--quiet");
        git(project, "config", "user.email", "diagscope@example.test");
        git(project, "config", "user.name", "DiagScope Test");
        git(project, "add", ".");
        git(project, "commit", "--quiet", "-m", "fixture");
    }

    private static void git(Path project, String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git failed: " + output);
        }
    }
}
