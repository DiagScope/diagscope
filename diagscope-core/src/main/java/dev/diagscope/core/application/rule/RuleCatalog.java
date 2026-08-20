package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Severity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 *
 * <p>This class is the <em>single source of truth</em> for rule metadata. It is the authoritative
 * registry of category, default severity, supported languages, and applicability for each rule.
 * {@code RuleDocumentationContractTest} enforces that every registered rule is documented here and
 * that every catalog entry corresponds to an active rule.</p>
 */
public final class RuleCatalog {

    /** All languages supported by DiagScope in the current release. */
    public static final Set<String> ALL_LANGUAGES = Set.of("java", "kotlin");

    /**
     * Detailed, presentation-only documentation for a rule.
     *
     * @param ruleId             the rule identifier
     * @param title              short human title for the rule
     * @param category           functional category (e.g. {@code "exception-handling"}, {@code "kafka"})
     * @param defaultSeverity    the severity this rule emits when no project policy overrides it
     * @param supportedLanguages the source languages this rule can produce findings for
     * @param applicability      brief note on the frameworks or setups where this rule is active
     * @param whatItMeans        plain description of the reported situation
     * @param whyItMatters       the diagnostic consequence at runtime
     * @param howDetected        the deterministic signal DiagScope used
     * @param knownLimitations   documented false-positive or false-negative scenarios, or {@code null}
     */
    public record RuleExplanation(
            String ruleId,
            String title,
            String category,
            Severity defaultSeverity,
            Set<String> supportedLanguages,
            String applicability,
            String whatItMeans,
            String whyItMatters,
            String howDetected,
            String knownLimitations
    ) {
        public RuleExplanation {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(defaultSeverity, "defaultSeverity");
            Objects.requireNonNull(supportedLanguages, "supportedLanguages");
            if (supportedLanguages.isEmpty()) throw new IllegalArgumentException("supportedLanguages must not be empty");
            Objects.requireNonNull(applicability, "applicability");
            Objects.requireNonNull(whatItMeans, "whatItMeans");
            Objects.requireNonNull(whyItMatters, "whyItMatters");
            Objects.requireNonNull(howDetected, "howDetected");
            // knownLimitations is intentionally nullable
            supportedLanguages = Set.copyOf(supportedLanguages);
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
                "uncategorized",
                Severity.WARNING,
                ALL_LANGUAGES,
                "Any application",
                "This rule reported a place where diagnostic evidence is weakened or lost.",
                "When evidence is missing, an incident on this flow has to be reconstructed from"
                        + " indirect signals instead of from the code path itself.",
                "Reported by a rule without catalog documentation; inspect the evidence table below.",
                null
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

        // ── Exception handling ────────────────────────────────────────────────────
        put(catalog, SilentCatchRule.ID,
                "Exception caught and ignored",
                "exception-handling", Severity.ERROR, ALL_LANGUAGES,
                "Any Java or Kotlin application",
                "An exception is caught in a block that does nothing with it: no log, no rethrow, no"
                        + " recovery action recorded.",
                "The failure disappears at this line. The caller keeps running as if the operation"
                        + " succeeded, and no trace of the original error reaches logs or traces.",
                "The catch block body is empty (or only contains comments) and carries no DiagScope"
                        + " suppression.");

        put(catalog, SilentFailureConversionRule.ID,
                "Failure converted into a normal value",
                "exception-handling", Severity.ERROR, ALL_LANGUAGES,
                "Any Java or Kotlin application",
                "An exception is caught and turned into a benign result such as null, an empty"
                        + " collection, false, or a default value.",
                "Downstream code cannot distinguish 'no data' from 'the call failed', so the incident"
                        + " surfaces later as wrong data instead of as an error.",
                "The catch block returns a constant or empty value and never logs, rethrows, or"
                        + " records the cause.");

        put(catalog, DuplicateDiagnosticSignalRule.ID,
                "Duplicate or contradictory failure record",
                "exception-handling", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application",
                "The same failure is logged in a catch block and then rethrown, so it is reported"
                        + " again upstream — and when the cause is dropped, the two records differ.",
                "Error counts double, the same incident appears as several distinct failures, and"
                        + " the record without a cause points triage at the wrong layer.",
                "A catch block that both logs and throws; the cause is tracked separately to tell a"
                        + " duplicate apart from a contradictory record.");

        // ── Logging ───────────────────────────────────────────────────────────────
        put(catalog, PrintStackTraceRule.ID,
                "Stack trace printed instead of logged",
                "logging", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application with SLF4J or Logback",
                "The exception is reported through printStackTrace() rather than through the"
                        + " application logger.",
                "The trace goes to standard error, bypassing log levels, structured fields, and"
                        + " correlation ids, so it is usually invisible in centralized logging.",
                "A direct call to Throwable.printStackTrace() on a reachable path.");

        put(catalog, SystemOutputRule.ID,
                "Diagnostics written to standard output",
                "logging", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application",
                "Diagnostic text is written with System.out or System.err instead of the logger.",
                "The message escapes log routing, sampling, and correlation, so it cannot be searched"
                        + " or alerted on when the incident happens.",
                "A direct call to System.out/System.err print methods on a reachable path.");

        put(catalog, LogWithoutThrowableRule.ID,
                "Failure logged without the exception",
                "logging", Severity.WARNING, ALL_LANGUAGES,
                "Any application with SLF4J or Logback",
                "An error or warning is logged in a method that handles exceptions, but the throwable"
                        + " is not passed to the logger.",
                "The log line states that something failed and gives no stack trace or cause, so the"
                        + " investigation restarts from zero.",
                "A logger error/warn call whose arguments do not reference the caught exception, in a"
                        + " method that contains catch blocks.");

        put(catalog, GenericExceptionMessageRule.ID,
                "Failure message without context",
                "logging", Severity.WARNING, ALL_LANGUAGES,
                "Any application with SLF4J or Logback",
                "The logged failure message is a bare word such as \"error\" or \"falha\".",
                "The message cannot be searched, grouped, or correlated with a request, so alerting"
                        + " and triage fall back to reading code.",
                "The first logger argument is a string literal matching a known context-free phrase.");

        put(catalog, SensitivePayloadLoggedRule.ID,
                "Sensitive payload written to logs",
                "logging", Severity.ERROR, ALL_LANGUAGES,
                "Any application with SLF4J or Logback",
                "A log statement includes an argument named like a secret or personal identifier.",
                "The value is persisted in log storage, where it is broadly readable and long lived,"
                        + " turning a debugging aid into a compliance incident.",
                "A logger call with an argument matching sensitive terms such as password, token, cpf,"
                        + " or authorization.",
                "The rule matches on parameter and variable names, not on runtime values, so it may"
                        + " flag intentional audit logs that log redacted representations.");

        put(catalog, MdcContextLostRule.ID,
                "Logging context (MDC) lost",
                "logging", Severity.WARNING, ALL_LANGUAGES,
                "Any application with SLF4J MDC and thread-pool or reactive dispatch",
                "The method fills the MDC and then either hands work to another thread without"
                        + " copying the context, or never removes the keys it wrote.",
                "Logs on the other side of the thread hop lose the correlation id, and keys left"
                        + " behind on a pooled thread attach the wrong identity to the next request.",
                "An MDC.put in the method combined with an async dispatch whose arguments do not"
                        + " carry the context, or with no MDC.remove/clear anywhere in the method.");

        // ── Kafka ─────────────────────────────────────────────────────────────────
        put(catalog, IgnoredKafkaSendResultRule.ID,
                "Kafka send result ignored",
                "kafka", Severity.WARNING, ALL_LANGUAGES,
                "Spring Kafka or Quarkus Reactive Messaging",
                "The asynchronous result of a Kafka producer send is discarded.",
                "A broker-side failure completes the future exceptionally and is never observed, so"
                        + " the message is lost while the code reports success.",
                "The send() result is neither assigned, chained with a completion callback, awaited,"
                        + " nor returned, and no producer listener was detected.");

        put(catalog, KafkaManualAckMissingRule.ID,
                "Manual Kafka acknowledgement never invoked",
                "kafka", Severity.ERROR, ALL_LANGUAGES,
                "Spring Kafka with manual acknowledgement (AckMode.MANUAL or MANUAL_IMMEDIATE)",
                "A listener declares manual acknowledgement but never calls acknowledge() on some"
                        + " path.",
                "Offsets are not committed for that path, causing redelivery loops or stalled"
                        + " partitions that look like a consumer outage.",
                "The listener signature accepts an Acknowledgment parameter that is never invoked in"
                        + " the method body.",
                "Class-level @KafkaListener with @KafkaHandler methods is supported but multi-method"
                        + " flows may not cover every handler.");

        put(catalog, KafkaListenerFailureNotPropagatedRule.ID,
                "Kafka listener swallows the failure",
                "kafka", Severity.WARNING, ALL_LANGUAGES,
                "Spring Kafka or Quarkus Reactive Messaging",
                "A listener catches an exception and does not rethrow it.",
                "The container never sees the error, so error handlers, retries, and dead-letter"
                        + " routing are all skipped and the record is silently dropped.",
                "A catch block inside a @KafkaListener/@KafkaHandler method that neither rethrows nor"
                        + " records the failure.");

        // ── Reactive ─────────────────────────────────────────────────────────────
        put(catalog, ReactiveMessageFailureNotPropagatedRule.ID,
                "Reactive message failure is not propagated",
                "reactive", Severity.WARNING, ALL_LANGUAGES,
                "Quarkus Reactive Messaging (@Incoming channels)",
                "An @Incoming consumer catches a failure and returns normally instead of returning a"
                        + " failed result or throwing.",
                "The configured channel failure strategy may never see the failure, making a dropped"
                        + " or unsuccessfully processed message look successful.",
                "A catch block on the @Incoming entrypoint has no throw. The channel connector and"
                        + " runtime failure strategy are unknown, so confidence is capped.",
                "Confidence is intentionally capped at MEDIUM because the runtime failure-strategy"
                        + " is not visible statically.");

        put(catalog, MutinyFailureRecoveredSilentlyRule.ID,
                "Mutiny failure recovery loses evidence",
                "reactive", Severity.WARNING, ALL_LANGUAGES,
                "Quarkus Mutiny (SmallRye Mutiny)",
                "A Mutiny onFailure() recovery returns a fallback without visibly receiving or"
                        + " recording the failure.",
                "Degraded responses become indistinguishable from healthy responses, which hides"
                        + " dependency outages and retry exhaustion.",
                "A recoverWithItem/recoverWithNull/recoverWithCompletion/recoverWithUni/"
                        + "recoverWithMulti call chained from onFailure(), whose arguments contain no"
                        + " throwable-looking value and whose chain does not already observe the"
                        + " failure through invoke(...) or call(...). Confidence drops to LOW when the"
                        + " recovery is a method reference, because the callback body is not visible"
                        + " at the call site.",
                "Method-reference callbacks are not inspected: the rule reports LOW confidence and"
                        + " may miss callbacks that correctly receive the failure.");

        put(catalog, MutinySubscriptionFailureUnobservedRule.ID,
                "Mutiny subscription ignores failures",
                "reactive", Severity.WARNING, ALL_LANGUAGES,
                "Quarkus Mutiny (SmallRye Mutiny)",
                "A Mutiny subscription supplies only the item callback and no callback for failures.",
                "A later asynchronous failure goes to a global dropped-exception path instead of the"
                        + " local flow's logs, metrics, or recovery policy.",
                "A one-argument with(...) call whose receiver is the syntax-visible result of"
                        + " subscribe(), for both Uni and Multi, and regardless of whether the single"
                        + " callback is a lambda, a typed lambda, or a method reference.");

        // ── Observability: metrics ────────────────────────────────────────────────
        put(catalog, HighCardinalityMetricTagRule.ID,
                "High-cardinality metric tag",
                "observability", Severity.ERROR, ALL_LANGUAGES,
                "Spring Boot Actuator or Micrometer",
                "A metric tag value comes from unbounded data such as an id, a user input, or a"
                        + " message payload.",
                "Each distinct value creates a new time series, which inflates cost and can degrade"
                        + " or break the metrics backend exactly during an incident.",
                "The tag value is not a literal, constant, or enum, and resolves to a caller-supplied"
                        + " or dynamically computed expression.");

        put(catalog, DynamicMetricNameRule.ID,
                "Metric name is not constant",
                "observability", Severity.WARNING, ALL_LANGUAGES,
                "Spring Boot Actuator or Micrometer",
                "The meter name itself is computed at runtime instead of being a constant.",
                "Dashboards and alerts bind to fixed metric names; a computed name makes the series"
                        + " unqueryable and silently breaks existing alerting.",
                "The name argument of the meter registration is not a compile-time constant.");

        put(catalog, MetricCreatedInLoopRule.ID,
                "Metric instrument created in a loop",
                "observability", Severity.WARNING, ALL_LANGUAGES,
                "Spring Boot Actuator or Micrometer",
                "A counter, timer, or gauge is resolved inside a loop body.",
                "Instrument creation per iteration multiplies time series and adds overhead on the hot"
                        + " path, which can degrade the metrics backend during an incident.",
                "A metric registration call whose enclosing statement is a for/while/do-while body.");

        // ── Resilience ───────────────────────────────────────────────────────────
        put(catalog, AsyncResultUnobservedRule.ID,
                "Asynchronous result never observed",
                "resilience", Severity.ERROR, ALL_LANGUAGES,
                "Spring @Async or CompletableFuture / Executor submit",
                "Work is submitted to an executor or a CompletableFuture and the returned handle is"
                        + " discarded.",
                "An exception inside the task completes the future exceptionally and nobody reads it,"
                        + " so the failure never appears anywhere.",
                "An async submission whose result is neither assigned, chained, awaited, nor"
                        + " returned.");

        put(catalog, HttpClientErrorDiscardedRule.ID,
                "HTTP client error replaced without the cause",
                "resilience", Severity.ERROR, ALL_LANGUAGES,
                "Spring WebClient or Project Reactor HTTP clients",
                "A reactive or future error operator substitutes a value for the failure and never"
                        + " references the throwable.",
                "The remote call failed but the flow continues with a placeholder, so the outage looks"
                        + " like empty data downstream.",
                "An error-handling operator (onErrorReturn, onErrorResume, exceptionally, onStatus)"
                        + " whose arguments do not mention the error.");

        put(catalog, ScheduledTaskSwallowsFailureRule.ID,
                "Scheduled task swallows the failure",
                "scheduling", Severity.ERROR, ALL_LANGUAGES,
                "Spring @Scheduled or Quarkus @Scheduled",
                "A @Scheduled method catches an exception and neither logs nor rethrows it.",
                "The job keeps its green schedule while doing nothing useful; the outage is only"
                        + " noticed through missing downstream data.",
                "A catch block in a @Scheduled method with no log and no rethrow.");

        put(catalog, RetryWithoutDiagnosticsRule.ID,
                "Retry without diagnostics",
                "resilience", Severity.WARNING, ALL_LANGUAGES,
                "Spring Retry (@Retryable) or MicroProfile Fault Tolerance (@Retry)",
                "A retried operation records nothing about the attempts it consumes.",
                "Retry storms stay invisible until the budget is exhausted, and the eventual error"
                        + " hides how many times the dependency already failed.",
                "A @Retryable/@Retry method whose body contains no logger call and no metric.");

        put(catalog, FallbackHidesFailureRule.ID,
                "Fallback hides the failure",
                "resilience", Severity.WARNING, ALL_LANGUAGES,
                "Spring Retry (@Recover) or MicroProfile Fault Tolerance fallbacks",
                "A recovery or fallback method returns a default value without recording what it is"
                        + " compensating for.",
                "Degraded responses become indistinguishable from healthy ones, so the dependency"
                        + " failure never shows up in dashboards.",
                "A @Recover or fallback-named method with no logger call and no metric.");

        // ── AOP / Proxy ───────────────────────────────────────────────────────────
        put(catalog, SelfInvocationProxyBypassRule.ID,
                "Advice bypassed by self-invocation",
                "aop", Severity.WARNING, ALL_LANGUAGES,
                "Spring AOP proxy-based applications",
                "An annotated method is called through this, so the call does not go through the"
                        + " Spring proxy.",
                "Transactions, retries, caching, or observability advice attached to that method never"
                        + " run, so the guarantees the annotation promises are silently absent.",
                "An internal call on this to a method matched by an advice pointcut or a proxied"
                        + " annotation.");

        put(catalog, NonProxyableAdviceTargetRule.ID,
                "Advice cannot apply to this method",
                "aop", Severity.WARNING, ALL_LANGUAGES,
                "Spring AOP proxy-based applications",
                "Advice targets a method that a proxy can never intercept, typically private, final,"
                        + " or static.",
                "The instrumentation looks configured but never executes, so the method appears"
                        + " covered while producing no telemetry or transactional behaviour.",
                "A pointcut match against a method whose visibility or modifiers make it"
                        + " non-proxyable under the detected proxy mode.");

        put(catalog, UnmanagedAdviceTargetRule.ID,
                "Advice target is not a managed bean",
                "aop", Severity.INFO, ALL_LANGUAGES,
                "Spring AOP proxy-based applications",
                "Advice matches a class that does not appear to be a Spring-managed bean.",
                "Instances created with new are never wrapped by a proxy, so the advice is absent for"
                        + " every call made through them.",
                "A pointcut match against a type with no stereotype or bean declaration detected in"
                        + " the analyzed sources.",
                "Beans registered programmatically through @Bean methods may not be detected;"
                        + " this can produce false positives for infrastructure classes.");

        // ── Transactions ─────────────────────────────────────────────────────────
        put(catalog, TransactionalRollbackSuppressedRule.ID,
                "Transaction rollback suppressed",
                "transactions", Severity.ERROR, ALL_LANGUAGES,
                "Spring @Transactional",
                "An exception inside a @Transactional method is caught and not rethrown or marked"
                        + " rollback-only.",
                "The transaction commits partial work: the database ends in a state the code never"
                        + " intended, and no error is reported to the caller.",
                "A catch block in a transactional method with no rethrow and no"
                        + " setRollbackOnly() call.");

        put(catalog, TransactionalPropagationMismatchRule.ID,
                "Transaction boundary does not exist",
                "transactions", Severity.ERROR, ALL_LANGUAGES,
                "Spring @Transactional",
                "A @Transactional method is reached through an internal call, or a caller without an"
                        + " active transaction calls a MANDATORY one.",
                "The declared boundary is not created: work believed to be isolated joins the caller"
                        + " transaction (or runs with none), and MANDATORY paths fail at runtime.",
                "Resolved call edges combined with the propagation attribute read from"
                        + " @Transactional on the caller and the callee.");

        // ── Database / Resource management ────────────────────────────────────────
        put(catalog, JdbcResourceLeakRule.ID,
                "JDBC resource not closed",
                "database", Severity.ERROR, ALL_LANGUAGES,
                "Spring JDBC or plain JDBC",
                "A Connection, Statement, PreparedStatement, or ResultSet is acquired outside"
                        + " try-with-resources and never closed.",
                "Pool exhaustion appears far from this code, as timeouts in unrelated requests, which"
                        + " makes the real cause very hard to find.",
                "The resource acquisition is not a try-with-resources binding and no close() call was"
                        + " found for it in the method.");

        put(catalog, DatabaseResourceCloseNotGuardedRule.ID,
                "Resource closed only on the happy path",
                "database", Severity.ERROR, ALL_LANGUAGES,
                "Spring JDBC or plain JDBC",
                "The resource is closed, but the close() call is not inside a finally block or a"
                        + " try-with-resources binding.",
                "If anything throws before that line, the resource leaks; the leak only shows up"
                        + " under failure, which is exactly when capacity matters most.",
                "A close() call reachable only on the normal path, with no enclosing finally block"
                        + " and no resource binding.");

        put(catalog, EntityManagerLeakRule.ID,
                "EntityManager never closed",
                "database", Severity.ERROR, ALL_LANGUAGES,
                "Spring Data JPA or plain JPA (manually created EntityManager)",
                "An EntityManager created from a factory is never closed in the method that created"
                        + " it.",
                "The persistence context and its connection stay held, leaking memory and pool"
                        + " capacity until the process degrades.",
                "A createEntityManager() result with no matching close() call in the same method.",
                "Container-managed EntityManagers injected via @PersistenceContext are not flagged;"
                        + " only factory-created instances are in scope.");

        put(catalog, JdbcTemplateConnectionEscapeRule.ID,
                "Raw connection escapes Spring management",
                "database", Severity.WARNING, ALL_LANGUAGES,
                "Spring JDBC (JdbcTemplate, DataSourceUtils)",
                "A raw Connection is taken from a JdbcTemplate or DataSourceUtils and used directly.",
                "The connection loses its binding to the active transaction and Spring's exception"
                        + " translation, so failures surface as vendor errors and work can commit"
                        + " outside the intended transaction.",
                "A call that extracts the underlying Connection from a Spring datasource"
                        + " abstraction.");

        return Collections.unmodifiableMap(catalog);
    }

    /** Adds a rule explanation without known limitations. */
    private static void put(Map<String, RuleExplanation> catalog,
                            String ruleId, String title,
                            String category, Severity defaultSeverity,
                            Set<String> supportedLanguages, String applicability,
                            String whatItMeans, String whyItMatters, String howDetected) {
        put(catalog, ruleId, title, category, defaultSeverity, supportedLanguages, applicability,
                whatItMeans, whyItMatters, howDetected, null);
    }

    /** Adds a rule explanation with documented known limitations. */
    private static void put(Map<String, RuleExplanation> catalog,
                            String ruleId, String title,
                            String category, Severity defaultSeverity,
                            Set<String> supportedLanguages, String applicability,
                            String whatItMeans, String whyItMatters, String howDetected,
                            String knownLimitations) {
        catalog.put(ruleId, new RuleExplanation(ruleId, title, category, defaultSeverity,
                supportedLanguages, applicability, whatItMeans, whyItMatters, howDetected,
                knownLimitations));
    }
}
