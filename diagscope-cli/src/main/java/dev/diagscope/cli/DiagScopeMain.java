package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.cli.report.HtmlReporter;
import dev.diagscope.cli.report.JsonReporter;
import dev.diagscope.cli.report.MarkdownReporter;
import dev.diagscope.cli.report.SarifReporter;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.LocalFlowBuilder;
import dev.diagscope.core.application.rule.AsyncOnPrivateMethodRule;
import dev.diagscope.core.application.rule.AsyncResultUnobservedRule;
import dev.diagscope.core.application.rule.BulkOperationInLoopRule;
import dev.diagscope.core.application.rule.CheckThenActOnMapRule;
import dev.diagscope.core.application.rule.CompletableFutureExceptionNotHandledRule;
import dev.diagscope.core.application.rule.ExecutorNotShutdownRule;
import dev.diagscope.core.application.rule.InterruptedExceptionSwallowedRule;
import dev.diagscope.core.application.rule.KafkaDeadLetterNotConfiguredRule;
import dev.diagscope.core.application.rule.BlockingCallInCoroutineRule;
import dev.diagscope.core.application.rule.CorsWildcardOriginRule;
import dev.diagscope.core.application.rule.EntityExposedInRestResponseRule;
import dev.diagscope.core.application.rule.ExceptionSuppressedInFinallyRule;
import dev.diagscope.core.application.rule.HttpClientCreatedPerRequestRule;
import dev.diagscope.core.application.rule.KafkaRetryWithoutBackoffRule;
import dev.diagscope.core.application.rule.KafkaTopicHardcodedRule;
import dev.diagscope.core.application.rule.MassAssignmentRiskRule;
import dev.diagscope.core.application.rule.RetryOnAllExceptionsRule;
import dev.diagscope.core.application.rule.TransactionWithHttpCallRule;
import dev.diagscope.core.application.rule.ValueWithoutDefaultRule;
import dev.diagscope.core.application.rule.MissingPaginationRule;
import dev.diagscope.core.application.rule.TransactionalOnInterfaceRule;
import dev.diagscope.core.application.rule.CoroutineExceptionNotHandledRule;
import dev.diagscope.core.application.rule.FlowExceptionNotCaughtRule;
import dev.diagscope.core.application.rule.OutboxPatternMissingRule;
import dev.diagscope.core.application.rule.SecretInStringLiteralRule;
import dev.diagscope.core.application.rule.SpanNotClosedRule;
import dev.diagscope.core.application.rule.ExcessiveMethodParametersRule;
import dev.diagscope.core.application.rule.GodClassRule;
import dev.diagscope.core.application.rule.HighMethodComplexityRule;
import dev.diagscope.core.application.rule.HttpTimeoutNotSetRule;
import dev.diagscope.core.application.rule.MissingTransactionAnnotationRule;
import dev.diagscope.core.application.rule.BlockingCallInReactiveContextRule;
import dev.diagscope.core.application.rule.FallbackHidesFailureRule;
import dev.diagscope.core.application.rule.FutureGetWithoutTimeoutRule;
import dev.diagscope.core.application.rule.GenericExceptionMessageRule;
import dev.diagscope.core.application.rule.HttpClientErrorDiscardedRule;
import dev.diagscope.core.application.rule.LockNotReleasedRule;
import dev.diagscope.core.application.rule.LogWithoutThrowableRule;
import dev.diagscope.core.application.rule.MetricCreatedInLoopRule;
import dev.diagscope.core.application.rule.NPlusOneQueryRiskRule;
import dev.diagscope.core.application.rule.OptionalGetWithoutCheckRule;
import dev.diagscope.core.application.rule.RetryWithoutDiagnosticsRule;
import dev.diagscope.core.application.rule.ScheduledExceptionNotHandledRule;
import dev.diagscope.core.application.rule.ScheduledTaskSwallowsFailureRule;
import dev.diagscope.core.application.rule.ThreadLocalLeakRule;
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
        var flowRules = List.of(
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
                new TransactionalPropagationMismatchRule(),
                // Concurrency & threads
                new LockNotReleasedRule(),
                new ThreadLocalLeakRule(),
                new FutureGetWithoutTimeoutRule(),
                new BlockingCallInReactiveContextRule(),
                // Performance
                new NPlusOneQueryRiskRule(),
                // Null safety
                new OptionalGetWithoutCheckRule(),
                // Transactions
                new MissingTransactionAnnotationRule(),
                // Resilience: HTTP timeout
                new HttpTimeoutNotSetRule(),
                // Maintainability
                new ExcessiveMethodParametersRule(),
                new HighMethodComplexityRule(),
                // Concurrency: atomic operations
                new CheckThenActOnMapRule(),
                // Exception handling: interrupt contract
                new InterruptedExceptionSwallowedRule(),
                // Concurrency: CompletableFuture exception handling
                new CompletableFutureExceptionNotHandledRule(),
                // Concurrency: executor lifecycle
                new ExecutorNotShutdownRule(),
                // AOP / Proxy: async visibility
                new AsyncOnPrivateMethodRule(),
                // Resilience: scheduler error boundary
                new ScheduledExceptionNotHandledRule(),
                // Database / Performance: bulk writes in loops
                new BulkOperationInLoopRule(),
                // Observability: unclosed spans
                new SpanNotClosedRule(),
                // Kafka: dead-letter topic not configured
                new KafkaDeadLetterNotConfiguredRule()
        );
        var fullEngine = new RuleEngine(flowRules, List.of(
                new GodClassRule(),
                new OutboxPatternMissingRule(),
                new SecretInStringLiteralRule(),
                new CoroutineExceptionNotHandledRule(),
                new FlowExceptionNotCaughtRule(),
                new BlockingCallInCoroutineRule(),
                new TransactionalOnInterfaceRule(),
                new MissingPaginationRule(),
                new KafkaRetryWithoutBackoffRule(),
                new ExceptionSuppressedInFinallyRule(),
                new MassAssignmentRiskRule(),
                // Resilience: retry exception scope
                new RetryOnAllExceptionsRule(),
                // Configuration: @Value without default
                new ValueWithoutDefaultRule(),
                // Security: entity returned in REST response
                new EntityExposedInRestResponseRule(),
                // Performance: HTTP call inside transaction
                new TransactionWithHttpCallRule(),
                // Performance: HTTP client created per request
                new HttpClientCreatedPerRequestRule(),
                // Security: CORS wildcard origin
                new CorsWildcardOriginRule(),
                // Kafka / Configuration: hardcoded topic name
                new KafkaTopicHardcodedRule()
        ));
        return new DiagnosticCoverageService(
                new CompositeProjectAnalyzer(List.of(
                        new JavaParserProjectAnalyzer(),
                        new KotlinParserProjectAnalyzer()
                )),
                new LocalFlowBuilder(),
                fullEngine
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
                dev.diagscope.core.application.rule.TransactionalPropagationMismatchRule.ID,
                dev.diagscope.core.application.rule.LockNotReleasedRule.ID,
                dev.diagscope.core.application.rule.ThreadLocalLeakRule.ID,
                dev.diagscope.core.application.rule.FutureGetWithoutTimeoutRule.ID,
                dev.diagscope.core.application.rule.BlockingCallInReactiveContextRule.ID,
                dev.diagscope.core.application.rule.NPlusOneQueryRiskRule.ID,
                dev.diagscope.core.application.rule.OptionalGetWithoutCheckRule.ID,
                dev.diagscope.core.application.rule.ExcessiveMethodParametersRule.ID,
                dev.diagscope.core.application.rule.HighMethodComplexityRule.ID,
                dev.diagscope.core.application.rule.CheckThenActOnMapRule.ID,
                dev.diagscope.core.application.rule.MissingTransactionAnnotationRule.ID,
                dev.diagscope.core.application.rule.HttpTimeoutNotSetRule.ID,
                dev.diagscope.core.application.rule.GodClassRule.ID,
                // Exception handling: interrupt contract
                dev.diagscope.core.application.rule.InterruptedExceptionSwallowedRule.ID,
                // Concurrency: CompletableFuture exception handling
                dev.diagscope.core.application.rule.CompletableFutureExceptionNotHandledRule.ID,
                // Concurrency: executor lifecycle
                dev.diagscope.core.application.rule.ExecutorNotShutdownRule.ID,
                // AOP / Proxy: async visibility
                dev.diagscope.core.application.rule.AsyncOnPrivateMethodRule.ID,
                // Resilience: scheduler error boundary
                dev.diagscope.core.application.rule.ScheduledExceptionNotHandledRule.ID,
                // Database / Performance: bulk writes in loops
                dev.diagscope.core.application.rule.BulkOperationInLoopRule.ID,
                // Observability: unclosed spans
                dev.diagscope.core.application.rule.SpanNotClosedRule.ID,
                // Kafka: dead-letter topic not configured
                dev.diagscope.core.application.rule.KafkaDeadLetterNotConfiguredRule.ID,
                // Kafka / Transactions: transactional outbox
                dev.diagscope.core.application.rule.OutboxPatternMissingRule.ID,
                // Security: hardcoded secrets
                dev.diagscope.core.application.rule.SecretInStringLiteralRule.ID,
                // Kotlin coroutines: unhandled exceptions
                dev.diagscope.core.application.rule.CoroutineExceptionNotHandledRule.ID,
                // Kotlin coroutines: uncaught Flow exceptions
                dev.diagscope.core.application.rule.FlowExceptionNotCaughtRule.ID,
                // Kotlin coroutines: blocking calls in coroutine builders
                dev.diagscope.core.application.rule.BlockingCallInCoroutineRule.ID,
                // AOP / Proxy: @Transactional on interface
                dev.diagscope.core.application.rule.TransactionalOnInterfaceRule.ID,
                // Database / Performance: missing pagination
                dev.diagscope.core.application.rule.MissingPaginationRule.ID,
                // Kafka: retry without exponential backoff
                dev.diagscope.core.application.rule.KafkaRetryWithoutBackoffRule.ID,
                // Exception handling: throw in finally discards original exception
                dev.diagscope.core.application.rule.ExceptionSuppressedInFinallyRule.ID,
                // Security: JPA entity used directly as @RequestBody
                dev.diagscope.core.application.rule.MassAssignmentRiskRule.ID,
                // Resilience: retry exception scope
                dev.diagscope.core.application.rule.RetryOnAllExceptionsRule.ID,
                // Configuration: @Value without default
                dev.diagscope.core.application.rule.ValueWithoutDefaultRule.ID,
                // Security: entity returned in REST response
                dev.diagscope.core.application.rule.EntityExposedInRestResponseRule.ID,
                // Performance: HTTP call inside transaction
                dev.diagscope.core.application.rule.TransactionWithHttpCallRule.ID,
                // Performance: HTTP client created per request
                dev.diagscope.core.application.rule.HttpClientCreatedPerRequestRule.ID,
                // Security: CORS wildcard origin
                dev.diagscope.core.application.rule.CorsWildcardOriginRule.ID,
                // Kafka / Configuration: hardcoded topic name
                dev.diagscope.core.application.rule.KafkaTopicHardcodedRule.ID
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
