package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.cli.report.HtmlReporter;
import dev.diagscope.cli.report.JsonReporter;
import dev.diagscope.cli.report.MarkdownReporter;
import dev.diagscope.cli.report.SarifReporter;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.LocalFlowBuilder;
import dev.diagscope.core.application.rule.AsyncResultUnobservedRule;
import dev.diagscope.core.application.rule.FallbackHidesFailureRule;
import dev.diagscope.core.application.rule.GenericExceptionMessageRule;
import dev.diagscope.core.application.rule.HttpClientErrorDiscardedRule;
import dev.diagscope.core.application.rule.LogWithoutThrowableRule;
import dev.diagscope.core.application.rule.MetricCreatedInLoopRule;
import dev.diagscope.core.application.rule.RetryWithoutDiagnosticsRule;
import dev.diagscope.core.application.rule.ScheduledTaskSwallowsFailureRule;
import dev.diagscope.core.application.rule.DuplicateDiagnosticSignalRule;
import dev.diagscope.core.application.rule.MdcContextLostRule;
import dev.diagscope.core.application.rule.MutinyFailureRecoveredSilentlyRule;
import dev.diagscope.core.application.rule.MutinySubscriptionFailureUnobservedRule;
import dev.diagscope.core.application.rule.ReactiveMessageFailureNotPropagatedRule;
import dev.diagscope.core.application.rule.SensitivePayloadLoggedRule;
import dev.diagscope.core.application.rule.TransactionalPropagationMismatchRule;
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
import dev.diagscope.jvmanalysis.CompositeProjectAnalyzer;
import dev.diagscope.kotlinparser.KotlinParserProjectAnalyzer;
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

    /** Fully wired scan engine; reused by the CLI, build-tool plugins and CI wrappers. */
    public static dev.diagscope.core.application.port.in.ScanProjectUseCase createScanUseCase() {
        var ruleEngine = new RuleEngine(List.of(
                new SilentCatchRule(),
                new SilentFailureConversionRule(),
                new IgnoredKafkaSendResultRule(),
                new KafkaManualAckMissingRule(),
                new KafkaListenerFailureNotPropagatedRule(),
                new ReactiveMessageFailureNotPropagatedRule(),
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
                new MutinyFailureRecoveredSilentlyRule(),
                new MutinySubscriptionFailureUnobservedRule(),
                new ScheduledTaskSwallowsFailureRule(),
                new RetryWithoutDiagnosticsRule(),
                new FallbackHidesFailureRule(),
                new MetricCreatedInLoopRule(),
                new SensitivePayloadLoggedRule(),
                new MdcContextLostRule(),
                new DuplicateDiagnosticSignalRule(),
                new TransactionalPropagationMismatchRule()
        ));
        return new DiagnosticCoverageService(
                new CompositeProjectAnalyzer(List.of(
                        new JavaParserProjectAnalyzer(),
                        new KotlinParserProjectAnalyzer()
                )),
                new LocalFlowBuilder(),
                ruleEngine
        );
    }

    /**
     * Returns the rule identifiers registered with the default rule engine.
     *
     * <p>This is the authoritative set of active rules in the current release. It is used by
     * {@code RuleDocumentationContractTest} to verify that every registered rule has a catalog
     * entry and that the catalog contains no undeclared identifiers.</p>
     */
    public static List<String> registeredRuleIds() {
        return List.of(
                dev.diagscope.core.application.rule.SilentCatchRule.ID,
                dev.diagscope.core.application.rule.SilentFailureConversionRule.ID,
                dev.diagscope.core.application.rule.IgnoredKafkaSendResultRule.ID,
                dev.diagscope.core.application.rule.KafkaManualAckMissingRule.ID,
                dev.diagscope.core.application.rule.KafkaListenerFailureNotPropagatedRule.ID,
                dev.diagscope.core.application.rule.ReactiveMessageFailureNotPropagatedRule.ID,
                dev.diagscope.core.application.rule.TransactionalRollbackSuppressedRule.ID,
                dev.diagscope.core.application.rule.JdbcResourceLeakRule.ID,
                dev.diagscope.core.application.rule.DatabaseResourceCloseNotGuardedRule.ID,
                dev.diagscope.core.application.rule.EntityManagerLeakRule.ID,
                dev.diagscope.core.application.rule.JdbcTemplateConnectionEscapeRule.ID,
                dev.diagscope.core.application.rule.HighCardinalityMetricTagRule.ID,
                dev.diagscope.core.application.rule.DynamicMetricNameRule.ID,
                dev.diagscope.core.application.rule.PrintStackTraceRule.ID,
                dev.diagscope.core.application.rule.SystemOutputRule.ID,
                dev.diagscope.core.application.rule.SelfInvocationProxyBypassRule.ID,
                dev.diagscope.core.application.rule.NonProxyableAdviceTargetRule.ID,
                dev.diagscope.core.application.rule.UnmanagedAdviceTargetRule.ID,
                dev.diagscope.core.application.rule.LogWithoutThrowableRule.ID,
                dev.diagscope.core.application.rule.GenericExceptionMessageRule.ID,
                dev.diagscope.core.application.rule.AsyncResultUnobservedRule.ID,
                dev.diagscope.core.application.rule.HttpClientErrorDiscardedRule.ID,
                dev.diagscope.core.application.rule.MutinyFailureRecoveredSilentlyRule.ID,
                dev.diagscope.core.application.rule.MutinySubscriptionFailureUnobservedRule.ID,
                dev.diagscope.core.application.rule.ScheduledTaskSwallowsFailureRule.ID,
                dev.diagscope.core.application.rule.RetryWithoutDiagnosticsRule.ID,
                dev.diagscope.core.application.rule.FallbackHidesFailureRule.ID,
                dev.diagscope.core.application.rule.MetricCreatedInLoopRule.ID,
                dev.diagscope.core.application.rule.SensitivePayloadLoggedRule.ID,
                dev.diagscope.core.application.rule.MdcContextLostRule.ID,
                dev.diagscope.core.application.rule.DuplicateDiagnosticSignalRule.ID,
                dev.diagscope.core.application.rule.TransactionalPropagationMismatchRule.ID
        );
    }

    /** Every reporter DiagScope ships, keyed by report format. */
    public static Map<ReportFormat, AnalysisReporter> createReporters() {
        Map<ReportFormat, AnalysisReporter> reporters = new EnumMap<>(ReportFormat.class);
        reporters.put(ReportFormat.MARKDOWN, new MarkdownReporter());
        reporters.put(ReportFormat.JSON, new JsonReporter());
        reporters.put(ReportFormat.HTML, new HtmlReporter());
        reporters.put(ReportFormat.SARIF, new SarifReporter());
        return Map.copyOf(reporters);
    }

    /** Scan engine ready to embed, with the default rule set and reporters. */
    public static ScanWorkflow createScanWorkflow() {
        return new ScanWorkflow(createScanUseCase(), createReporters());
    }

    static CommandLine createCommandLine() {
        return new CommandLine(new RootCommand())
                .addSubcommand("scan", new ScanCommand(createScanWorkflow()))
                .addSubcommand("trend", new TrendCommand())
                .addSubcommand("doctor", new DoctorCommand(createScanUseCase()))
                .addSubcommand("rules", new RulesCommand())
                .addSubcommand("explain", new ExplainCommand())
                .setCaseInsensitiveEnumValuesAllowed(true);
    }
}
