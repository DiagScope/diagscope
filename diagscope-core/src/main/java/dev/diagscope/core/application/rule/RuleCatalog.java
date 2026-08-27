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

        // ── AOP / Proxy: @Transactional on interface ─────────────────────────
        put(catalog, TransactionalOnInterfaceRule.ID,
                "@Transactional on interface method ignored by CGLIB proxy",
                "aop-proxy", Severity.ERROR, ALL_LANGUAGES,
                "Spring applications using CGLIB proxying (the default since Spring Boot 2)",
                "A method declared on an interface is annotated with @Transactional. Under"
                        + " CGLIB subclass proxying, annotations on interfaces are not picked"
                        + " up — no transaction boundary is opened and rollback has no effect.",
                "CGLIB creates a subclass of the concrete bean class; it does not read"
                        + " annotations from interfaces. An interface-level @Transactional is"
                        + " silently ignored. The failure is discovered at runtime when a rollback"
                        + " fails or writes appear outside expected transaction boundaries.",
                "DiagScope detects @Transactional on methods whose declaring type is an"
                        + " interface (resolved from the AST type declaration kind). Confidence is"
                        + " HIGH because the AST explicitly records whether a type is an interface.",
                "Methods in JDK dynamic proxy mode (proxyTargetClass=false) would correctly"
                        + " pick up the interface annotation, but this mode is not the default"
                        + " and should not be relied upon.");

        // ── Database / Performance: missing pagination ────────────────────────
        put(catalog, MissingPaginationRule.ID,
                "Repository method returns unbounded collection without Pageable",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring Data repositories (types ending with Repository or annotated @Repository)",
                "A repository method returns a List, Collection, Iterable, or Set without a"
                        + " Pageable parameter. There is no LIMIT clause — the query loads all"
                        + " matching rows into memory.",
                "On a small dataset the query is fast; with production data volumes it loads"
                        + " thousands or millions of rows. Manifests as OOM errors or timeouts"
                        + " that appear months after go-live when data grows beyond test sizes.",
                "DiagScope inspects the return type of repository methods (types matching the"
                        + " Repository/Dao naming pattern) for collection return types. Methods"
                        + " with a Pageable parameter, or names containing count/delete/save, are"
                        + " suppressed.",
                "The rule matches by type name pattern (ends with Repository); a type with that"
                        + " suffix that is not a Spring Data repository produces a false positive."
                        + " Reactive repositories returning Flux<T> are flagged but Flux supports"
                        + " .take(n) for limiting — the finding may be a false positive there.");

        // ── Kafka: retry without exponential backoff ──────────────────────────
        put(catalog, KafkaRetryWithoutBackoffRule.ID,
                "Kafka retry configured with zero-delay fixed backoff",
                "kafka", Severity.WARNING, ALL_LANGUAGES,
                "Spring Kafka applications using DefaultErrorHandler or RetryTopicConfiguration",
                "A FixedBackOff is constructed with an interval below 100ms and more than one"
                        + " retry attempt. This causes rapid retry storms against a failing"
                        + " downstream, amplifying the failure rather than allowing recovery.",
                "A rapid retry loop hammers the already-failing downstream — broker, database,"
                        + " or external API — at hundreds of attempts per second. This extends"
                        + " the outage, triggers rate limiting, and may exhaust connection pools"
                        + " before the downstream has any chance to recover.",
                "DiagScope detects constructor calls to FixedBackOff(interval, maxAttempts)"
                        + " (captured via ObjectCreationExpr in the Java parser and as a regular"
                        + " call expression in the Kotlin parser). The interval argument is parsed"
                        + " as a numeric literal; variable references are not resolved.",
                "Variable-based interval values (e.g., FixedBackOff(intervalMs, 3)) cannot be"
                        + " resolved statically and are skipped, producing false negatives when"
                        + " the variable happens to be zero.");

        // ── Exception handling: throw in finally suppresses original exception ──
        put(catalog, ExceptionSuppressedInFinallyRule.ID,
                "throw in finally block silently discards the original exception",
                "exception-handling", Severity.ERROR, ALL_LANGUAGES,
                "Any Java or Kotlin method that uses try-finally",
                "A 'throw' statement inside a 'finally' block discards any exception propagating"
                        + " from the 'try' body. The root-cause exception is silently replaced by"
                        + " the finally exception, hiding the real failure from logs and monitoring.",
                "Engineers responding to an incident see a secondary cleanup exception instead of"
                        + " the actual root cause. The misleading stack trace extends investigation"
                        + " time significantly. In some cases the root-cause exception is never"
                        + " logged, making the failure completely invisible.",
                "DiagScope detects 'throw' statements that appear directly inside the 'finally'"
                        + " block of a 'try' statement (captured by the Java and Kotlin parsers as"
                        + " 'throwsInFinally' locations on the method model). Confidence is HIGH"
                        + " when the method also has catch clauses or non-finally invocations"
                        + " (indicating the protected block can throw).",
                "A throw in 'finally' inside a nested 'try' within the finally block is not"
                        + " reported — the nested try handles it. Intentional finally-throws"
                        + " (e.g. resource cleanup that must propagate) are rare and should be"
                        + " documented inline.");

        // ── Security: mass assignment via JPA entity as @RequestBody ──────────
        put(catalog, MassAssignmentRiskRule.ID,
                "JPA entity used directly as @RequestBody — mass assignment risk",
                "security", Severity.WARNING, ALL_LANGUAGES,
                "Spring MVC REST controllers with @PostMapping or @PutMapping endpoints",
                "A controller write-endpoint method (POST, PUT, or PATCH) accepts a JPA @Entity"
                        + " class directly as a request body parameter. Jackson deserializes every"
                        + " matching JSON key into the entity — including server-controlled fields"
                        + " like 'id', 'role', 'createdAt', or 'ownerId' that the caller should"
                        + " never be allowed to set.",
                "An attacker can send arbitrary JSON keys to overwrite fields they should not"
                        + " control. In the best case the API behaves unexpectedly; in the worst"
                        + " case it becomes a privilege-escalation or data-tampering vulnerability."
                        + " The problem is often invisible during normal testing because legitimate"
                        + " clients simply do not send the extra fields.",
                "DiagScope first builds a set of entity type names by collecting declaring types"
                        + " whose methods carry 'Entity' or 'Table' in their effective annotations"
                        + " (class-level annotations are merged onto every method, so this is"
                        + " accurate). It then finds write-endpoint methods and checks whether any"
                        + " parameter type's simple name appears in that set.",
                "The heuristic uses simple-type-name matching, so a non-entity class that happens"
                        + " to have the same simple name as an entity will produce a false positive."
                        + " The rule also cannot detect @RequestBody annotation on parameters"
                        + " directly — it applies to all parameters of write-endpoint methods.");

        // ── Resilience: retry scope ────────────────────────────────────────────
        put(catalog, RetryOnAllExceptionsRule.ID,
                "@Retryable with no exception filter retries on every failure",
                "resilience", Severity.WARNING, ALL_LANGUAGES,
                "Spring Retry (@Retryable) or MicroProfile Fault Tolerance (@Retry)",
                "A @Retryable method declares no 'include', 'value', or 'retryFor' attribute."
                        + " Every thrown exception — including NullPointerException,"
                        + " OutOfMemoryError, and programming defects — triggers the retry budget.",
                "Retrying a programming error delays failure without any chance of recovery."
                        + " The upstream caller is blocked for the full retry budget, the downstream"
                        + " receives redundant requests, and the retry budget is exhausted on a"
                        + " scenario it cannot resolve. Retries should target transient failures only.",
                "The method carries @Retryable but its annotation attributes map contains no"
                        + " 'include', 'value', or 'retryFor' key — the exception filter is absent.",
                "Methods using @Recover fallbacks are not excluded; they should still filter"
                        + " the retry target to transient exceptions.");

        // ── Configuration: required properties without defaults ────────────────
        put(catalog, ValueWithoutDefaultRule.ID,
                "@Value property placeholder with no default value",
                "configuration", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Value for external configuration",
                "A @Value annotation uses a ${property} placeholder with no default-value suffix"
                        + " (:fallback). If the property is absent from all config sources,"
                        + " Spring throws IllegalArgumentException at startup — before the"
                        + " application is ready to serve traffic.",
                "Missing required properties fail deployment pipelines late and surface only when"
                        + " the application starts in a new environment (staging, DR, a new"
                        + " cloud region) that does not have the full config set. The failure is"
                        + " a loud startup crash rather than a silent runtime error, but it blocks"
                        + " deployments at exactly the wrong time.",
                "The @Value attribute value matches ${…} without a colon separator. SpEL"
                        + " expressions (#{…}) and values with a default (:) are excluded.",
                "Only @Value on methods (setter injection) is detected. The more common"
                        + " field-injection pattern requires field-level annotation support, which"
                        + " is not currently in the domain model.");

        // ── Security: entity exposed in REST response ──────────────────────────
        put(catalog, EntityExposedInRestResponseRule.ID,
                "JPA entity returned directly from REST endpoint",
                "security", Severity.WARNING, ALL_LANGUAGES,
                "Spring MVC REST controllers using Spring Data JPA",
                "A REST endpoint method returns a JPA @Entity class directly as its response"
                        + " body. Jackson serialises every mapped field — including password"
                        + " hashes, audit metadata, foreign-key IDs, bidirectional associations"
                        + " that cause recursive cycles, and internal fields the API contract"
                        + " never intends to expose.",
                "Returning the persistence model as the API model couples the database schema"
                        + " to the API contract. Schema changes break the API, recursive"
                        + " associations produce StackOverflowError, and sensitive fields"
                        + " (e.g. hashed passwords, internal status codes) leak to clients."
                        + " The outbound complement of MASS_ASSIGNMENT_RISK — the same class"
                        + " that should not be deserialized from input should not be serialized"
                        + " to output.",
                "Two-pass analysis: entity type names are collected from methods whose declaring"
                        + " type carries @Entity or @Table (same heuristic as MASS_ASSIGNMENT_RISK)."
                        + " REST endpoint methods (carrying any HTTP-method mapping annotation) are"
                        + " then checked for a returnType whose simple name, or generic argument"
                        + " inside ResponseEntity<…>, matches an entity name.",
                "Simple-name matching may produce false positives if a non-entity class shares a"
                        + " name with a detected entity. Generic wrappers beyond one level of"
                        + " nesting (e.g. ResponseEntity<Page<Entity>>) check the innermost"
                        + " generic argument.");

        // ── Performance: HTTP call inside transaction ──────────────────────────
        put(catalog, TransactionWithHttpCallRule.ID,
                "HTTP call inside a @Transactional method holds DB connection",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications combining @Transactional with REST or HTTP clients",
                "A @Transactional method makes an outbound HTTP call. The database connection"
                        + " is held open for the full duration of the HTTP roundtrip.",
                "HTTP roundtrips typically take 100–500 ms under normal conditions and can take"
                        + " seconds or minutes under degradation. The held connection cannot be"
                        + " used by other requests. Under concurrent load this exhausts the"
                        + " connection pool: all threads queue on a free connection, latency"
                        + " spikes across all endpoints, and circuit breakers open. The pattern"
                        + " is invisible in low-traffic environments and catastrophic under spikes.",
                "The method carries @Transactional and its invocations list contains a call"
                        + " on a receiver type that matches a known HTTP client type: RestTemplate,"
                        + " WebClient, OkHttpClient, HttpClient, CloseableHttpClient, Feign.",
                "HTTP clients configured at a lower layer as fields or injected beans are"
                        + " matched by receiver type name. A custom HTTP client wrapper with an"
                        + " unconventional type name will not be detected.");

        // ── Performance: HTTP client created per request ───────────────────────
        put(catalog, HttpClientCreatedPerRequestRule.ID,
                "HTTP client constructed inside method body instead of as a shared bean",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using RestTemplate, WebClient, or OkHttp",
                "An HTTP client instance is created inside a regular method body. HTTP client"
                        + " construction allocates a thread pool, a connection pool, and SSL"
                        + " context — one per call.",
                "In a hot path (request handler, Kafka listener, scheduled job), this creates"
                        + " a new client on every invocation. None of the clients share"
                        + " connections, so the connection pool is never reused. File-descriptor"
                        + " exhaustion, heap pressure, and SSL handshake overhead accumulate"
                        + " quickly under load. The correct pattern is a single shared bean"
                        + " injected at construction time.",
                "A constructor call (e.g. new RestTemplate(), new OkHttpClient()) or a"
                        + " builder-pattern call (.build() on a WebClient.Builder receiver) in"
                        + " a method body not annotated with @Bean or @Configuration.",
                "HTTP client construction in @Bean methods or test lifecycle methods (@Before,"
                        + " @BeforeEach) is excluded. A builder call whose receiver scope does"
                        + " not explicitly name a known HTTP client type may be missed.");

        // ── Security: CORS wildcard origin ────────────────────────────────────
        put(catalog, CorsWildcardOriginRule.ID,
                "@CrossOrigin(\"*\") permits any browser origin — CSRF risk",
                "security", Severity.WARNING, ALL_LANGUAGES,
                "Spring MVC REST controllers using @CrossOrigin",
                "A REST endpoint carries @CrossOrigin(\"*\") or @CrossOrigin(origins = \"*\"),"
                        + " permitting any browser origin to make cross-origin requests to"
                        + " this endpoint.",
                "The browser same-origin policy protects users from cross-site request forgery."
                        + " A wildcard CORS policy nullifies this protection. Combined with"
                        + " session cookies or token-based auth, any page on the internet can"
                        + " make authenticated requests to this endpoint on behalf of a"
                        + " logged-in user. The impact ranges from data exfiltration to"
                        + " account takeover depending on what the endpoint exposes.",
                "The method carries a REST mapping annotation and its CrossOrigin annotation"
                        + " attribute (value or origins) equals the literal string \"*\".",
                "CORS configured globally via WebMvcConfigurer is not detected here; this"
                        + " rule only inspects @CrossOrigin annotations. A class-level"
                        + " @CrossOrigin(\"*\") is merged into every method and will produce"
                        + " one finding per REST endpoint — intentionally, since each"
                        + " endpoint is independently accessible by a cross-origin attacker.");

        // ── Kafka / Configuration: hardcoded topic names ───────────────────────
        put(catalog, KafkaTopicHardcodedRule.ID,
                "Kafka listener topic name is a hardcoded string literal",
                "kafka", Severity.INFO, ALL_LANGUAGES,
                "Spring Kafka applications using @KafkaListener",
                "A @KafkaListener topics attribute contains a plain string literal instead of a"
                        + " property placeholder (${kafka.topic.name}). Topic names typically"
                        + " differ between environments.",
                "In a deployment to an environment where the hardcoded topic does not exist,"
                        + " the listener silently receives no messages. The failure is discovered"
                        + " only when downstream business metrics are missing — a slow feedback"
                        + " loop that is hard to correlate with the deployment event.",
                "The @KafkaListener annotation has a topics attribute whose value does not"
                        + " contain a ${…} placeholder pattern. Listeners with no topics attribute"
                        + " (e.g. those using topicPattern or @KafkaHandler class-level setup)"
                        + " are not flagged.",
                "Topic names that are intentionally the same across all environments"
                        + " (rare but valid) will produce false positives. In those cases,"
                        + " suppress with a diagscope:ignore comment.");

        // ── Wave 3: minor domain extensions ──────────────────────────────────
        put(catalog, ScheduledNoInitialDelayRule.ID,
                "@Scheduled task fires immediately at application startup",
                "configuration", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Scheduled with fixedRate or fixedDelay",
                "A @Scheduled method uses fixedRate or fixedDelay without an initialDelay."
                        + " Spring fires the task immediately after the application starts — before"
                        + " the application is fully ready to serve traffic.",
                "In Kubernetes or any orchestrated environment, the scheduled task can execute"
                        + " before the readiness probe passes, before connection pools are warmed"
                        + " up, or before downstream services are reachable. A failed startup job"
                        + " can prevent the pod from passing health checks, causing restart loops"
                        + " that are difficult to distinguish from application code bugs.",
                "The method is annotated with @Scheduled and has fixedRate or fixedDelay in"
                        + " its annotation attributes, but no initialDelay or initialDelayString"
                        + " attribute is set (or it is explicitly 0).",
                "Cron-based schedules (@Scheduled(cron = \"...\")) are not flagged because the"
                        + " first execution time is determined by the cron expression, not startup"
                        + " time. Single-instance startup jobs that are intentionally designed to"
                        + " run at boot should use ApplicationRunner or CommandLineRunner instead.");

        put(catalog, SynchronizedOnSpringBeanRule.ID,
                "synchronized method on a Spring-managed bean",
                "aop", Severity.ERROR, ALL_LANGUAGES,
                "Spring applications using @Component, @Service, @Repository, or @Controller",
                "A method uses the 'synchronized' keyword (Java) or @Synchronized (Kotlin)"
                        + " on a class managed by Spring. Spring proxies the bean; the"
                        + " 'synchronized' lock is acquired on the proxy, not the bean instance.",
                "Two threads can execute the method simultaneously on the same logical bean"
                        + " because each acquires the lock on a different proxy object. The"
                        + " synchronisation silently does nothing, leaving shared mutable state"
                        + " exposed to data races that appear only under concurrent load.",
                "The method's effective annotation set (after class-level merging) contains the"
                        + " Spring stereotype annotation AND the 'Synchronized' signal. For Java"
                        + " the parser synthesises this signal from the 'synchronized' keyword"
                        + " modifier; for Kotlin the @Synchronized annotation is already present"
                        + " as a real annotation entry.",
                "A final class with no AOP annotations and no Spring proxy involvement would not"
                        + " be wrapped by CGLIB, making synchronization safe — but DiagScope"
                        + " cannot confirm the absence of proxying at the static analysis level,"
                        + " so those cases are still flagged.");

        put(catalog, FieldInjectionUsedRule.ID,
                "Field injection instead of constructor injection",
                "spring", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Component, @Service, @Repository, or @Controller",
                "A Spring-managed bean declares one or more fields annotated with @Autowired,"
                        + " @Inject, or @Resource. Field injection bypasses the constructor,"
                        + " injecting dependencies directly into private fields via reflection.",
                "Field-injected beans hide their dependencies from the constructor signature,"
                        + " making it impossible to instantiate them in unit tests without a"
                        + " Spring context. Circular dependencies are not detected at startup."
                        + " Fields cannot be final, preventing immutability guarantees. Spring"
                        + " itself has recommended constructor injection since Spring 4.0 and"
                        + " its own documentation explicitly discourages field injection.",
                "The parser scans the class body for field declarations annotated with"
                        + " @Autowired, @Inject, or @Resource, then synthesises a"
                        + " 'FieldInjectionPresent' marker on the declaring type. This marker"
                        + " is propagated to every method in that class by the annotation-merge"
                        + " step so that the rule can detect it without a field-level domain object.",
                "Only classes with a Spring stereotype annotation are reported. Non-Spring classes"
                        + " that use @Inject or @Resource for other DI frameworks are not flagged."
                        + " One finding is emitted per class, not one per injected field, to"
                        + " reduce noise when multiple fields are involved.");

        // ── Wave 4 ────────────────────────────────────────────────────────────
        put(catalog, TransactionalReadOnlyMissingRule.ID,
                "@Transactional query method missing readOnly = true",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Transactional with Spring Data or JPA",
                "A @Transactional method whose name starts with a query hint (find, get, list,"
                        + " count, query, fetch, search, exists, load) does not declare"
                        + " readOnly = true. The transaction opens in full read-write mode.",
                "A full read-write transaction holds a more expensive connection-pool slot and"
                        + " prevents the JDBC driver from routing the query to a read replica."
                        + " Hibernate does not skip its dirty-check flush at session end,"
                        + " adding unnecessary CPU overhead. Under connection-pool pressure,"
                        + " this delays all requests — not just the query-heavy ones.",
                "The method carries @Transactional, its annotation attributes do not include"
                        + " readOnly = true, and its name starts with a recognized query prefix.",
                "False positives occur for methods that read data and then conditionally write"
                        + " — those methods intentionally need a read-write transaction."
                        + " Use readOnly = true only when the method is guaranteed not to"
                        + " perform any write operations.");

        put(catalog, AsyncDefaultExecutorRule.ID,
                "@Async without a named executor uses SimpleAsyncTaskExecutor",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Async",
                "An @Async method specifies no executor name. Spring falls back to"
                        + " SimpleAsyncTaskExecutor, which spawns a new OS thread for every"
                        + " method invocation with no pooling or reuse.",
                "Each call creates one OS thread, consuming stack memory (256KB–1MB), file"
                        + " descriptors, and OS scheduling slots. Under any meaningful load the"
                        + " thread count grows without bound. The failure surface as gradual"
                        + " heap growth and increasing thread count in JMX, culminating in"
                        + " OutOfMemoryError: unable to create new native thread during spikes.",
                "The method carries @Async and its annotation attribute map has no 'value'"
                        + " key (the executor bean name).",
                "A globally-configured AsyncConfigurer that replaces the default executor is"
                        + " not visible at the call site and will not suppress this finding."
                        + " If a central async configuration is in place, suppress per-method.");

        put(catalog, MissingResponseStatusRule.ID,
                "@ExceptionHandler without @ResponseStatus returns HTTP 200 on errors",
                "observability", Severity.WARNING, ALL_LANGUAGES,
                "Spring MVC @Controller, @RestController, @ControllerAdvice, @RestControllerAdvice",
                "A @ExceptionHandler method in a controller or advice class carries no"
                        + " @ResponseStatus annotation and its return type is not ResponseEntity."
                        + " Spring MVC defaults to HTTP 200 OK for such handlers.",
                "Clients that inspect the HTTP status to distinguish success from failure"
                        + " interpret the error response as a success. Monitoring systems that"
                        + " count 4xx/5xx responses see no errors. SLO dashboards show 100%"
                        + " success. Caching layers may cache the 200 response and prevent"
                        + " clients from retrying. The error is invisible at the HTTP layer.",
                "The method carries @ExceptionHandler, is declared in a class with a"
                        + " controller or advice annotation, has no @ResponseStatus, and its"
                        + " return type does not contain ResponseEntity.",
                "Handlers that intentionally return 200 with error detail in the body"
                        + " (e.g. a legacy API contract) are false positives. Suppress with"
                        + " diagscope:ignore or add an explicit @ResponseStatus(OK).");

        put(catalog, CacheEvictMissingRule.ID,
                "Class uses @Cacheable but has no cache eviction",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using Spring Cache (@Cacheable, @CacheEvict, @CachePut)",
                "A class declares at least one @Cacheable method but no @CacheEvict or"
                        + " @CachePut method anywhere in the same declaring type. Without an"
                        + " explicit eviction strategy, cache entries accumulate indefinitely.",
                "In local caches (ConcurrentHashMap, Caffeine) the heap grows unboundedly;"
                        + " in distributed caches (Redis, Hazelcast) the store fills until"
                        + " eviction policies discard entries silently. Stale data is served"
                        + " for the lifetime of the application or until a restart. The problem"
                        + " is invisible in development (small data volumes, short sessions)"
                        + " and surfaces weeks or months after go-live.",
                "Methods are grouped by declaring type. A type with at least one @Cacheable"
                        + " method is checked for any @CacheEvict or @CachePut method. One"
                        + " finding is emitted per type, at the first @Cacheable method's location.",
                "External eviction policies (Redis TTL, Caffeine expireAfterWrite) and a"
                        + " globally-configured CacheManager with TTL are not visible to static"
                        + " analysis and produce false positives. Suppress with diagscope:ignore"
                        + " when TTL-only eviction is the intentional strategy.");

        put(catalog, TransactionalOnFinalMethodRule.ID,
                "@Transactional or @Async on a final method is silently ignored",
                "aop-proxy", Severity.ERROR, ALL_LANGUAGES,
                "Spring applications using @Transactional or @Async (CGLIB proxy mode)",
                "A method annotated with @Transactional or @Async is also final (Java 'final'"
                        + " keyword; Kotlin methods without 'open'). CGLIB cannot subclass a"
                        + " final method, so the annotation is silently ignored at runtime.",
                "For @Transactional: no transaction boundary is opened, writes run in"
                        + " auto-commit mode, and rollback has no effect — data consistency"
                        + " guarantees are silently lost. For @Async: the method runs"
                        + " synchronously on the calling thread while the caller believes the"
                        + " work was dispatched asynchronously. Both failures are invisible"
                        + " in development and discovered only under production conditions.",
                "The parser synthesises a 'Final' signal for the Java 'final' modifier"
                        + " and for Kotlin methods that lack 'open', 'abstract', or 'override'."
                        + " The rule fires when 'Final' AND (@Transactional OR @Async) appear"
                        + " in the effective annotation set.",
                "The 'kotlin-spring' compiler plugin (plugin.spring) automatically opens"
                        + " all Spring-annotated classes and methods; projects using it will"
                        + " not be affected at runtime, but this rule still fires because the"
                        + " plugin behaviour is not visible to static analysis. Suppress if"
                        + " the plugin is configured for the entire project.");

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
