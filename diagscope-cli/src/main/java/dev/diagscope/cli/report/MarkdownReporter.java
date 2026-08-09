package dev.diagscope.cli.report;

import dev.diagscope.cli.BuildInfo;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.application.rule.RuleCatalog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        appendExecutiveSummary(builder, result);
        appendFindings(builder, result);
        appendAspects(builder, result);
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
                .append("| Build system | ").append(result.buildSystem().displayName())
                .append(result.projectLayout().isMultiModule()
                        ? " (" + result.projectLayout().modules().size() + " modules)" : "")
                .append(" |\n")
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
                        .map(Enum::name).sorted().collect(Collectors.joining(", "))).append("\n")
                .append("- Explicit classpath entries: ")
                .append(result.options().explicitClasspath().isEmpty() ? "none"
                        : result.options().explicitClasspath().stream().map(Path::toString)
                                .collect(Collectors.joining(", "))).append("\n")
                .append("- Additional source roots: ")
                .append(result.options().additionalSourceRoots().isEmpty() ? "none"
                        : result.options().additionalSourceRoots().stream().map(Path::toString)
                                .collect(Collectors.joining(", "))).append("\n")
                .append("- Project policy: ").append(valueOrNone(result.scanPolicy().configurationFile()))
                .append("\n")
                .append("- Disabled rules: ").append(result.options().policy().disabledRules().isEmpty()
                        ? "none" : result.options().policy().disabledRules().stream().sorted()
                                .collect(Collectors.joining(", "))).append("\n")
                .append("- Baseline: ").append(valueOrNone(result.scanPolicy().baselineFile()))
                .append(" (suppressed ").append(result.scanPolicy().baselineSuppressedFindings())
                .append(")\n")
                .append("- Changed since: ").append(valueOrNone(result.scanPolicy().changedSince()))
                .append(" (excluded ").append(result.scanPolicy().changeScopeExcludedFindings())
                .append(")\n\n")
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

    private static String valueOrNone(String value) {
        return value == null || value.isBlank() ? "none" : '`' + escape(value) + '`';
    }

    /**
     * Counts per rule and per confidence level, printed before the detailed findings so the state of
     * the project is readable in seconds.
     */
    private static void appendExecutiveSummary(StringBuilder builder, AnalysisResult result) {
        var findings = result.findings();
        builder.append("## Executive summary\n\n");
        if (findings.isEmpty()) {
            builder.append("No findings. Every analyzed flow preserves diagnostic evidence.\n\n");
            return;
        }
        builder.append(findings.size()).append(" finding(s): ")
                .append(countBySeverity(result, "ERROR")).append(" error(s), ")
                .append(countBySeverity(result, "WARNING")).append(" warning(s), ")
                .append(countBySeverity(result, "INFO")).append(" info. ")
                .append(countByConfidence(result, "HIGH")).append(" are high confidence and worth")
                .append(" triaging first.\n\n")
                .append("### Findings by rule\n\n")
                .append("| Rule | What it flags | Findings | Highest severity | High | Medium | Low |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        var byRule = new java.util.TreeMap<String, java.util.List<dev.diagscope.core.domain.Finding>>();
        findings.forEach(finding -> byRule
                .computeIfAbsent(finding.ruleId(), key -> new java.util.ArrayList<>()).add(finding));
        byRule.entrySet().stream()
                .sorted(java.util.Comparator
                        .<java.util.Map.Entry<String, java.util.List<dev.diagscope.core.domain.Finding>>>
                                comparingInt(entry -> -entry.getValue().size())
                        .thenComparing(java.util.Map.Entry::getKey))
                .forEach(entry -> {
                    var rows = entry.getValue();
                    builder.append("| `").append(entry.getKey()).append("` | ")
                            .append(escape(RuleCatalog.explain(entry.getKey()).title())).append(" | ")
                            .append(rows.size()).append(" | `").append(highestSeverity(rows)).append("` | ")
                            .append(countConfidence(rows, "HIGH")).append(" | ")
                            .append(countConfidence(rows, "MEDIUM")).append(" | ")
                            .append(countConfidence(rows, "LOW")).append(" |\n");
                });
        builder.append("\n### Findings by confidence\n\n")
                .append("| Confidence | Findings | What it means |\n| --- | --- | --- |\n");
        var levels = new java.util.ArrayList<>(
                java.util.List.of(dev.diagscope.core.domain.Confidence.values()));
        java.util.Collections.reverse(levels);
        for (var confidence : levels) {
            builder.append("| `").append(confidence).append("` | ")
                    .append(countByConfidence(result, confidence.name())).append(" | ")
                    .append(escape(RuleCatalog.confidenceRationale(confidence))).append(" |\n");
        }
        builder.append('\n');
    }

    private static String highestSeverity(java.util.List<dev.diagscope.core.domain.Finding> findings) {
        return findings.stream().map(finding -> finding.severity().name())
                .min(java.util.Comparator.comparingInt(
                        name -> java.util.List.of("ERROR", "WARNING", "INFO").indexOf(name)))
                .orElse("INFO");
    }

    private static long countConfidence(java.util.List<dev.diagscope.core.domain.Finding> findings,
                                        String confidence) {
        return findings.stream().filter(finding -> finding.confidence().name().equals(confidence)).count();
    }

    private static long countByConfidence(AnalysisResult result, String confidence) {
        return countConfidence(result.findings(), confidence);
    }

    private static void appendFindings(StringBuilder builder, AnalysisResult result) {
        builder.append("## Findings\n\n");
        if (result.findings().isEmpty()) {
            builder.append("No findings. Every analyzed flow preserves diagnostic evidence.\n\n");
            return;
        }
        for (var finding : result.findings()) {
            String flows = finding.relatedFlows().stream()
                    .map(flow -> escape(flow.displayName()) + " (`" + flow.confidence() + "`, depth "
                            + flow.depth() + ")")
                    .collect(Collectors.joining(", "));
            builder.append("### ").append(icon(finding.severity())).append(' ')
                    .append(finding.ruleId()).append(" — `")
                    .append(escape(finding.location().file().toString())).append(':')
                    .append(finding.location().startLine()).append("`\n\n")
                    .append(escape(finding.message())).append("\n\n");
            var doc = RuleCatalog.explain(finding.ruleId());
            builder.append("**What this means:** ").append(escape(doc.whatItMeans())).append("\n\n")
                    .append("**Why it matters:** ").append(escape(doc.whyItMatters())).append("\n\n")
                    .append("**How it was detected:** ").append(escape(doc.howDetected())).append("\n\n")
                    .append("**Suggested action:** ").append(escape(finding.recommendation())).append("\n\n")
                    .append("- Severity: `").append(finding.severity())
                    .append("` · Confidence: `").append(finding.confidence()).append("`\n")
                    .append("- Confidence means: ")
                    .append(escape(RuleCatalog.confidenceRationale(finding.confidence()))).append("\n")
                    .append("- Affected flows: ").append(flows.isEmpty() ? "none" : flows).append("\n")
                    .append("- Fingerprint: `").append(finding.fingerprint()).append("`\n\n");
            appendCallPaths(builder, finding);
            if (!finding.evidence().isEmpty()) {
                builder.append("<details><summary>Evidence</summary>\n\n");
                finding.evidence().forEach((key, value) -> builder.append("- `")
                        .append(escape(key)).append("`: `").append(escape(value)).append("`\n"));
                builder.append("\n</details>\n\n");
            }
        }
    }

    /** Renders the traced call path from every affected entrypoint down to the evidence method. */
    private static void appendCallPaths(StringBuilder builder, dev.diagscope.core.domain.Finding finding) {
        if (finding.relatedFlows().isEmpty()) {
            return;
        }
        builder.append("<details><summary>Call paths (").append(finding.relatedFlows().size())
                .append(")</summary>\n\n");
        for (var flow : finding.relatedFlows()) {
            builder.append("- `").append(flow.entrypointType()).append("` ")
                    .append(escape(flow.displayName())).append("\n");
            var path = flow.path();
            for (int index = 0; index < path.size(); index++) {
                builder.append("  ").append("  ".repeat(index)).append("- `")
                        .append(escape(path.get(index))).append('`')
                        .append(index == path.size() - 1 ? " ← evidence" : "")
                        .append("\n");
            }
        }
        builder.append("\n</details>\n\n");
    }

    /**
     * Lists the advice that instruments the code without appearing at any call site, so a reader can
     * tell which behaviour is attached by a proxy rather than written in the method.
     */
    private static void appendAspects(StringBuilder builder, AnalysisResult result) {
        if (result.aspects().isEmpty()) {
            return;
        }
        builder.append("## Indirect instrumentation (Spring AOP)\n\n")
                .append("Advice runs around your methods without being visible at the call site. It only")
                .append(" applies to calls that go through the Spring proxy.\n\n")
                .append("| Advice | Kind | Pointcut | Declared at |\n| --- | --- | --- | --- |\n");
        for (var advice : result.aspects()) {
            builder.append("| `").append(escape(advice.id()))
                    .append("` | `").append(advice.kind().annotation())
                    .append("` | `").append(escape(advice.pointcut().isBlank() ? "(none)" : advice.pointcut()))
                    .append("` | `").append(escape(advice.location().file().toString())).append(':')
                    .append(advice.location().startLine()).append("` |\n");
        }
        builder.append('\n');
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
