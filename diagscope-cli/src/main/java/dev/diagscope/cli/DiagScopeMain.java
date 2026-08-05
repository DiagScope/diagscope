package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.cli.report.HtmlReporter;
import dev.diagscope.cli.report.JsonReporter;
import dev.diagscope.cli.report.MarkdownReporter;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.rule.DynamicMetricNameRule;
import dev.diagscope.core.application.rule.HighCardinalityMetricTagRule;
import dev.diagscope.core.application.rule.IgnoredKafkaSendResultRule;
import dev.diagscope.core.application.rule.PrintStackTraceRule;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.application.rule.SilentCatchRule;
import dev.diagscope.core.application.rule.SilentFailureConversionRule;
import dev.diagscope.core.application.rule.SystemOutputRule;
import dev.diagscope.javaparser.JavaParserProjectAnalyzer;
import dev.diagscope.javaparser.LocalFlowBuilder;
import picocli.CommandLine;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class DiagScopeMain {
    private DiagScopeMain() {}

    public static void main(String[] args) {
        int exitCode = createCommandLine().execute(args);
        System.exit(exitCode);
    }

    static dev.diagscope.core.application.port.in.ScanProjectUseCase createScanUseCase() {
        var ruleEngine = new RuleEngine(List.of(
                new SilentCatchRule(),
                new SilentFailureConversionRule(),
                new IgnoredKafkaSendResultRule(),
                new HighCardinalityMetricTagRule(),
                new DynamicMetricNameRule(),
                new PrintStackTraceRule(),
                new SystemOutputRule()
        ));
        return new DiagnosticCoverageService(
                new JavaParserProjectAnalyzer(),
                new LocalFlowBuilder(),
                ruleEngine
        );
    }

    static CommandLine createCommandLine() {
        Map<ReportFormat, AnalysisReporter> reporters = new EnumMap<>(ReportFormat.class);
        reporters.put(ReportFormat.MARKDOWN, new MarkdownReporter());
        reporters.put(ReportFormat.JSON, new JsonReporter());
        reporters.put(ReportFormat.HTML, new HtmlReporter());

        return new CommandLine(new RootCommand())
                .addSubcommand("scan", new ScanCommand(createScanUseCase(), reporters))
                .setCaseInsensitiveEnumValuesAllowed(true);
    }
}
