package dev.diagscope.cli.report;

import dev.diagscope.cli.BuildInfo;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Findings-first Markdown report.
 *
 * <p>The report answers "what should I review or change in my code?" before it exposes analyzer
 * internals: summary, findings, then the flow overview with boundaries folded away.</p>
 */
public final class MarkdownReporter implements AnalysisReporter {
    private static final int MAX_BOUNDARIES_PER_FLOW = 10;

    @Override
    public ReportFormat format() {
        return ReportFormat.MARKDOWN;
    }

    @Override
    public void write(AnalysisResult result, OutputStream output) throws IOException {
        var builder = new StringBuilder(8192);
        appendSummary(builder, result);
        appendFindings(builder, result);
        appendFlows(builder, result);
        output.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendSummary(StringBuilder builder, AnalysisResult result) {
        var statistics = result.statistics();
        long boundaries = result.flows().stream().mapToLong(flow -> flow.boundaries().size()).sum();
        builder.append("# DiagScope Report\n\n")
                .append("`").append(escape(result.projectName())).append("` — ")
                .append(statistics.findings()).append(" finding(s) across ")
                .append(statistics.flows()).append(" flow(s).\n\n")
                .append("| Metric | Value |\n| --- | --- |\n")
                .append("| Findings | ").append(statistics.findings()).append(" |\n")
                .append("| Errors | ").append(countBySeverity(result, "ERROR")).append(" |\n")
                .append("| Warnings | ").append(countBySeverity(result, "WARNING")).append(" |\n")
                .append("| Info | ").append(countBySeverity(result, "INFO")).append(" |\n")
                .append("| Flows | ").append(statistics.flows()).append(" |\n")
                .append("| Source files | ").append(statistics.sourceFiles()).append(" |\n")
                .append("| Methods | ").append(statistics.parsedMethods()).append(" |\n")
                .append("| Flow boundaries | ").append(boundaries).append(" |\n")
                .append("| Parse failures | ").append(statistics.parseFailures()).append(" |\n")
                .append("| Total time | ").append(statistics.phaseMetrics().totalMillis()).append(" ms |\n")
                .append("| Tool version | `").append(BuildInfo.version()).append("` |\n\n")
                .append("<details><summary>Scan configuration</summary>\n\n")
                .append("- Maximum flow depth: ").append(result.options().maxFlowDepth()).append("\n")
                .append("- Parser workers: ").append(result.options().parallelism()).append("\n")
                .append("- Entrypoint types: ").append(result.options().enabledEntrypointTypes().stream()
                        .map(Enum::name).sorted().collect(Collectors.joining(", "))).append("\n\n")
                .append("</details>\n\n");
        if (!result.parseFailures().isEmpty()) {
            builder.append("<details><summary>Parse failures (")
                    .append(result.parseFailures().size()).append(")</summary>\n\n");
            result.parseFailures().forEach(failure -> builder.append("- `")
                    .append(escape(failure.file().toString())).append("`: ")
                    .append(escape(failure.message())).append("\n"));
            builder.append("\n</details>\n\n");
        }
    }

    private static void appendFindings(StringBuilder builder, AnalysisResult result) {
        builder.append("## Findings\n\n");
        if (result.findings().isEmpty()) {
            builder.append("No findings. Every analyzed flow preserves diagnostic evidence.\n\n");
            return;
        }
        for (var finding : result.findings()) {
            String flows = finding.relatedFlows().stream()
                    .map(flow -> escape(flow.displayName()) + " (`" + flow.confidence() + "`)")
                    .collect(Collectors.joining(", "));
            builder.append("### ").append(icon(finding.severity())).append(' ')
                    .append(finding.ruleId()).append(" — `")
                    .append(escape(finding.location().file().toString())).append(':')
                    .append(finding.location().startLine()).append("`\n\n")
                    .append(escape(finding.message())).append("\n\n")
                    .append("**Suggested action:** ").append(escape(finding.recommendation())).append("\n\n")
                    .append("- Severity: `").append(finding.severity())
                    .append("` · Confidence: `").append(finding.confidence()).append("`\n")
                    .append("- Affected flows: ").append(flows.isEmpty() ? "none" : flows).append("\n")
                    .append("- Fingerprint: `").append(finding.fingerprint()).append("`\n\n");
            if (!finding.evidence().isEmpty()) {
                builder.append("<details><summary>Evidence</summary>\n\n");
                finding.evidence().forEach((key, value) -> builder.append("- `")
                        .append(escape(key)).append("`: `").append(escape(value)).append("`\n"));
                builder.append("\n</details>\n\n");
            }
        }
    }

    private static void appendFlows(StringBuilder builder, AnalysisResult result) {
        builder.append("## Flow overview\n\n");
        if (result.flows().isEmpty()) {
            builder.append("No supported entrypoints were found.\n\n");
            return;
        }
        builder.append("Flow boundaries are analyzer limits, not defects.\n\n")
                .append("| Entrypoint | Type | Confidence | Methods | Boundaries |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        for (var flow : result.flows()) {
            builder.append("| ").append(escape(flow.entrypoint().displayName()))
                    .append(" | `").append(flow.entrypoint().type())
                    .append("` | `").append(flow.confidence())
                    .append("` | ").append(flow.methods().size())
                    .append(" | ").append(flow.boundaries().size()).append(" |\n");
        }
        builder.append('\n');
        for (var flow : result.flows()) {
            if (flow.boundaries().isEmpty()) {
                continue;
            }
            builder.append("<details><summary>Boundaries — ")
                    .append(escape(flow.entrypoint().displayName()))
                    .append(" (").append(flow.boundaries().size()).append(")</summary>\n\n");
            flow.boundaries().stream().limit(MAX_BOUNDARIES_PER_FLOW).forEach(boundary ->
                    builder.append("- `").append(boundary.resolutionReason()).append("` at `")
                            .append(escape(boundary.callSite().file().toString())).append(':')
                            .append(boundary.callSite().startLine()).append("`: ")
                            .append(escape(boundary.displayName())).append("\n"));
            int hidden = flow.boundaries().size() - MAX_BOUNDARIES_PER_FLOW;
            if (hidden > 0) {
                builder.append("- … ").append(hidden).append(" more (see `result.json`)\n");
            }
            builder.append("\n</details>\n\n");
        }
    }

    private static long countBySeverity(AnalysisResult result, String severity) {
        return result.findings().stream()
                .filter(finding -> finding.severity().name().equals(severity))
                .count();
    }

    private static String icon(dev.diagscope.core.domain.Severity severity) {
        return switch (severity) {
            case ERROR -> "❌";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };
    }

    private static String escape(String value) {
        return value.replace("`", "\\`").replace("\r", " ").replace("\n", " ");
    }
}
