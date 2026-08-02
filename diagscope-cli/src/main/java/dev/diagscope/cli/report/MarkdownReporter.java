package dev.diagscope.cli.report;

import dev.diagscope.cli.BuildInfo;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public final class MarkdownReporter implements AnalysisReporter {
    @Override
    public ReportFormat format() {
        return ReportFormat.MARKDOWN;
    }

    @Override
    public void write(AnalysisResult result, OutputStream output) throws IOException {
        var builder = new StringBuilder(8192);
        appendSummary(builder, result);
        appendFlows(builder, result);
        appendFindings(builder, result);
        output.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendSummary(StringBuilder builder, AnalysisResult result) {
        var statistics = result.statistics();
        long boundaries = result.flows().stream().mapToLong(flow -> flow.boundaries().size()).sum();
        builder.append("# DiagScope Report\n\n")
                .append("- Tool version: `").append(BuildInfo.version()).append("`\n")
                .append("- Project: `").append(escape(result.projectName())).append("`\n")
                .append("- Source files: ").append(statistics.sourceFiles()).append("\n")
                .append("- Methods: ").append(statistics.parsedMethods()).append("\n")
                .append("- Flows: ").append(statistics.flows()).append("\n")
                .append("- Findings: ").append(statistics.findings()).append("\n")
                .append("- Parse failures: ").append(statistics.parseFailures()).append("\n")
                .append("- Flow boundaries: ").append(boundaries).append("\n")
                .append("- Maximum flow depth: ").append(result.options().maxFlowDepth()).append("\n")
                .append("- Parser workers: ").append(result.options().parallelism()).append("\n")
                .append("- Entrypoint types: ").append(result.options().enabledEntrypointTypes().stream()
                        .map(Enum::name).sorted().collect(Collectors.joining(", "))).append("\n")
                .append("- Total time: ").append(statistics.phaseMetrics().totalMillis()).append(" ms\n\n");
        if (!result.parseFailures().isEmpty()) {
            builder.append("Parse failures:\n\n");
            result.parseFailures().forEach(failure -> builder.append("- `")
                    .append(escape(failure.file().toString())).append("`: ")
                    .append(escape(failure.message())).append("\n"));
            builder.append('\n');
        }
    }

    private static void appendFlows(StringBuilder builder, AnalysisResult result) {
        builder.append("## Flow Overview\n\n");
        if (result.flows().isEmpty()) {
            builder.append("No supported entrypoints were found.\n\n");
            return;
        }
        for (var flow : result.flows()) {
            builder.append("### ").append(escape(flow.entrypoint().displayName())).append("\n\n")
                    .append("- Type: `").append(flow.entrypoint().type()).append("`\n")
                    .append("- Method: `").append(escape(flow.entrypoint().method().displayName())).append("`\n")
                    .append("- Confidence: `").append(flow.confidence()).append("`\n")
                    .append("- Reached methods: ").append(flow.methods().size()).append("\n")
                    .append("- Boundaries: ").append(flow.boundaries().size()).append("\n\n");
            for (var boundary : flow.boundaries()) {
                builder.append("  - `").append(boundary.resolutionReason()).append("` at `")
                        .append(escape(boundary.callSite().file().toString())).append(':')
                        .append(boundary.callSite().startLine()).append("`: ")
                        .append(escape(boundary.displayName())).append("\n");
            }
            if (!flow.boundaries().isEmpty()) {
                builder.append('\n');
            }
        }
    }

    private static void appendFindings(StringBuilder builder, AnalysisResult result) {
        builder.append("## Findings\n\n");
        if (result.findings().isEmpty()) {
            builder.append("No findings.\n");
            return;
        }
        for (var finding : result.findings()) {
            String flows = finding.relatedFlows().stream()
                    .map(flow -> escape(flow.displayName()) + " (`" + flow.confidence() + "`)")
                    .collect(Collectors.joining(", "));
            builder.append("### ").append(icon(finding.severity())).append(' ')
                    .append(finding.ruleId()).append("\n\n")
                    .append("- Fingerprint: `").append(finding.fingerprint()).append("`\n")
                    .append("- Severity: `").append(finding.severity()).append("`\n")
                    .append("- Confidence: `").append(finding.confidence()).append("`\n")
                    .append("- Location: `").append(escape(finding.location().file().toString())).append(':')
                    .append(finding.location().startLine()).append("`\n")
                    .append("- Related flows: ").append(flows).append("\n\n")
                    .append(escape(finding.message())).append("\n\n")
                    .append("**Recommendation:** ").append(escape(finding.recommendation())).append("\n\n");
            if (!finding.evidence().isEmpty()) {
                builder.append("Evidence:\n\n");
                finding.evidence().forEach((key, value) -> builder.append("- `")
                        .append(escape(key)).append("`: `").append(escape(value)).append("`\n"));
                builder.append('\n');
            }
        }
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
