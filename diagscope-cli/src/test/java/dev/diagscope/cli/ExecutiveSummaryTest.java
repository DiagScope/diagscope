package dev.diagscope.cli;

import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every report opens with an executive summary: how many findings each rule produced and how they
 * split across confidence levels, so the state of a project is readable in seconds.
 */
class ExecutiveSummaryTest {
    @TempDir
    Path temp;

    @Test
    void reports_open_with_counts_per_rule_and_per_confidence() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("reports");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan",
                "--project", project.toString(),
                "--output", output.toString(),
                "--format", "MARKDOWN,JSON,HTML");
        assertThat(exit).isIn(0, 1);

        String markdown = Files.readString(output.resolve("report.md"));
        assertThat(markdown)
                .contains("## Executive summary")
                .contains("### Findings by rule")
                .contains("### Findings by confidence")
                .contains("| Rule | What it flags | Findings | Highest severity | High | Medium | Low |");
        assertThat(markdown.indexOf("## Executive summary")).isLessThan(markdown.indexOf("## Findings"));

        String json = Files.readString(output.resolve("result.json"));
        assertThat(json)
                .contains("\"summary\"")
                .contains("\"byRule\"")
                .contains("\"byConfidence\"")
                .contains("\"highestSeverity\"");

        String html = Files.readString(output.resolve("report.html"));
        assertThat(html)
                .contains("id=\"exec-summary\"")
                .contains("Executive summary")
                .contains("data-exec-rule");
    }
}
