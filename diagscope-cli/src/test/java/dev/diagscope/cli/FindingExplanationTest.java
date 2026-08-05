package dev.diagscope.cli;

import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every report explains a finding to a reader who does not know the rule: what it means, why it
 * matters at runtime, how it was detected, and what the reported confidence implies for triage.
 */
class FindingExplanationTest {
    @TempDir
    Path temp;

    @Test
    void markdown_json_and_html_reports_carry_the_explanation_and_confidence_meaning()
            throws IOException {
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
                .contains("**What this means:**")
                .contains("**Why it matters:**")
                .contains("**How it was detected:**")
                .contains("- Confidence means: ");

        String json = Files.readString(output.resolve("result.json"));
        assertThat(json)
                .contains("\"explanation\"")
                .contains("\"whatItMeans\"")
                .contains("\"whyItMatters\"")
                .contains("\"howDetected\"")
                .contains("\"confidenceRationale\"");

        String html = Files.readString(output.resolve("report.html"));
        assertThat(html)
                .contains("confidenceRationale")
                .contains("What this means:");
    }
}
