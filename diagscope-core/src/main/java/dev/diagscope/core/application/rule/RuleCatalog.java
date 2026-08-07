package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Human-readable documentation for every diagnostic rule.
 *
 * <p>A finding carries a one-line message so tools stay terse. Reports, however, are read by people
 * who may not know the rule, so every finding is enriched here with what the rule means, why the
 * loss of evidence matters at runtime, how DiagScope detected it, and what the reported confidence
 * level actually implies for triage.</p>
 *
 * <p>The catalog is intentionally static text: it never participates in the finding fingerprint, so
 * wording can be improved without invalidating baselines or suppressions.</p>
 */
public final class RuleCatalog {

    /**
     * Detailed, presentation-only documentation for a rule.
     *
     * @param ruleId       the rule identifier
     * @param title        short human title for the rule
     * @param whatItMeans  plain description of the reported situation
     * @param whyItMatters the diagnostic consequence at runtime
     * @param howDetected  the deterministic signal DiagScope used
     */
    public record RuleExplanation(
            String ruleId,
            String title,
            String whatItMeans,
            String whyItMatters,
            String howDetected
    ) {
        public RuleExplanation {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(whatItMeans, "whatItMeans");
            Objects.requireNonNull(whyItMatters, "whyItMatters");
            Objects.requireNonNull(howDetected, "howDetected");
        }
    }

    private static final Map<String, RuleExplanation> EXPLANATIONS = buildExplanations();

    private static final String GENERIC_TITLE = "Diagnostic evidence loss";

    private RuleCatalog() {
    }

    /** Returns the documentation for a rule, or a neutral fallback for rules added by plugins. */
    public static RuleExplanation explain(String ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        return Optional.ofNullable(EXPLANATIONS.get(ruleId)).orElseGet(() -> new RuleExplanation(
                ruleId,
                GENERIC_TITLE,
                "This rule reported a place where diagnostic evidence is weakened or lost.",
                "When evidence is missing, an incident on this flow has to be reconstructed from"
                        + " indirect signals instead of from the code path itself.",
                "Reported by a rule without catalog documentation; inspect the evidence table below."
        ));
    }

    /**
     * Explains what the reported confidence level means for triage.
     *
     * <p>Confidence is never a measure of how bad the problem is (that is severity). It states how
     * certain the static analysis is that the situation really happens on a reachable path.</p>
     */
    public static String confidenceRationale(Confidence confidence) {
        Objects.requireNonNull(confidence, "confidence");
        return switch (confidence) {
            case HIGH -> "HIGH — the evidence is explicit in the source and the call path from the"
                    + " entrypoint was resolved without ambiguity. Treat it as a real finding.";
            case MEDIUM -> "MEDIUM — the evidence is explicit, but part of the reasoning depends on"
                    + " resolution that static analysis cannot fully prove (interface or proxy"
                    + " dispatch, framework wiring, or a pointcut approximation). Confirm the"
                    + " runtime wiring before acting.";
            case LOW -> "LOW — the situation is plausible but depends on runtime behaviour DiagScope"
                    + " cannot observe (dynamic targets, global handlers, or deep or ambiguous call"
                    + " edges). Use it as a hint, not as a defect.";
        };
    }

    /** Returns every documented rule, keyed by rule id, in catalog order. */
    public static Map<String, RuleExplanation> all() {
        return EXPLANATIONS;
    }

    private static Map<String, RuleExplanation> buildExplanations() {
        var catalog = new LinkedHashMap<String, RuleExplanation>();
        put(catalog, SilentCatchRule.ID, "Exception caught and ignored",
                "An exception is caught in a block that does nothing with it: no log, no rethrow, no"
                        + " recovery action recorded.",
                "The failure disappears at this line. The caller keeps running as if the operation"
                        + " succeeded, and no trace of the original error reaches logs or traces.",
                "The catch block body is empty (or only contains comments) and carries no DiagScope"
                        + " suppression.");
        put(catalog, SilentFailureConversionRule.ID, "Failure converted into a normal value",
                "An exception is caught and turned into a benign result such as null, an empty"
                        + " collection, false, or a default value.",
                "Downstream code cannot distinguish 'no data' from 'the call failed', so the incident"
                        + " surfaces later as wrong data instead of as an error.",
                "The catch block returns a constant or empty value and never logs, rethrows, or"
                        + " records the cause.");
        put(catalog, PrintStackTraceRule.ID, "Stack trace printed instead of logged",
                "The exception is reported through printStackTrace() rather than through the"
                        + " application logger.",
                "The trace goes to standard error, bypassing log levels, structured fields, and"
                        + " correlation ids, so it is usually invisible in centralized logging.",
                "A direct call to Throwable.printStackTrace() on a reachable path.");
        put(catalog, SystemOutputRule.ID, "Diagnostics written to standard output",
                "Diagnostic text is written with System.out or System.err instead of the logger.",
                "The message escapes log routing, sampling, and correlation, so it cannot be searched"
                        + " or alerted on when the incident happens.",
                "A direct call to System.out/System.err print methods on a reachable path.");
        put(catalog, IgnoredKafkaSendResultRule.ID, "Kafka send result ignored",
                "The asynchronous result of a Kafka producer send is discarded.",
                "A broker-side failure completes the future exceptionally and is never observed, so"
                        + " the message is lost while the code reports success.",
                "The send() result is neither assigned, chained with a completion callback, awaited,"
                        + " nor returned, and no producer listener was detected.");
        put(catalog, KafkaManualAckMissingRule.ID, "Manual Kafka acknowledgement never invoked",
                "A listener declares manual acknowledgement but never calls acknowledge() on some"
                        + " path.",
                "Offsets are not committed for that path, causing redelivery loops or stalled"
                        + " partitions that look like a consumer outage.",
                "The listener signature accepts an Acknowledgment parameter that is never invoked in"
                        + " the method body.");
        put(catalog, KafkaListenerFailureNotPropagatedRule.ID, "Kafka listener swallows the failure",
                "A listener catches an exception and does not rethrow it.",
                "The container never sees the error, so error handlers, retries, and dead-letter"
                        + " routing are all skipped and the record is silently dropped.",
                "A catch block inside a @KafkaListener/@KafkaHandler method that neither rethrows nor"
                        + " records the failure.");
        put(catalog, HighCardinalityMetricTagRule.ID, "High-cardinality metric tag",
                "A metric tag value comes from unbounded data such as an id, a user input, or a"
                        + " message payload.",
                "Each distinct value creates a new time series, which inflates cost and can degrade"
                        + " or break the metrics backend exactly during an incident.",
                "The tag value is not a literal, constant, or enum, and resolves to a caller-supplied"
                        + " or dynamically computed expression.");
        put(catalog, DynamicMetricNameRule.ID, "Metric name is not constant",
                "The meter name itself is computed at runtime instead of being a constant.",
                "Dashboards and alerts bind to fixed metric names; a computed name makes the series"
                        + " unqueryable and silently breaks existing alerting.",
                "The name argument of the meter registration is not a compile-time constant.");
        put(catalog, SelfInvocationProxyBypassRule.ID, "Advice bypassed by self-invocation",
                "An annotated method is called through this, so the call does not go through the"
                        + " Spring proxy.",
                "Transactions, retries, caching, or observability advice attached to that method never"
                        + " run, so the guarantees the annotation promises are silently absent.",
                "An internal call on this to a method matched by an advice pointcut or a proxied"
                        + " annotation.");
        put(catalog, NonProxyableAdviceTargetRule.ID, "Advice cannot apply to this method",
                "Advice targets a method that a proxy can never intercept, typically private, final,"
                        + " or static.",
                "The instrumentation looks configured but never executes, so the method appears"
                        + " covered while producing no telemetry or transactional behaviour.",
                "A pointcut match against a method whose visibility or modifiers make it"
                        + " non-proxyable under the detected proxy mode.");
        put(catalog, UnmanagedAdviceTargetRule.ID, "Advice target is not a managed bean",
                "Advice matches a class that does not appear to be a Spring-managed bean.",
                "Instances created with new are never wrapped by a proxy, so the advice is absent for"
                        + " every call made through them.",
                "A pointcut match against a type with no stereotype or bean declaration detected in"
                        + " the analyzed sources.");
        put(catalog, TransactionalRollbackSuppressedRule.ID, "Transaction rollback suppressed",
                "An exception inside a @Transactional method is caught and not rethrown or marked"
                        + " rollback-only.",
                "The transaction commits partial work: the database ends in a state the code never"
                        + " intended, and no error is reported to the caller.",
                "A catch block in a transactional method with no rethrow and no"
                        + " setRollbackOnly() call.");
        put(catalog, JdbcResourceLeakRule.ID, "JDBC resource not closed",
                "A Connection, Statement, PreparedStatement, or ResultSet is acquired outside"
                        + " try-with-resources and never closed.",
                "Pool exhaustion appears far from this code, as timeouts in unrelated requests, which"
                        + " makes the real cause very hard to find.",
                "The resource acquisition is not a try-with-resources binding and no close() call was"
                        + " found for it in the method.");
        put(catalog, DatabaseResourceCloseNotGuardedRule.ID, "Resource closed only on the happy path",
                "The resource is closed, but the close() call is not inside a finally block or a"
                        + " try-with-resources binding.",
                "If anything throws before that line, the resource leaks; the leak only shows up"
                        + " under failure, which is exactly when capacity matters most.",
                "A close() call reachable only on the normal path, with no enclosing finally block"
                        + " and no resource binding.");
        put(catalog, EntityManagerLeakRule.ID, "EntityManager never closed",
                "An EntityManager created from a factory is never closed in the method that created"
                        + " it.",
                "The persistence context and its connection stay held, leaking memory and pool"
                        + " capacity until the process degrades.",
                "A createEntityManager() result with no matching close() call in the same method.");
        put(catalog, LogWithoutThrowableRule.ID, "Failure logged without the exception",
                "An error or warning is logged in a method that handles exceptions, but the throwable"
                        + " is not passed to the logger.",
                "The log line states that something failed and gives no stack trace or cause, so the"
                        + " investigation restarts from zero.",
                "A logger error/warn call whose arguments do not reference the caught exception, in a"
                        + " method that contains catch blocks.");
        put(catalog, GenericExceptionMessageRule.ID, "Failure message without context",
                "The logged failure message is a bare word such as \"error\" or \"falha\".",
                "The message cannot be searched, grouped, or correlated with a request, so alerting"
                        + " and triage fall back to reading code.",
                "The first logger argument is a string literal matching a known context-free phrase.");
        put(catalog, AsyncResultUnobservedRule.ID, "Asynchronous result never observed",
                "Work is submitted to an executor or a CompletableFuture and the returned handle is"
                        + " discarded.",
                "An exception inside the task completes the future exceptionally and nobody reads it,"
                        + " so the failure never appears anywhere.",
                "An async submission whose result is neither assigned, chained, awaited, nor"
                        + " returned.");
        put(catalog, HttpClientErrorDiscardedRule.ID, "HTTP client error replaced without the cause",
                "A reactive or future error operator substitutes a value for the failure and never"
                        + " references the throwable.",
                "The remote call failed but the flow continues with a placeholder, so the outage looks"
                        + " like empty data downstream.",
                "An error-handling operator (onErrorReturn, onErrorResume, exceptionally, onStatus)"
                        + " whose arguments do not mention the error.");
        put(catalog, ScheduledTaskSwallowsFailureRule.ID, "Scheduled task swallows the failure",
                "A @Scheduled method catches an exception and neither logs nor rethrows it.",
                "The job keeps its green schedule while doing nothing useful; the outage is only"
                        + " noticed through missing downstream data.",
                "A catch block in a @Scheduled method with no log and no rethrow.");
        put(catalog, RetryWithoutDiagnosticsRule.ID, "Retry without diagnostics",
                "A retried operation records nothing about the attempts it consumes.",
                "Retry storms stay invisible until the budget is exhausted, and the eventual error"
                        + " hides how many times the dependency already failed.",
                "A @Retryable/@Retry method whose body contains no logger call and no metric.");
        put(catalog, FallbackHidesFailureRule.ID, "Fallback hides the failure",
                "A recovery or fallback method returns a default value without recording what it is"
                        + " compensating for.",
                "Degraded responses become indistinguishable from healthy ones, so the dependency"
                        + " failure never shows up in dashboards.",
                "A @Recover or fallback-named method with no logger call and no metric.");
        put(catalog, MetricCreatedInLoopRule.ID, "Metric instrument created in a loop",
                "A counter, timer, or gauge is resolved inside a loop body.",
                "Instrument creation per iteration multiplies time series and adds overhead on the hot"
                        + " path, which can degrade the metrics backend during an incident.",
                "A metric registration call whose enclosing statement is a for/while/do-while body.");
        put(catalog, SensitivePayloadLoggedRule.ID, "Sensitive payload written to logs",
                "A log statement includes an argument named like a secret or personal identifier.",
                "The value is persisted in log storage, where it is broadly readable and long lived,"
                        + " turning a debugging aid into a compliance incident.",
                "A logger call with an argument matching sensitive terms such as password, token, cpf,"
                        + " or authorization.");
        put(catalog, JdbcTemplateConnectionEscapeRule.ID, "Raw connection escapes Spring management",
                "A raw Connection is taken from a JdbcTemplate or DataSourceUtils and used directly.",
                "The connection loses its binding to the active transaction and Spring's exception"
                        + " translation, so failures surface as vendor errors and work can commit"
                        + " outside the intended transaction.",
                "A call that extracts the underlying Connection from a Spring datasource"
                        + " abstraction.");
        return java.util.Collections.unmodifiableMap(catalog);
    }

    private static void put(Map<String, RuleExplanation> catalog, String ruleId, String title,
                            String whatItMeans, String whyItMatters, String howDetected) {
        catalog.put(ruleId, new RuleExplanation(ruleId, title, whatItMeans, whyItMatters, howDetected));
    }
}
