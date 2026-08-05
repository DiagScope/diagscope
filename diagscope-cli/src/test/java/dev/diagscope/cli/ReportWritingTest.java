package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report-writing contract: writes are atomic, a failing reporter never corrupts a previous report
 * or leaves temporary files behind, and an unknown report format fails before any analysis output.
 */
class ReportWritingTest {
    @TempDir
    Path temp;

    @Test
    void unknown_report_format_is_rejected_without_creating_output() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("unknown-format");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan",
                "--project", project.toString(),
                "--output", output.toString(),
                "--format", "SARIF");

        assertThat(exit).isEqualTo(2);
        assertThat(output).doesNotExist();
    }

    @Test
    void report_files_are_replaced_atomically_on_a_successful_rescan() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("reports");
        Files.createDirectories(output);
        Files.writeString(output.resolve("report.md"), "stale content");

        assertThat(scan(project, output)).isZero();

        assertThat(Files.readString(output.resolve("report.md")))
                .doesNotContain("stale content")
                .startsWith("# DiagScope Report");
        assertThat(temporaryFiles(output)).isEmpty();
    }

    @Test
    void a_failing_reporter_leaves_no_partial_or_temporary_file() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("failing-reports");
        Files.createDirectories(output);
        Files.writeString(output.resolve("result.json"), "{\"previous\":true}");

        var command = new ScanCommand(
                DiagScopeMain.createScanUseCase(),
                Map.of(ReportFormat.JSON, new FailingReporter()));
        int exit = new picocli.CommandLine(command).execute(
                "--project", project.toString(),
                "--output", output.toString(),
                "--format", "JSON");

        assertThat(exit).isEqualTo(2);
        assertThat(Files.readString(output.resolve("result.json"))).isEqualTo("{\"previous\":true}");
        assertThat(temporaryFiles(output)).isEmpty();
    }

    private static int scan(Path project, Path output) {
        return DiagScopeMain.createCommandLine().execute(
                "scan",
                "--project", project.toString(),
                "--output", output.toString(),
                "--parallelism", "1");
    }

    private static List<Path> temporaryFiles(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    private static final class FailingReporter implements AnalysisReporter {
        @Override
        public ReportFormat format() {
            return ReportFormat.JSON;
        }

        @Override
        public void write(AnalysisResult result, OutputStream output) throws IOException {
            output.write("partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("reporter failed");
        }
    }
}
