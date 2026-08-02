package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.cli.report.JsonReporter;
import dev.diagscope.cli.report.MarkdownReporter;
import dev.diagscope.core.application.DiagnosticCoverageService;
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

    static CommandLine createCommandLine() {
        var ruleEngine = new RuleEngine(List.of(
                new SilentCatchRule(),
                new SilentFailureConversionRule(),
                new IgnoredKafkaSendResultRule(),
                new HighCardinalityMetricTagRule(),
                new PrintStackTraceRule(),
                new SystemOutputRule()
        ));
        var useCase = new DiagnosticCoverageService(
                new JavaParserProjectAnalyzer(),
                new LocalFlowBuilder(),
                ruleEngine
        );

        Map<ReportFormat, AnalysisReporter> reporters = new EnumMap<>(ReportFormat.class);
        reporters.put(ReportFormat.MARKDOWN, new MarkdownReporter());
        reporters.put(ReportFormat.JSON, new JsonReporter());

        return new CommandLine(new RootCommand())
                .addSubcommand("scan", new ScanCommand(useCase, reporters))
                .setCaseInsensitiveEnumValuesAllowed(true);
    }
}
