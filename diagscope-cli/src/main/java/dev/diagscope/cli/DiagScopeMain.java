package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.cli.report.HtmlReporter;
import dev.diagscope.cli.report.JsonReporter;
import dev.diagscope.cli.report.MarkdownReporter;
import dev.diagscope.cli.report.SarifReporter;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.rule.AsyncResultUnobservedRule;
import dev.diagscope.core.application.rule.FallbackHidesFailureRule;
import dev.diagscope.core.application.rule.GenericExceptionMessageRule;
import dev.diagscope.core.application.rule.HttpClientErrorDiscardedRule;
import dev.diagscope.core.application.rule.LogWithoutThrowableRule;
import dev.diagscope.core.application.rule.MetricCreatedInLoopRule;
import dev.diagscope.core.application.rule.RetryWithoutDiagnosticsRule;
import dev.diagscope.core.application.rule.ScheduledTaskSwallowsFailureRule;
import dev.diagscope.core.application.rule.SensitivePayloadLoggedRule;
import dev.diagscope.core.application.rule.DynamicMetricNameRule;
import dev.diagscope.core.application.rule.HighCardinalityMetricTagRule;
import dev.diagscope.core.application.rule.IgnoredKafkaSendResultRule;
import dev.diagscope.core.application.rule.DatabaseResourceCloseNotGuardedRule;
import dev.diagscope.core.application.rule.EntityManagerLeakRule;
import dev.diagscope.core.application.rule.JdbcResourceLeakRule;
import dev.diagscope.core.application.rule.JdbcTemplateConnectionEscapeRule;
import dev.diagscope.core.application.rule.KafkaListenerFailureNotPropagatedRule;
import dev.diagscope.core.application.rule.KafkaManualAckMissingRule;
import dev.diagscope.core.application.rule.TransactionalRollbackSuppressedRule;
import dev.diagscope.core.application.rule.NonProxyableAdviceTargetRule;
import dev.diagscope.core.application.rule.PrintStackTraceRule;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.application.rule.SelfInvocationProxyBypassRule;
import dev.diagscope.core.application.rule.SilentCatchRule;
import dev.diagscope.core.application.rule.SilentFailureConversionRule;
import dev.diagscope.core.application.rule.SystemOutputRule;
import dev.diagscope.core.application.rule.UnmanagedAdviceTargetRule;
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
                new KafkaManualAckMissingRule(),
                new KafkaListenerFailureNotPropagatedRule(),
                new TransactionalRollbackSuppressedRule(),
                new JdbcResourceLeakRule(),
                new DatabaseResourceCloseNotGuardedRule(),
                new EntityManagerLeakRule(),
                new JdbcTemplateConnectionEscapeRule(),
                new HighCardinalityMetricTagRule(),
                new DynamicMetricNameRule(),
                new PrintStackTraceRule(),
                new SystemOutputRule(),
                new SelfInvocationProxyBypassRule(),
                new NonProxyableAdviceTargetRule(),
                new UnmanagedAdviceTargetRule(),
                new LogWithoutThrowableRule(),
                new GenericExceptionMessageRule(),
                new AsyncResultUnobservedRule(),
                new HttpClientErrorDiscardedRule(),
                new ScheduledTaskSwallowsFailureRule(),
                new RetryWithoutDiagnosticsRule(),
                new FallbackHidesFailureRule(),
                new MetricCreatedInLoopRule(),
                new SensitivePayloadLoggedRule()
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
        reporters.put(ReportFormat.SARIF, new SarifReporter());

        return new CommandLine(new RootCommand())
                .addSubcommand("scan", new ScanCommand(createScanUseCase(), reporters))
                .setCaseInsensitiveEnumValuesAllowed(true);
    }
}
