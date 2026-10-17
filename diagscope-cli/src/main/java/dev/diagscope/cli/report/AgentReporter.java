package dev.diagscope.cli.report;

import dev.diagscope.cli.BuildInfo;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.domain.Finding;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Compact plain text for a coding assistant: what is wrong, where, and what to do, nothing else.
 *
 * <p>Every token an assistant reads costs time and money, and a verbose report invites it to skim.
 * One block per finding, worst first, capped so a noisy project cannot flood the conversation. The
 * footer is the only instruction in the output and exists so a failing check is fixed rather than
 * silenced.</p>
 */
public final class AgentReporter implements AnalysisReporter {
    static final int MAX_FINDINGS = 30;

    private static final Comparator<Finding> WORST_FIRST = Comparator
            .comparing(Finding::severity).reversed()
            .thenComparing(Finding::confidence, Comparator.reverseOrder())
            .thenComparing(finding -> finding.location().file().toString())
            .thenComparingInt(finding -> finding.location().startLine())
            .thenComparing(Finding::ruleId);

    @Override
    public ReportFormat format() {
        return ReportFormat.AGENT;
    }

    @Override
    public void write(AnalysisResult result, OutputStream output) throws IOException {
        List<Finding> findings = result.findings().stream().sorted(WORST_FIRST).toList();
        var text = new StringBuilder(1024);
        text.append("diagscope ").append(BuildInfo.version()).append(": ");
        if (findings.isEmpty()) {
            text.append("no findings\n");
        } else {
            long files = findings.stream().map(finding -> finding.location().file()).distinct().count();
            text.append(findings.size()).append(" finding(s) in ").append(files).append(" file(s)\n");
            findings.stream().limit(MAX_FINDINGS).forEach(finding -> append(text, result.projectRoot(), finding));
            if (findings.size() > MAX_FINDINGS) {
                text.append("\n+").append(findings.size() - MAX_FINDINGS)
                        .append(" more, lower priority; see result.json\n");
            }
            text.append("\nFix the cause. Do not add diagscope:ignore comments, waivers, baseline entries or"
                    + " rule overrides without asking the user. `diagscope explain <RULE>` gives the reasoning behind a rule.\n");
        }
        output.write(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void append(StringBuilder text, Path root, Finding finding) {
        text.append('\n').append(finding.severity()).append(' ').append(finding.confidence()).append(' ')
                .append(finding.ruleId()).append(' ').append(path(root, finding)).append(':')
                .append(finding.location().startLine()).append('\n')
                .append("  ").append(oneLine(finding.message())).append('\n')
                .append("  fix: ").append(oneLine(finding.recommendation())).append('\n');
    }

    private static String path(Path root, Finding finding) {
        Path file = finding.location().file().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (file.isAbsolute() && file.startsWith(normalizedRoot)) file = normalizedRoot.relativize(file);
        return file.toString().replace('\\', '/');
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
