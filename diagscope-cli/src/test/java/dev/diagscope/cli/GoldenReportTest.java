package dev.diagscope.cli;

import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalized golden reports.
 *
 * <p>Semantic determinism is covered by repeated-scan tests; these goldens additionally freeze the
 * exact rendered Markdown and JSON so an accidental report change is reviewed instead of silently
 * shipped. Volatile values (absolute paths, tool version, timings) are normalized away.</p>
 *
 * <p>Regenerate intentional changes with:
 * {@code mvn -pl diagscope-cli test -Dtest=GoldenReportTest -Ddiagscope.golden.update=true}
 * and review the diff.</p>
 */
class GoldenReportTest {
    private static final String UPDATE_PROPERTY = "diagscope.golden.update";
    private static final Path GOLDEN_SOURCE_DIRECTORY =
            Path.of("src", "test", "resources", "golden", "mixed-flow");

    @TempDir
    Path temp;

    @Test
    void markdown_report_matches_the_normalized_golden_file() throws IOException {
        assertMatchesGolden("report.md");
    }

    @Test
    void json_report_matches_the_normalized_golden_file() throws IOException {
        assertMatchesGolden("result.json");
    }

    private void assertMatchesGolden(String fileName) throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("golden-output");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan",
                "--project", project.toString(),
                "--output", output.toString(),
                "--parallelism", "1");

        assertThat(exit).isZero();
        String actual = normalize(Files.readString(output.resolve(fileName)), project);

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Path destination = GOLDEN_SOURCE_DIRECTORY.resolve(fileName);
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, actual);
            return;
        }

        assertThat(actual)
                .describedAs("Report %s changed. Review the diff and regenerate with -D%s=true",
                        fileName, UPDATE_PROPERTY)
                .isEqualTo(golden(fileName));
    }

    private static String normalize(String report, Path project) {
        return report
                .replace(project.toAbsolutePath().normalize().toString(), "<project>")
                .replace(project.toString(), "<project>")
                .replace('\\', '/')
                .replace(BuildInfo.version(), "<version>")
                .replaceAll("(?m)\\| Total time \\| \\d+ ms \\|", "| Total time | <duration> |")
                .replaceAll("\"(projectAnalysisNanos|flowConstructionNanos|ruleExecutionNanos|totalNanos)\" : \\d+",
                        "\"$1\" : 0")
                .replace("\r\n", "\n")
                .stripTrailing() + "\n";
    }

    private static String golden(String fileName) throws IOException {
        try (InputStream stream = GoldenReportTest.class.getResourceAsStream("/golden/mixed-flow/" + fileName)) {
            assertThat(stream).describedAs("Missing golden resource %s", fileName).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
