package dev.diagscope.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workflow is the embedding surface for build-tool plugins and CI wrappers, so it must produce
 * the same reports and the same severity gate as the CLI without any picocli involvement.
 */
class ScanWorkflowTest {

    @Test
    void writesRequestedReportsAndAppliesSeverityGate(@TempDir Path projectRoot) throws Exception {
        writeSampleProject(projectRoot);

        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var outcome = DiagScopeMain.createScanWorkflow().run(
                new ScanWorkflow.Request(
                        projectRoot,
                        Path.of("target", "diagscope"),
                        3,
                        1,
                        EnumSet.of(ReportFormat.JSON, ReportFormat.SARIF),
                        FailOn.INFO,
                        null,
                        false,
                        List.of(),
                        false,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        null
                ),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8)
        );

        Path reports = projectRoot.resolve("target/diagscope");
        assertThat(reports.resolve(ReportFormat.JSON.fileName())).exists();
        assertThat(reports.resolve(ReportFormat.SARIF.fileName())).exists();
        assertThat(reports.resolve(ReportFormat.HTML.fileName())).doesNotExist();
        assertThat(outcome.outputDirectory()).isEqualTo(reports);
        assertThat(outcome.result().findings()).isNotEmpty();
        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.gateBreached()).isTrue();
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("Failing:");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Findings:");
    }

    @Test
    void keepsExitCodeZeroWithoutAGate(@TempDir Path projectRoot) throws Exception {
        writeSampleProject(projectRoot);

        var outcome = DiagScopeMain.createScanWorkflow().run(
                new ScanWorkflow.Request(
                        projectRoot, null, 3, 1, null, FailOn.NONE, null, false,
                        null, false, null, null, null, null, null
                ),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.gateBreached()).isFalse();
        assertThat(projectRoot.resolve("target/diagscope").resolve(ReportFormat.HTML.fileName())).exists();
    }

    private static void writeSampleProject(Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>sample</groupId>
                  <artifactId>sample</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path sources = projectRoot.resolve("src/main/java/sample");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("SampleController.java"), """
                package sample;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class SampleController {

                    @GetMapping("/sample")
                    public String sample() {
                        try {
                            return load();
                        } catch (Exception exception) {
                            return "";
                        }
                    }

                    private String load() throws Exception {
                        throw new Exception("boom");
                    }
                }
                """);
    }
}
