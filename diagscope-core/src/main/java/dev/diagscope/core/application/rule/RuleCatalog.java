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

        // ── Concurrency / Threads ─────────────────────────────────────────────
        put(catalog, LockNotReleasedRule.ID,
                "Lock not released on every path",
                "concurrency", Severity.ERROR, ALL_LANGUAGES,
                "Any Java or Kotlin application using java.util.concurrent.locks",
                "A Lock.lock() call is not paired with an unlock() inside a finally block.",
                "If any code between lock() and unlock() throws, the lock is never released,"
                        + " causing a deadlock the next time any thread attempts to acquire it.",
                "A lock() invocation in the method body with no corresponding unlock() guarded"
                        + " by a finally block on the same receiver.",
                "Best-effort receiver matching compares scope names; fields accessed through"
                        + " different aliases may not be correlated, which can cause false negatives.");

        put(catalog, ThreadLocalLeakRule.ID,
                "ThreadLocal value never removed",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application using thread pools",
                "ThreadLocal.set() is called but remove() is never called in the same method.",
                "Pooled threads are reused. A value stored in a ThreadLocal that is never removed"
                        + " persists to the next request on the same thread, leaking state"
                        + " (and potentially sensitive data) across requests, or growing the heap"
                        + " without bound.",
                "A set() call on a ThreadLocal or InheritableThreadLocal with no matching remove()"
                        + " on the same receiver in the same method body.",
                "remove() called in a finally block in a different method (e.g., a servlet filter"
                        + " or interceptor) is not visible here and may cause a false positive.");

        put(catalog, FutureGetWithoutTimeoutRule.ID,
                "Future.get() blocks without a timeout",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application using CompletableFuture, Future, or ListenableFuture",
                "Future.get() or CompletableFuture.join() is called without a timeout, blocking"
                        + " the calling thread indefinitely.",
                "In a container or thread pool, an unbounded block ties up the thread for as long"
                        + " as the remote computation takes. If the dependency degrades, all threads"
                        + " block and the service becomes unresponsive.",
                "A get() call with zero arguments or a join()/getNow() call on a future-looking"
                        + " receiver.",
                "get(long, TimeUnit) with a very large timeout may not be detected as safe;"
                        + " the rule only checks for the presence of arguments, not their values.");

        put(catalog, BlockingCallInReactiveContextRule.ID,
                "Blocking call on reactive/event-loop thread",
                "concurrency", Severity.ERROR, ALL_LANGUAGES,
                "Quarkus, Spring WebFlux, Vert.x or any Project Reactor / Mutiny application",
                "A blocking operation (Thread.sleep, Object.wait, CountDownLatch.await, etc.) is"
                        + " called inside a method declared as non-blocking or reactive.",
                "Reactive runtimes multiplex many requests onto a small number of event-loop"
                        + " threads. A single blocking call stalls the thread and prevents other"
                        + " events from being processed, cascading into a full service hang under"
                        + " load.",
                "A call to a known blocking method inside a method carrying @NonBlocking,"
                        + " @Incoming, @Outgoing, @MessageMapping, or @ReactiveTransactional.",
                "The rule detects reactive context from annotations only; methods that are reactive"
                        + " because they return Mono/Flux without annotation are not detected.");

        // ── Performance ───────────────────────────────────────────────────────
        put(catalog, NPlusOneQueryRiskRule.ID,
                "N+1 query risk: database call inside a loop",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring Data, JPA / Hibernate, JdbcTemplate, Micronaut Data, or any DAO layer",
                "A repository or persistence method is called inside a loop body.",
                "One outer query returns N records, then the loop fires one additional query per"
                        + " record. The total number of queries grows linearly with the data set,"
                        + " overwhelming the database at scale and inflating response latency.",
                "An invocation of a Spring Data finder, JPA EntityManager operation, or"
                        + " JdbcTemplate query method whose insideLoop flag is true.",
                "The rule uses method name patterns and receiver type heuristics; it may flag"
                        + " calls that are intentionally batched or cached at a lower layer.");

        // ── Maintainability ──────────────────────────────────────────────────
        put(catalog, ExcessiveMethodParametersRule.ID,
                "Method has too many parameters",
                "maintainability", Severity.INFO, ALL_LANGUAGES,
                "Any Java or Kotlin application",
                "A method declares more than " + ExcessiveMethodParametersRule.MAX_PARAMETERS
                        + " parameters.",
                "Long parameter lists are hard to call correctly — callers must track the order"
                        + " and meaning of each argument. They signal that the method may be doing"
                        + " too much and should be split, or that related parameters should be"
                        + " grouped into a dedicated value object.",
                "The declared parameter count on the MethodId exceeds the configured threshold."
                        + " Constructors and vararg methods are excluded.",
                "Spring controller methods may have many @RequestParam / @PathVariable parameters"
                        + " by design; consider configuring an exclusion for REST handlers if these"
                        + " produce too much noise.");

        put(catalog, HighMethodComplexityRule.ID,
                "Method structural complexity is too high",
                "maintainability", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application",
                "A method makes more than " + HighMethodComplexityRule.MAX_INVOCATIONS
                        + " calls, or its combined complexity score (calls + catch-block weight)"
                        + " exceeds " + HighMethodComplexityRule.COMPLEXITY_THRESHOLD + ".",
                "High structural complexity correlates strongly with defect density and"
                        + " maintenance cost. A method with many branches and many calls is hard"
                        + " to unit-test in isolation and expensive to reason about during an"
                        + " incident.",
                "The number of method invocations in the body plus the number of catch blocks"
                        + " (each weighted at 3x because a catch represents at least one additional"
                        + " execution path) is computed as a proxy for cyclomatic complexity.",
                "DiagScope cannot count if/else or switch branches without the full AST, so the"
                        + " score is an approximation. Well-designed orchestration methods may"
                        + " legitimately call many collaborators; tune the threshold if needed.");

        // ── Null safety ───────────────────────────────────────────────────────
        put(catalog, OptionalGetWithoutCheckRule.ID,
                "Optional.get() without presence check",
                "null-safety", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application using java.util.Optional",
                "Optional.get() is called in a method that does not call isPresent(), isEmpty(),"
                        + " or any safe Optional accessor on the same value.",
                "Optional.get() on an empty Optional throws NoSuchElementException, which is"
                        + " indistinguishable from a NullPointerException to callers and carries"
                        + " no context about which value was missing.",
                "A get() call on an Optional-typed receiver in a method that does not also invoke"
                        + " isPresent(), isEmpty(), ifPresent(), orElse(), or map() on the same"
                        + " receiver.",
                "The rule compares receiver scope names to determine if the guard applies to the"
                        + " same Optional; a guard on a different Optional in the same method will"
                        + " not suppress the finding. Kotlin nullable types are not in scope.");

        // ── Transactions ────────────────────────────────────────────────────────
        put(catalog, MissingTransactionAnnotationRule.ID,
                "Write operation outside a transaction boundary",
                "transactions", Severity.WARNING, ALL_LANGUAGES,
                "Spring Data / JPA / Hibernate applications",
                "A JPA or Spring Data write operation (save, delete, persist, merge, flush, remove)"
                        + " is called in a method that carries no @Transactional annotation and whose"
                        + " class is not declared @Transactional.",
                "Without a transaction boundary each write runs in its own auto-commit context."
                        + " If a subsequent operation in the same business action fails, the previously"
                        + " persisted changes cannot be rolled back, leaving data in an inconsistent state.",
                "A write-operation method call (matched by name against a curated set) on a receiver"
                        + " whose type or scope name suggests a Spring Data repository, DAO,"
                        + " or JPA EntityManager, in a method with no detectable @Transactional"
                        + " annotation on the method or its declaring class.",
                "The rule uses method-name and receiver-type heuristics, so a custom repository"
                        + " with an unconventional name may be missed, and a method that calls a"
                        + " helper that is itself @Transactional will still be flagged.");

        // ── Resilience: HTTP timeout ──────────────────────────────────────────────
        put(catalog, HttpTimeoutNotSetRule.ID,
                "HTTP client call without a timeout",
                "resilience", Severity.WARNING, ALL_LANGUAGES,
                "Spring WebClient, RestTemplate, or any reactive HTTP client",
                "An HTTP client call or a reactive .block() is made in a method where no timeout"
                        + " (.timeout(), responseTimeout, setReadTimeout) is observed.",
                "An HTTP call with no timeout will block the calling thread indefinitely if the"
                        + " remote server is slow or unreachable. Under load this exhausts the thread"
                        + " pool and can bring the entire service down.",
                "A blocking terminal operator (block, blockFirst, blockLast) on a receiver whose scope"
                        + " or type name suggests an HTTP or reactive type, or a RestTemplate call,"
                        + " in a method where no timeout-setting call is observed.",
                "Timeouts set on the WebClient builder at bean-construction time are not visible"
                        + " at the call site; this can produce false positives when the builder is"
                        + " properly configured. Review each finding in the context of the client setup.");

        // ── Maintainability: class complexity ─────────────────────────────────────
        put(catalog, GodClassRule.ID,
                "Class has too many public methods (god class)",
                "maintainability", Severity.INFO, ALL_LANGUAGES,
                "Any Java or Kotlin application",
                "A class declares more than " + GodClassRule.MAX_PUBLIC_METHODS
                        + " public non-static methods.",
                "Classes that accumulate too many responsibilities become hard to understand,"
                        + " test in isolation, and evolve independently. A god class is typically a"
                        + " symptom of missing domain abstractions or service boundaries.",
                "The number of non-static public methods grouped by declaring type across the full"
                        + " analyzed project. Exceeding the threshold triggers one finding per class.",
                "Interfaces and abstract classes with many declared methods may be flagged; those"
                        + " define a contract rather than an implementation and may warrant a project"
                        + " policy exclusion. Threshold is configurable.");

        // ── Exception handling: interrupt contract ────────────────────────────
        put(catalog, InterruptedExceptionSwallowedRule.ID,
                "InterruptedException caught without restoring the interrupt flag",
                "exception-handling", Severity.ERROR, ALL_LANGUAGES,
                "Any Java or Kotlin application using thread pools or blocking operations",
                "An InterruptedException is caught but Thread.currentThread().interrupt() is"
                        + " not called, and the exception is not rethrown.",
                "The JVM thread-interrupted flag is permanently cleared. Executor frameworks,"
                        + " shutdown hooks, and test runners that rely on interruption to stop"
                        + " threads can no longer do so. The thread runs indefinitely with no"
                        + " signal that the interrupt was consumed.",
                "A catch block whose declared exception type contains InterruptedException,"
                        + " where the block neither rethrows nor contains a call to"
                        + " Thread.currentThread().interrupt().",
                "The rule checks for interrupt() anywhere in the method body, not specifically"
                        + " inside the catch block. A re-interrupt in a finally block or a"
                        + " different catch arm will suppress the finding.");

        // ── Concurrency: CompletableFuture ────────────────────────────────────
        put(catalog, CompletableFutureExceptionNotHandledRule.ID,
                "CompletableFuture pipeline has no exception handler",
                "concurrency", Severity.ERROR, ALL_LANGUAGES,
                "Any Java or Kotlin application using CompletableFuture",
                "An async computation is submitted (supplyAsync, runAsync, or an Async-suffixed"
                        + " stage) but the pipeline has no terminal exception handler"
                        + " (exceptionally, handle, or whenComplete).",
                "An unhandled exception in a CompletableFuture pipeline is silently discarded"
                        + " in fire-and-forget patterns. No log entry, no metric, no alert —"
                        + " the operation failed and nothing recorded it.",
                "At least one async submission method call is present in the method body,"
                        + " and no invocation matching exceptionally, handle, or whenComplete"
                        + " is found on any future-looking receiver in the same method.",
                "When the future is returned to the caller the exception handling responsibility"
                        + " is delegated upstream, and the rule is suppressed. A handler"
                        + " registered in a calling scope outside this method is not visible.");

        // ── Concurrency: executor lifecycle ───────────────────────────────────
        put(catalog, ExecutorNotShutdownRule.ID,
                "ExecutorService created locally but never shut down",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application that creates thread pools",
                "An ExecutorService is created via Executors factory methods inside a method"
                        + " body, its result is stored in a local variable, and shutdown() or"
                        + " shutdownNow() is never called in the same method.",
                "The thread pool holds live threads that are GC roots. They keep the JVM alive"
                        + " and retain references to everything they have processed. In code"
                        + " called repeatedly (per-request, per-message) this creates a new"
                        + " thread pool on each invocation — a thread leak that manifests as"
                        + " slow heap growth and increasing thread count.",
                "An Executors factory method call whose result is assigned to a local variable,"
                        + " in a method that has no corresponding shutdown/shutdownNow call and"
                        + " is not annotated @Bean.",
                "shutdown() called in a @PreDestroy method, a Spring DisposableBean, or another"
                        + " lifecycle callback is not visible at the creation site and may"
                        + " cause false positives. Suppress with @Bean or an explicit ignore.");

        // ── AOP / Proxy: async visibility ─────────────────────────────────────
        put(catalog, AsyncOnPrivateMethodRule.ID,
                "@Async on a private method is silently ignored",
                "aop-proxy", Severity.ERROR, ALL_LANGUAGES,
                "Spring applications using @Async",
                "A method declared private carries the @Async annotation.",
                "Spring's proxy cannot override a private method. The @Async annotation is"
                        + " silently ignored and the method runs synchronously on the calling"
                        + " thread. The caller receives the result immediately, believing the"
                        + " work was dispatched asynchronously when it was not.",
                "The method's declared visibility is PRIVATE and it carries an @Async annotation.",
                "Some AOP frameworks (AspectJ LTW, Quarkus @Asynchronous) may behave"
                        + " differently. The rule fires on @Async specifically; other async"
                        + " annotations are not covered.");

        // ── Resilience: scheduler error boundary ──────────────────────────────
        put(catalog, ScheduledExceptionNotHandledRule.ID,
                "@Scheduled method has no exception boundary",
                "resilience", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Scheduled",
                "A @Scheduled method has no try/catch block and no instrumentation annotation,"
                        + " leaving any exception to propagate directly to the scheduler.",
                "Before Spring 6, an uncaught exception from a @Scheduled method causes the"
                        + " scheduler to permanently cancel future executions of that task —"
                        + " silently. In Spring 6+ future executions continue but the exception"
                        + " is swallowed at the scheduler boundary with only a generic log entry"
                        + " and no job-level context.",
                "A @Scheduled method that has no catch blocks, carries no instrumentation"
                        + " annotation, and makes at least one external method call.",
                "A global TaskScheduler ErrorHandler configured elsewhere is not visible here"
                        + " and will not suppress the finding. If a central error handler is"
                        + " in place, suppress per-method with a diagscope:ignore comment.");

        // ── Database / Performance: bulk writes ───────────────────────────────
        put(catalog, BulkOperationInLoopRule.ID,
                "Repository write inside a loop",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring Data, JPA / Hibernate, or any DAO layer",
                "A repository write operation (save, delete, update, persist, merge) is called"
                        + " inside a loop body, issuing one database round-trip per iteration.",
                "With N items: N network round-trips, N lock acquisitions, N transaction log"
                        + " entries. Spring Data saveAll() and deleteAllById() collapse all of"
                        + " these into one or two round-trips. The performance difference is"
                        + " 10–100× under production data volumes.",
                "An invocation of a repository write method whose insideLoop flag is true,"
                        + " on a receiver whose type suggests a Spring Data repository or JPA"
                        + " EntityManager.",
                "Batch methods already used in the loop (saveAll, deleteAll, deleteAllById)"
                        + " are not flagged. A globally-batched strategy at a lower layer is not"
                        + " visible here and may cause false positives.");

        // ── Observability: span lifecycle ─────────────────────────────────────
        put(catalog, SpanNotClosedRule.ID,
                "Span started but not closed on all paths",
                "observability", Severity.ERROR, ALL_LANGUAGES,
                "OpenTelemetry, Micrometer Tracing, Zipkin Brave, or any tracer API",
                "A tracing span is started but its end() (or finish()) call is not guarded by a"
                        + " finally block, leaving the span open on exception paths.",
                "An unclosed span leaks in the tracer's in-memory buffer. Under Zipkin/Jaeger"
                        + " it may never be exported, or exports with a nonsensical duration."
                        + " Under high traffic, leaked spans exhaust the export buffer and cause"
                        + " span drops across all operations, not just the affected one.",
                "A span-start invocation (startSpan, start, buildAndStart) on a tracer-like"
                        + " receiver, where no corresponding span-end invocation (end, finish)"
                        + " is found inside a finally block or try-with-resources in the same"
                        + " method.",
                "If the span is stored in a field or handed to another method responsible for"
                        + " closing it, this rule produces a false positive. If try-with-resources"
                        + " on the Scope wraps the span, the resource-managed flag suppresses it.");

        // ── Kafka: dead-letter topic ──────────────────────────────────────────
        put(catalog, KafkaDeadLetterNotConfiguredRule.ID,
                "Kafka listener has no dead-letter topic configured",
                "kafka", Severity.WARNING, ALL_LANGUAGES,
                "Spring Kafka (@KafkaListener)",
                "A @KafkaListener method has no errorHandler attribute and no"
                        + " DeadLetterPublishingRecoverer visible at its call site.",
                "After retry exhaustion the failed message is silently discarded. No DLT means"
                        + " no visibility into what failed, no ability to replay, and no audit"
                        + " trail. In financial or event-sourced systems this is data loss"
                        + " disguised as successful processing.",
                "The @KafkaListener annotation has no errorHandler attribute, and no"
                        + " invocation in the method body references a dead-letter recoverer"
                        + " by name (DeadLetterPublishingRecoverer, DLT, DLQ).",
                "A globally-configured DefaultErrorHandler bean with a"
                        + " DeadLetterPublishingRecoverer is not visible at the listener call"
                        + " site and will not suppress this finding. If a central error handler"
                        + " is in place, suppress per-listener with a diagscope:ignore comment.");

        // ── Kafka / Transactions: transactional outbox ───────────────────────────
        put(catalog, OutboxPatternMissingRule.ID,
                "Database write and messaging send without transactional outbox",
                "kafka", Severity.WARNING, ALL_LANGUAGES,
                "Applications that use Spring Data (or JPA/JDBC) together with a message broker"
                        + " (Kafka, RabbitMQ, JMS, AWS SNS/SQS)",
                "A method performs a database write (save, delete, update, …) and a messaging send"
                        + " (kafkaTemplate.send, rabbitTemplate.convertAndSend, …) in the same"
                        + " method body without a transactional outbox or event-publisher bridge.",
                "If the application crashes between the database commit and the broker send, the"
                        + " event is silently lost. The database reflects the new state, but"
                        + " consumers never receive the notification. This split-brain is silent,"
                        + " appears only under failure conditions, and is extremely difficult to"
                        + " reconcile retroactively — especially in financial, audit, or"
                        + " event-sourced systems.",
                "An invocation matching a repository-write method name (save, delete, update, …)"
                        + " on a receiver type containing 'repository', 'dao', or 'entitymanager'"
                        + " appears in the same method as an invocation matching a broker-send"
                        + " method name on a receiver type containing 'kafkatemplate', 'rabbit',"
                        + " 'jmstemplate', etc., and no publishEvent() or"
                        + " @TransactionalEventListener is observed.",
                "A globally-configured outbox relay (Debezium, Transactional outbox table) is"
                        + " not visible at the method call site and will not suppress this finding."
                        + " If the outbox is managed externally, suppress with diagscope:ignore.");

        // ── Security: hardcoded secrets ──────────────────────────────────────────
        put(catalog, SecretInStringLiteralRule.ID,
                "Hardcoded secret in source code",
                "security", Severity.ERROR, ALL_LANGUAGES,
                "Any application with authentication, API calls, or external service integration",
                "A method call whose name implies a credential (setPassword, withApiKey, …) receives"
                        + " a non-placeholder string literal as its first argument, or a map/config"
                        + " put call uses a key that contains a credential hint word with a"
                        + " non-placeholder string literal as the value.",
                "Hardcoded secrets are committed to source control and remain in git history"
                        + " permanently — even after deletion. They appear in CI logs, IDE"
                        + " auto-completion, and code-review diffs. Rotation is impossible without"
                        + " a code change and full re-deploy. This is one of the top causes of"
                        + " credential leaks in enterprise codebases.",
                "An invocation whose name (normalised to lowercase without separators) contains"
                        + " a secret hint word (password, secret, apikey, token, credential,"
                        + " privatekey, passphrase) has a first argument that is a non-empty,"
                        + " non-placeholder Java string literal. Excluded: Spring property"
                        + " references (${…}), SpEL (#{…}), and common stand-in strings.",
                "Only call-site string literals are inspected; secrets assigned to fields or"
                        + " local variables are not detected. Test-only code may produce false"
                        + " positives if placeholder values do not match the exclusion list.");

        // ── Concurrency: atomic operations ────────────────────────────────────
        put(catalog, CheckThenActOnMapRule.ID,
                "Non-atomic check-then-act on Map or Collection",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application with shared Map or Collection state",
                "A method calls a membership-test method (containsKey, contains) on a receiver"
                        + " and then calls a mutating method (put, add, remove) on the same"
                        + " receiver without using an atomic alternative.",
                "Between the check and the mutation another thread can modify the collection,"
                        + " causing a lost update, duplicate entry, or inconsistent state. The"
                        + " bug is absent under single-threaded load and appears only under"
                        + " concurrent access, making it difficult to reproduce.",
                "Both a membership-check call and a mutating call target the same named receiver"
                        + " in the same method body, and no atomic alternative (putIfAbsent,"
                        + " computeIfAbsent, merge) is observed on that receiver.",
                "The rule compares receiver scope names; if the map is accessed through different"
                        + " aliases in the same method the pattern may not be detected. Single-"
                        + " threaded or read-only code paths will produce false positives if the"
                        + " collection is known not to be shared.");

        // ── Kotlin coroutines: unhandled exceptions ───────────────────────────
        put(catalog, CoroutineExceptionNotHandledRule.ID,
                "Coroutine launched without a CoroutineExceptionHandler",
                "kotlin-coroutines", Severity.ERROR, Set.of("kotlin"),
                "Kotlin applications using kotlinx.coroutines",
                "A 'launch' or 'async' coroutine builder call has no CoroutineExceptionHandler"
                        + " in its context. Exceptions thrown inside the coroutine body do not"
                        + " propagate to the caller — they travel to the nearest exception handler"
                        + " in the scope hierarchy, or are silently dropped for GlobalScope.",
                "Without a visible handler the application loses all diagnostic context for the"
                        + " failure: no structured log entry, no MDC, no metric increment, and no"
                        + " retry. For GlobalScope the failure is completely invisible — the"
                        + " coroutine simply stops executing. In production this surfaces as missing"
                        + " data or unanswered requests with no observable cause.",
                "DiagScope detects calls to 'launch' or 'async' on a receiver whose name ends"
                        + " with 'Scope', equals 'GlobalScope', or is blank (implicit this). The"
                        + " finding is suppressed when any argument to the builder contains the"
                        + " text 'CoroutineExceptionHandler'.",
                "The rule cannot detect handlers installed in parent scopes or application-level"
                        + " coroutine exception handlers registered via ServiceLoader. Coroutine"
                        + " builders inside suspending functions reachable through an indirect call"
                        + " are analysed but the parent coroutine context is not tracked.");

        // ── Kotlin coroutines: uncaught Flow exceptions ───────────────────────
        put(catalog, FlowExceptionNotCaughtRule.ID,
                "Kotlin Flow collected without a .catch operator",
                "kotlin-coroutines", Severity.ERROR, Set.of("kotlin"),
                "Kotlin applications using kotlinx.coroutines Flow",
                "A Flow terminal operator ('collect' or 'launchIn') is called without a"
                        + " preceding '.catch {}' operator in the same expression chain or"
                        + " method body, and the call is not enclosed in a try/catch block.",
                "An uncaught exception inside a Flow operator propagates to the collecting"
                        + " coroutine and cancels it. Because Flow is cold, exceptions surface only"
                        + " at the terminal and are invisible to callers. In production this"
                        + " typically causes silent data loss — items stop arriving, the coroutine"
                        + " cancels, and no error entry appears in the log.",
                "DiagScope inspects the scope text of every 'collect' / 'launchIn' invocation."
                        + " If the scope contains '.catch' the chain already includes the catch"
                        + " operator and the finding is suppressed. The rule also suppresses when"
                        + " any other invocation named 'catch' exists in the same method (covering"
                        + " the variable-assignment pattern) or when the method has a try/catch block.",
                "The rule cannot resolve type aliases or flows defined in another method."
                        + " A '.catch' applied to a different flow in the same method suppresses the"
                        + " finding even if the collected flow is distinct.");

        // ── Kotlin coroutines: blocking calls inside coroutine builders ───────
        put(catalog, BlockingCallInCoroutineRule.ID,
                "Blocking JVM call inside a coroutine builder lambda",
                "kotlin-coroutines", Severity.ERROR, Set.of("kotlin"),
                "Kotlin applications using kotlinx.coroutines",
                "A blocking JVM call (Thread.sleep, Object.wait, synchronous IO, or"
                        + " CompletableFuture.get/join) is detected inside the lambda body of a"
                        + " 'launch', 'async', or 'runBlocking' coroutine builder, without wrapping"
                        + " in 'withContext(Dispatchers.IO)'.",
                "Blocking a coroutine dispatcher thread makes it unavailable for other coroutines."
                        + " The default dispatcher pool size equals the number of CPU cores;"
                        + " a single blocking call under load starves all other coroutines on that"
                        + " dispatcher, causing latency spikes that are hard to diagnose because"
                        + " thread dumps show legitimate-looking call stacks.",
                "DiagScope inspects the text of each lambda argument on 'launch', 'async', and"
                        + " 'runBlocking' invocations for known blocking patterns. The finding is"
                        + " suppressed when the lambda text contains"
                        + " 'withContext(Dispatchers.IO' or 'withContext(Dispatchers.Default'.",
                "The rule cannot detect blocking calls hidden inside helper functions called"
                        + " from the lambda — inter-procedural analysis would be required."
                        + " Deeply nested withContext wrappers inside complex lambdas may not"
                        + " suppress the finding correctly, causing false positives.");

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
