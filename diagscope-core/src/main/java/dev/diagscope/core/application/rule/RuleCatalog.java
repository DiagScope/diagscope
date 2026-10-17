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
                "A direct call to System.out/System.err print/println on a reachable path. Methods of a @Command class and main methods are skipped: the output of a command-line program is its product.",
                "Only print and println are matched, so printf and format are not reported. A program that is not annotated with @Command and prints on purpose is reported; suppress with diagscope:ignore.");

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
                "Looking an instrument up by name and tags on every iteration allocates the tag objects and goes "
                        + "through the registry's map each time, on the hot path. Re-resolving the same name and tags "
                        + "returns the existing meter, so the time series are not multiplied unless the tags differ per "
                        + "iteration, which is then a cardinality problem (HIGH_CARDINALITY_METRIC_TAG). Resolve the "
                        + "instrument once outside the loop.",
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
                "Spring JDBC (JdbcTemplate)",
                "A raw Connection is taken from a JdbcTemplate (for example "
                        + "jdbcTemplate.getDataSource().getConnection()) and used directly.",
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
                "A blocking operation (Thread.sleep, Object.wait, CountDownLatch.await, Future.get,"
                        + " Mono.block, Mutiny await().indefinitely(), or blocking I/O such as JDBC,"
                        + " RestTemplate and file access) runs on an event-loop thread: either in a"
                        + " method declared non-blocking, or in a helper that such a method calls.",
                "Reactive runtimes multiplex many requests onto a small number of event-loop"
                        + " threads. A single blocking call stalls the thread and prevents other"
                        + " events from being processed, cascading into a full service hang under"
                        + " load. Mutiny and Reactor refuse to block on those threads and fail with"
                        + " IllegalStateException at runtime.",
                "The event-loop context comes from an annotation (@NonBlocking, @Incoming, @Outgoing,"
                        + " @ReactiveTransactional, @WithTransaction, @WithSession, @MessageMapping) or from a"
                        + " reactive return type (Uni, Multi, Mono, Flux and RxJava types), and is followed"
                        + " along the flow's call path until a method declares another context (@Blocking,"
                        + " @RunOnVirtualThread, @Async). Receiver types are resolved when possible; when"
                        + " they are not, naming hints are used and the confidence drops to MEDIUM."
                        + " Thread-level blocking is reported as ERROR, blocking I/O as WARNING.",
                "A method that hands work to another thread anywhere in its body (subscribeOn,"
                        + " publishOn, runSubscriptionOn, executeBlocking, an executor) is skipped"
                        + " entirely, because source text cannot tell which lambda runs where."
                        + " Future.get(timeout, unit) and other bounded waits are not reported."
                        + " A reactive return type is weaker evidence than an annotation, so those"
                        + " findings carry MEDIUM confidence. Dynamic dispatch the call graph cannot"
                        + " resolve ends the inherited context.");

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
                "Method cyclomatic complexity is too high",
                "maintainability", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application",
                "A method's cyclomatic complexity exceeds " + HighMethodComplexityRule.COMPLEXITY_THRESHOLD + ".",
                "High cyclomatic complexity correlates strongly with defect density and maintenance"
                        + " cost. A method with many branches needs many tests to cover its paths, is hard to"
                        + " reason about during an incident, and is where unhandled combinations of conditions hide.",
                "The parsers count McCabe cyclomatic complexity per method: one path plus one for each if,"
                        + " for/while/do loop, catch clause, case or when branch, ternary, && / || operator and"
                        + " Kotlin elvis. Lambdas count towards their enclosing method. The finding appears when the"
                        + " count is above the threshold.",
                "The count is syntactic: a flat switch over many enum values or a validation method made of simple"
                        + " guards can be high and still easy to read. Generated or table-driven code is better"
                        + " excluded with ignoredPaths in diagscope.yml. Methods the parsers did not measure are"
                        + " skipped.");

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

        put(catalog, EntityManagerFindDereferenceRule.ID,
                "EntityManager.find() result dereferenced without null guard",
                "null-safety", Severity.WARNING, ALL_LANGUAGES,
                "JPA applications using EntityManager directly",
                "A value returned by EntityManager.find(...) is assigned to a local variable and"
                        + " then dereferenced without a syntax-visible null guard.",
                "EntityManager.find(...) returns null when no row exists. Dereferencing that value"
                        + " turns an expected missing-row case into a NullPointerException with"
                        + " little domain context.",
                "A find(...) call on an EntityManager-looking receiver is assigned to a variable,"
                        + " and a later invocation uses that variable as receiver without an observed"
                        + " Objects.requireNonNull, Optional.ofNullable, or equivalent guard.",
                "A method that compares the variable with null (x == null, x != null, or ?. and ?: in Kotlin)"
                        + " is not reported. A guard in a caller, or one that goes through a helper method, is"
                        + " not visible, so such code may need review.");

        // ── Transactions ────────────────────────────────────────────────────────
        put(catalog, MissingTransactionAnnotationRule.ID,
                "Write operation outside a transaction boundary",
                "transactions", Severity.WARNING, ALL_LANGUAGES,
                "Spring Data / JPA / Hibernate applications",
                "A method performs an EntityManager write (persist, merge, remove, flush), or two or more"
                        + " Spring Data write calls (save, delete, ...), with no @Transactional on the"
                        + " method, its class, or any caller on the traced path.",
                "Without a transaction boundary each write runs in its own auto-commit context."
                        + " If a subsequent operation in the same business action fails, the previously"
                        + " persisted changes cannot be rolled back, leaving data in an inconsistent state.",
                "A write-operation method call (matched by name against a curated set) on a receiver"
                        + " whose type or scope name suggests a Spring Data repository, DAO,"
                        + " or JPA EntityManager, in a method with no detectable @Transactional"
                        + " annotation on the method or its declaring class.",
                "A single Spring Data call is not reported: repository methods are transactional on their own,"
                        + " so one save cannot half-complete. The rule uses method-name and receiver-type"
                        + " heuristics, so a custom repository with an unconventional name may be missed, and"
                        + " a transaction opened by a caller outside the traced flow is invisible.");

        put(catalog, ReadOnlyTransactionWriteRule.ID,
                "Write operation inside read-only transaction",
                "transactions", Severity.ERROR, ALL_LANGUAGES,
                "Spring Data / JPA / Hibernate applications using @Transactional(readOnly = true)",
                "A method explicitly marked @Transactional(readOnly = true) performs a persistence"
                        + " write such as save, delete, persist, merge, flush, update, or executeUpdate.",
                "The method advertises a read-only transaction while mutating state. Depending on"
                        + " the database, provider, and connection routing, the write can fail at"
                        + " runtime, bypass expected flushing, or be routed to infrastructure intended"
                        + " only for reads.",
                "The method carries @Transactional with readOnly = true and contains a write-method"
                        + " invocation on a repository, DAO, EntityManager, Hibernate Session,"
                        + " JdbcTemplate, or similar persistence receiver.",
                "The rule relies on method names and receiver hints. Custom persistence abstractions"
                        + " with domain-specific write method names may be missed.");

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
                "resilience", Severity.INFO, ALL_LANGUAGES,
                "Spring and Quarkus applications using @Scheduled",
                "A @Scheduled method has no try/catch block and no instrumentation annotation,"
                        + " leaving any exception to propagate directly to the scheduler.",
                "The scheduler logs the exception and runs the task again, so the failure is not lost."
                        + " But the only trace is a generic framework log line: no metric, no alert and no"
                        + " job-level context. A job that fails on every run looks the same as a quiet one"
                        + " until someone reads the logs. (A task that really stops after one exception is a"
                        + " java.util.Timer, reported by TIMER_TASK_EXCEPTION_KILLS_TIMER.)",
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
                "With N items the loop issues N statements and, when it runs outside a transaction, N separate "
                        + "transactions with N commits. Moving the writes into one transaction removes the commits. Note "
                        + "that Spring Data JPA's saveAll() and deleteAllById() still run one statement per entity: real "
                        + "batching needs hibernate.jdbc.batch_size (and ordered inserts), and a bulk delete needs "
                        + "deleteAllInBatch() or a @Modifying query.",
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
                "kafka", Severity.INFO, ALL_LANGUAGES,
                "Spring Kafka (@KafkaListener)",
                "A @KafkaListener method has no errorHandler attribute and no"
                        + " DeadLetterPublishingRecoverer visible at its call site.",
                "With Spring Kafka's default error handler a record that keeps failing is retried a few times and "
                        + "then logged at ERROR and skipped. It is not silent, but the payload survives only in the log: "
                        + "there is no topic to inspect or replay it from.",
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
                "kotlin-coroutines", Severity.WARNING, Set.of("kotlin"),
                "Kotlin applications using kotlinx.coroutines",
                "A 'launch' or 'async' coroutine builder call has no CoroutineExceptionHandler"
                        + " in its context. An exception thrown inside a launched coroutine does not reach the"
                        + " caller: it goes to the parent scope, and from there to a CoroutineExceptionHandler.",
                "With no handler anywhere, kotlinx.coroutines passes the exception to the thread's uncaught"
                        + " exception handler, which by default prints a stack trace to stderr. The failure is not"
                        + " invisible, but it bypasses the application's logging: no structured entry, no MDC, no"
                        + " metric, no retry. A GlobalScope coroutine has no parent to cancel or report, so its"
                        + " failure ends only that coroutine. An async coroutine keeps its exception until await.",
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
                "kotlin-coroutines", Severity.WARNING, Set.of("kotlin"),
                "Kotlin applications using kotlinx.coroutines Flow",
                "A Flow terminal operator ('collect' or 'launchIn') is called without a"
                        + " preceding '.catch {}' operator in the same expression chain or"
                        + " method body, and the call is not enclosed in a try/catch block.",
                "An exception inside a Flow operator is rethrown at the terminal operator, in the collecting"
                        + " coroutine. If nothing there catches it, the coroutine fails like any other: a launch"
                        + " reaches the scope's exception handler (by default the stack trace goes to stderr), and"
                        + " an exception can cancel the whole scope. The failure is not silent, but the collection"
                        + " stops for good, with no logging or recovery at this call site.",
                "DiagScope inspects the scope text of every 'collect' / 'launchIn' invocation."
                        + " If the scope contains '.catch' the chain already includes the catch"
                        + " operator and the finding is suppressed. The rule also suppresses when"
                        + " any other invocation named 'catch' exists in the same method (covering"
                        + " the variable-assignment pattern) or when the method has a try/catch block.",
                "The rule cannot resolve type aliases or flows defined in another method."
                        + " A '.catch' applied to a different flow in the same method suppresses the"
                        + " finding even if the collected flow is distinct.");

        // ── Test sources (--include-tests) ───────────────────────────────────
        put(catalog, TestWithoutAssertionRule.ID,
                "Test runs code but asserts nothing",
                "testing", Severity.WARNING, ALL_LANGUAGES,
                "Projects scanned with --include-tests, using JUnit 4, JUnit 5 or Kotlin test frameworks",
                "A test method runs code but neither it nor the project methods it calls assert, verify, expect or fail on anything.",
                "A test with no check passes whatever the code does, so it adds coverage numbers and no safety. A regression that returns the wrong value, or never calls a collaborator, goes unnoticed until production. Such tests tend to survive because they are green.",
                "A test method (JUnit @Test, @ParameterizedTest, @RepeatedTest, @TestFactory) with at least one call, none of whose calls (or those of the project methods it calls, two levels deep) is named like an assertion, verification, expectation or fail.",
                "Assertions hidden behind a method outside the project, or a custom DSL with unusual names, are not recognised. Tests whose purpose is \"does not throw\" are reported; make that explicit with assertDoesNotThrow. Empty tests and tests declaring an expected exception are skipped.");

        put(catalog, TestSwallowsExceptionRule.ID,
                "Test catches a broad exception and carries on",
                "testing", Severity.WARNING, ALL_LANGUAGES,
                "Projects scanned with --include-tests, using JUnit 4, JUnit 5 or Kotlin test frameworks",
                "A test method catches Exception, Throwable, Error or AssertionError and neither rethrows nor asserts or fails afterwards.",
                "The catch converts a failing check into a passing test. The failure the test was written to expose is swallowed, and the suite stays green while the behaviour is broken.",
                "A catch of a broad type inside a test method with no rethrow, where no assertion or fail call appears at or after the catch line.",
                "An assertion later in the method, even one unrelated to the catch, suppresses the finding. Catches of specific exception types are not reported.");

        put(catalog, TestDisabledWithoutReasonRule.ID,
                "Test disabled without a reason",
                "testing", Severity.INFO, ALL_LANGUAGES,
                "Projects scanned with --include-tests, using JUnit 4, JUnit 5 or Kotlin test frameworks",
                "A test or test class carries @Disabled or @Ignore with no reason text.",
                "Nobody can tell whether the test waits for a fix, was flaky, or was forgotten. The coverage it gave is gone and nothing says when, or whether, it should return.",
                "A test method whose effective annotations include @Disabled or @Ignore with no value. A class disabled as a whole is reported once.",
                "Disabling through conditions (@EnabledIf, @DisabledOnOs) or build configuration is not analysed.");

        put(catalog, TestThreadSleepRule.ID,
                "Fixed sleep inside a test",
                "testing", Severity.INFO, ALL_LANGUAGES,
                "Projects scanned with --include-tests, using JUnit 4, JUnit 5 or Kotlin test frameworks",
                "A test method calls Thread.sleep or TimeUnit.sleep.",
                "A fixed wait is either too short, making the test fail on a slow machine, or long enough to be safe, slowing every run. Flaky tests train a team to ignore red builds.",
                "A call named sleep on Thread or TimeUnit inside a test method.",
                "Sleeps in helper classes that are not test methods are not reported.");

        put(catalog, TestBroadExpectedExceptionRule.ID,
                "Test expects a very general exception",
                "testing", Severity.INFO, ALL_LANGUAGES,
                "Projects scanned with --include-tests, using JUnit 4, JUnit 5 or Kotlin test frameworks",
                "A test expects Exception, RuntimeException or Throwable through assertThrows, isInstanceOf or @Test(expected = ...).",
                "Any exception satisfies the expectation, including a NullPointerException from a bug in the setup. The test keeps passing while the behaviour it describes is no longer there.",
                "assertThrows, isInstanceOf or hasCauseInstanceOf whose first argument is a broad exception class, or @Test(expected = ...) with one.",
                "Kotlin assertFailsWith<Exception>() carries the type as a generic argument that is not captured, so it is not reported.");

        put(catalog, VertxExecuteBlockingFailureIgnoredRule.ID,
                "Vert.x executeBlocking whose failure is never observed",
                "resilience", Severity.WARNING, ALL_LANGUAGES,
                "Vert.x applications that move blocking work off the event loop with executeBlocking",
                "A call to executeBlocking whose failure nobody observes: the returned future is discarded, the chain"
                        + " registers only onSuccess, or the result handler never checks failed() or cause().",
                "Blocking work is the work that throws: JDBC, file and HTTP clients. When it fails, the exception"
                        + " lives only in the future (or AsyncResult). If nobody reads it, the request never"
                        + " answers, the verticle never finishes starting, or the task silently did not run, and"
                        + " the log shows nothing.",
                "Three shapes: a discarded future (the call is a statement); a chain that registers no failure"
                        + " handler (onFailure, onComplete, compose, recover, otherwise, await, ...) and ends as a"
                        + " statement; and the callback form whose result-handler lambda never mentions failed(),"
                        + " succeeded() or cause(). A future that is returned, assigned or passed on is the caller's"
                        + " responsibility and is not reported. Runs on the whole project, so it works without"
                        + " framework entrypoints.",
                "A result handler passed as a method reference or variable is not inspected. A failure handled by a"
                        + " helper the chain hands the future to is not visible. Kotlin coroutine wrappers such as"
                        + " awaitBlocking are not analysed.");

        put(catalog, TransactionalOnInterfaceRule.ID,
                "@Transactional on an interface method instead of the implementation",
                "aop-proxy", Severity.INFO, ALL_LANGUAGES,
                "Spring applications that use @Transactional",
                "A method declared on an interface is annotated with @Transactional. Since Spring 5.0 this is"
                        + " honoured for interface-based and class-based proxies, but the Spring reference"
                        + " recommends annotating the concrete class.",
                "Java annotations are not inherited from interfaces. In AspectJ mode, and before Spring 5.0, an"
                        + " interface-declared @Transactional is silently ignored, so the code appears to work"
                        + " until someone tests a rollback. Declaring it on the implementation works in every mode.",
                "DiagScope detects @Transactional on methods whose declaring type is an"
                        + " interface (resolved from the AST type declaration kind). Interfaces that extend a"
                        + " Spring Data repository are skipped: the container builds their proxy from the"
                        + " interface, so the annotation is honoured.",
                "Confidence is LOW: source alone does not show the proxy mode (@EnableTransactionManagement"
                        + " mode = ASPECTJ) or the Spring version, so on a current proxy-based setup the"
                        + " annotation does work and the finding is only a recommendation.");

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
                        + " Repository/Dao naming pattern) for collection return types. Only methods with no"
                        + " parameters are reported, since a filter argument usually bounds the result."
                        + " Methods with a Pageable parameter, or names containing count/delete/save, are"
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
                "A @Retryable method declares no 'include', 'value' or 'retryFor' attribute. Every thrown "
                        + "exception, including NullPointerException and other programming defects, triggers the retry "
                        + "budget.",
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
                "configuration", Severity.INFO, ALL_LANGUAGES,
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
                "@CrossOrigin(\"*\") lets any website read this endpoint's responses",
                "security", Severity.INFO, ALL_LANGUAGES,
                "Spring MVC REST controllers using @CrossOrigin",
                "A REST endpoint carries @CrossOrigin(\"*\") or @CrossOrigin(origins = \"*\"),"
                        + " permitting any browser origin to make cross-origin requests to"
                        + " this endpoint.",
                "Spring's @CrossOrigin does not allow credentials by default, so with a wildcard origin the "
                        + "browser sends no cookies or HTTP authentication, and the wildcard does not enable cross-site "
                        + "request forgery. What it allows is any web page, on any site, to read this endpoint's responses "
                        + "from a visitor's browser. That is harmless for public data. It is a real exposure when the "
                        + "endpoint is reachable only from the visitor's network position (an intranet or localhost "
                        + "service) or answers based on ambient trust such as an IP allowlist. Spring rejects the "
                        + "combination of a wildcard origin and allowCredentials = true.",
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
                "@Scheduled task starts as soon as the scheduler does",
                "configuration", Severity.INFO, ALL_LANGUAGES,
                "Spring applications using @Scheduled with fixedRate or fixedDelay",
                "A @Scheduled method uses fixedRate or fixedDelay without an initialDelay, so its first run "
                        + "happens as soon as the scheduler starts, right after the application context is refreshed.",
                "Scheduled tasks start once the application context is fully refreshed, so beans are initialised, "
                        + "but readiness probes, caches and downstream services may not be ready yet. A task that depends "
                        + "on them can fail on its first run; the scheduler logs the failure and runs the task again at the "
                        + "next tick, so the usual effect is a noisy first log entry rather than a crash.",
                "The method is annotated with @Scheduled and has fixedRate or fixedDelay in"
                        + " its annotation attributes, but no initialDelay or initialDelayString"
                        + " attribute is set (or it is explicitly 0).",
                "Cron-based schedules (@Scheduled(cron = \"...\")) are not flagged because the"
                        + " first execution time is determined by the cron expression, not startup"
                        + " time. Single-instance startup jobs that are intentionally designed to"
                        + " run at boot should use ApplicationRunner or CommandLineRunner instead.");

        put(catalog, FieldInjectionUsedRule.ID,
                "Field injection instead of constructor injection",
                "spring", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Component, @Service, @Repository, or @Controller",
                "A Spring-managed bean declares one or more fields annotated with @Autowired,"
                        + " @Inject, or @Resource. Field injection bypasses the constructor,"
                        + " injecting dependencies directly into private fields via reflection.",
                "Field-injected beans hide their dependencies from the constructor signature, so they cannot be "
                        + "instantiated in a unit test without a Spring context or reflection. Fields cannot be final. "
                        + "Circular dependencies are allowed to form (Spring Boot 2.6 and later reject them by default; "
                        + "older versions resolve them silently), whereas constructor injection fails fast. The Spring "
                        + "Framework reference recommends constructor injection for mandatory dependencies.",
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
                "readOnly = true lets Spring mark the connection read-only (some drivers and routing DataSources "
                        + "use that to pick a read replica) and, with JPA and Hibernate, sets the session flush mode to "
                        + "MANUAL so no dirty-checking happens at the end. Without it, a query method pays to dirty-check "
                        + "every entity it loaded and can never be routed to a replica.",
                "The method carries @Transactional, its annotation attributes do not include"
                        + " readOnly = true, and its name starts with a recognized query prefix.",
                "False positives occur for methods that read data and then conditionally write"
                        + " — those methods intentionally need a read-write transaction."
                        + " Use readOnly = true only when the method is guaranteed not to"
                        + " perform any write operations.");

        put(catalog, AsyncDefaultExecutorRule.ID,
                "@Async without a named executor relies on a shared default",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Async",
                "An @Async method specifies no executor name, so it runs on the application's default"
                        + " executor. Plain Spring falls back to SimpleAsyncTaskExecutor (a new thread per"
                        + " call); Spring Boot auto-configures a shared ThreadPoolTaskExecutor with an"
                        + " unbounded queue.",
                "Without a named executor the work has no limits of its own. On plain Spring the thread"
                        + " count grows with the call rate until the JVM cannot create threads; on Boot the"
                        + " queue grows instead, and slow async work can delay unrelated async work sharing"
                        + " the pool. Both show up late, as memory or latency growth under load.",
                "The method carries @Async and its annotation attribute map has no 'value'"
                        + " key (the executor bean name).",
                "Confidence is LOW because the default executor is often replaced: an AsyncConfigurer or a"
                        + " TaskExecutor bean (JHipster ships one) is not visible at the call site and does"
                        + " not suppress this finding. If a central async configuration is in place, suppress"
                        + " per-method.");

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
                "performance", Severity.INFO, ALL_LANGUAGES,
                "Spring applications using Spring Cache (@Cacheable, @CacheEvict, @CachePut)",
                "A class declares at least one @Cacheable method but no @CacheEvict or @CachePut method in the "
                        + "same declaring type, so nothing in this class ever invalidates the cache.",
                "A cache that is never invalidated serves stale data until its entries expire by configuration or "
                        + "the application restarts. Whether that matters depends on the CacheManager: the default "
                        + "in-memory cache never evicts, while Caffeine and Redis caches usually carry a TTL or a size "
                        + "bound. Eviction is also often written in the service class that performs the write, which this "
                        + "rule cannot see.",
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

        // ── Wave 5 ────────────────────────────────────────────────────────────
        put(catalog, ScheduledNonVoidReturnRule.ID,
                "@Scheduled method with a non-void return type (the value is ignored)",
                "correctness", Severity.INFO, ALL_LANGUAGES,
                "Spring applications using @Scheduled",
                "A @Scheduled method declares a return type other than void. Spring documents that the returned "
                        + "value is ignored when the method is called by the scheduler. Methods returning a reactive "
                        + "publisher (Spring 6.1 and later) are subscribed to instead and are not reported.",
                "The value is dropped on every execution. Nothing fails, but a reader (or a caller of the same "
                        + "method) that expects the result to go somewhere is misled.",
                "The method carries @Scheduled and its declared return type is anything other"
                        + " than void (Java) or Unit (Kotlin).",
                null);

        put(catalog, FeignClientNoFallbackRule.ID,
                "@FeignClient without fallback",
                "resilience", Severity.INFO, ALL_LANGUAGES,
                "Spring Cloud applications using OpenFeign (@FeignClient)",
                "A @FeignClient interface declares no fallback or fallbackFactory attribute."
                        + " Any failure from the remote service — timeout, 5xx, network partition"
                        + " — propagates as an uncaught exception to the caller.",
                "Without a fallback a downstream failure reaches the caller as a FeignException. That is the "
                        + "default and is often right when the caller handles it. Note that a Feign fallback is only used "
                        + "when Spring Cloud CircuitBreaker is on the classpath and "
                        + "spring.cloud.openfeign.circuitbreaker.enabled=true; declaring one without that setting has no "
                        + "effect.",
                "Methods are grouped by declaring type. Types with @FeignClient in their"
                        + " effective annotation set are checked for fallback or fallbackFactory"
                        + " attributes. One finding is emitted per affected Feign client type.",
                "Fallbacks configured via a global FeignClientConfigurer or a default"
                        + " error decoder are not visible at the annotation level and will"
                        + " not suppress this finding. Suppress per-client if centralised"
                        + " fallback handling is in place.");

        put(catalog, MultipleScheduledNoThreadPoolRule.ID,
                "Multiple @Scheduled tasks share a single thread — one slow task blocks all others",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications with two or more @Scheduled methods",
                "The project declares two or more @Scheduled methods but no TaskScheduler or"
                        + " ThreadPoolTaskScheduler bean. All tasks run sequentially on a single"
                        + " shared thread.",
                "A task that blocks (network call, lock wait, slow query) prevents every"
                        + " other scheduled task from starting until it completes. A task that"
                        + " hangs indefinitely stops all scheduled work for the lifetime of the"
                        + " application. The symptom — 'the cleanup job stopped running' — is"
                        + " typically traced back to an unrelated task that hung hours earlier.",
                "The rule counts methods annotated with @Scheduled across the project. If"
                        + " the count reaches two or more, it checks for any @Bean method whose"
                        + " return type contains TaskScheduler or ScheduledExecutorService."
                        + " If no such bean is found, one WARNING is emitted at the first"
                        + " scheduled method's location.",
                "A TaskScheduler configured programmatically via SchedulingConfigurer and"
                        + " not exposed as a @Bean will not suppress this finding. Spring Boot supplies"
                        + " a scheduler whose pool size is 1 by default, so the finding holds there too,"
                        + " but spring.task.scheduling.pool.size can raise it without any @Bean, and"
                        + " that is not visible in the sources. Suppress manually if a custom scheduler"
                        + " is in place through a non-bean path.");

        put(catalog, ObjectMapperCreatedPerRequestRule.ID,
                "ObjectMapper constructed per-call — should be a shared @Bean",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Java and Kotlin applications using Jackson ObjectMapper",
                "A new ObjectMapper() is constructed inside a regular method body rather than"
                        + " being declared once as a shared Spring @Bean and injected.",
                "A fresh ObjectMapper has empty serializer and deserializer caches, so every call re-introspects "
                        + "the classes it handles (reflection over properties, annotations and constructors). That cost is "
                        + "paid again on each call and is far larger than the constructor itself. ObjectMapper is "
                        + "thread-safe once configured and designed to be shared. Module discovery only happens if "
                        + "findAndRegisterModules() is called; the constructor does not scan the classpath.",
                "The rule walks method.invocations() looking for InvocationEvidence with"
                        + " methodName 'ObjectMapper' (constructor calls are recorded with the"
                        + " class name as method name). Methods annotated with @Bean,"
                        + " @Configuration, or test setup annotations are excluded.",
                "Construction inside @Bean methods is the intentional factory pattern and"
                        + " is always suppressed. Test setup methods (@BeforeEach, @Before)"
                        + " are also suppressed — test ObjectMapper instances are typically"
                        + " configured differently from production ones.");

        // ── Kotlin coroutines: blocking calls inside coroutine builders ───────
        put(catalog, BlockingCallInCoroutineRule.ID,
                "Blocking call on a coroutine dispatcher thread",
                "kotlin-coroutines", Severity.ERROR, Set.of("kotlin"),
                "Kotlin applications using kotlinx.coroutines",
                "A blocking call (Thread.sleep, Object.wait, CountDownLatch.await, Future.get,"
                        + " Mono.block, or blocking I/O such as JDBC, RestTemplate and file access) runs"
                        + " in a suspend function or inside a 'launch', 'async', 'runBlocking', 'produce',"
                        + " 'actor' or 'withContext' lambda, without a blocking-friendly dispatcher.",
                "Blocking a coroutine dispatcher thread makes it unavailable for other coroutines."
                        + " The default dispatcher pool size equals the number of CPU cores;"
                        + " a single blocking call under load starves all other coroutines on that"
                        + " dispatcher, causing latency spikes that are hard to diagnose because"
                        + " thread dumps show legitimate-looking call stacks.",
                "A call is on a coroutine thread when it is in the body of a suspend function or inside a"
                        + " coroutine-builder lambda (enclosure is derived from source line ranges). The"
                        + " innermost builder that names a dispatcher decides: Dispatchers.IO or a custom"
                        + " dispatcher is accepted; Dispatchers.Default, Main and Unconfined are reported with"
                        + " HIGH confidence because they are not blocking pools; an inherited dispatcher is"
                        + " reported with MEDIUM confidence. Receiver types are used to recognise blocking"
                        + " calls, so map.get() or optional.get() are not mistaken for Future.get()."
                        + " Thread-level blocking is ERROR, blocking I/O is WARNING.",
                "Only the body of the suspend function or builder lambda itself is inspected; a blocking"
                        + " call inside a plain helper that it calls is not followed. A suspend function"
                        + " that blocks may legitimately be called under withContext(Dispatchers.IO) by"
                        + " its callers, which is why findings that rely on the suspend modifier alone"
                        + " are MEDIUM confidence. Enclosure uses line ranges, so a blocking call written"
                        + " on the same line as a closing builder lambda is treated as inside it.");

        // ── Wave 6 ────────────────────────────────────────────────────────────
        put(catalog, OptionalOrElseNullRule.ID,
                "Optional.orElse(null) propagates null instead of enforcing presence",
                "null-safety", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application using java.util.Optional",
                "A call to Optional.orElse(null) returns the wrapped value if present, or null if"
                        + " the Optional is empty — silently re-introducing the null the Optional was"
                        + " supposed to eliminate. The caller cannot distinguish a legitimate absent"
                        + " case from a successful result.",
                "Every code path that receives a null from this call site is a latent"
                        + " NullPointerException waiting for the Optional to be empty. The NPE"
                        + " explodes further downstream with no link to the orElse(null) that produced"
                        + " it, making root-cause analysis significantly harder.",
                "A call to orElse() whose single argument is the literal null. Both Java"
                        + " and Kotlin parsers record argument text, so the check is syntactic.",
                "A result assigned to a variable that the method then compares with null is not reported:"
                        + " the absent case is handled. Intentional interop with APIs that expect null (legacy"
                        + " serializers, nullable JSON fields, a nullable return) may still generate false positives. Suppress with diagscope:ignore"
                        + " and an inline comment explaining the nullable contract.");

        put(catalog, AutowiredRequiredFalseUnsafeRule.ID,
                "Optional @Autowired field used without a null check",
                "null-safety", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications that inject fields with @Autowired(required = false)",
                "A field injected with @Autowired(required = false) is used as a method-call receiver in a"
                        + " method that never compares it with null. With required = false Spring leaves the"
                        + " field untouched when no bean matches, so it stays null.",
                "The application starts normally without the optional bean, then fails on the first call"
                        + " with a NullPointerException that points at the call site, not at the missing"
                        + " bean or the profile that did not register it. The failure appears only in the"
                        + " environment that lacks the bean, often production.",
                "The parsers record the fields of a class declared with @Autowired(required = false) and the"
                        + " variables each method compares with null (or reaches through ?. or ?: in Kotlin)."
                        + " The rule reports the first call on such a field in a method with no such check.",
                "Fields typed Optional, ObjectProvider or Provider are skipped, as are Kotlin properties"
                        + " declared nullable, since the compiler already enforces the check. Optional setters"
                        + " and constructor parameters are not analysed. A null check in a caller or a guard"
                        + " such as Objects.requireNonNull is not visible here.");

        put(catalog, MapGetDereferencedWithoutCheckRule.ID,
                "Map.get() result dereferenced without a null guard",
                "null-safety", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin application using java.util.Map or similar map-like structures",
                "The result of Map.get(key) is assigned to a local variable and then used as a"
                        + " method-call receiver without a visible guard for the absent-key case."
                        + " Map.get() returns null when the key is not present.",
                "This pattern is so idiomatic that it barely reads as a bug. Yet any missing key"
                        + " — cold start, expired entry, unexpected input — produces a"
                        + " NullPointerException at the dereference site, far from the get() that"
                        + " returned null. The stack trace points to the symptom, not the cause.",
                "A get() invocation on a map-looking receiver (type or scope name contains"
                        + " map, cache, registry, store, index, or similar) whose result is assigned"
                        + " to a variable, followed by a dereference of that variable without an"
                        + " intervening containsKey, getOrDefault, computeIfAbsent, or"
                        + " requireNonNull guard.",
                "A method that compares the variable with null is not reported. A guard in a caller, or"
                        + " one that goes through a helper method, is not visible, so such code may be"
                        + " falsely flagged.");

        put(catalog, TransactionIsolationDangerousRule.ID,
                "@Transactional(isolation = READ_UNCOMMITTED) enables dirty reads",
                "transactions", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Transactional with explicit isolation levels",
                "A method sets the transaction isolation to READ_UNCOMMITTED, the weakest level."
                        + " The method can observe rows written by concurrent transactions that have"
                        + " not yet committed — including data that will be rolled back.",
                "Decisions can be built on data that was never committed and may be rolled back. The"
                        + " effect depends on the database: PostgreSQL treats READ_UNCOMMITTED as READ_COMMITTED,"
                        + " Oracle does not offer it, while MySQL/InnoDB and SQL Server do allow dirty reads. It is"
                        + " an explicit choice, so the finding asks for a documented reason, not for a rollback."
                        + " READ_COMMITTED prevents dirty reads at minimal overhead.",
                "The method carries @Transactional and the isolation attribute resolves to"
                        + " READ_UNCOMMITTED or its numeric equivalent 1. Detection is deterministic"
                        + " via annotationAttributes.",
                "A small set of bulk reporting queries intentionally use READ_UNCOMMITTED for"
                        + " performance on non-critical reads. Suppress with diagscope:ignore and an"
                        + " inline comment documenting the dirty-read tolerance.");

        put(catalog, RequiresNewInLoopRule.ID,
                "REQUIRES_NEW transaction called inside a loop — one DB transaction per iteration",
                "transactions", Severity.ERROR, ALL_LANGUAGES,
                "Spring applications using @Transactional(propagation = REQUIRES_NEW)",
                "A method declared @Transactional(propagation = REQUIRES_NEW) is called from"
                        + " inside a loop in a calling method visible in the flow. Each iteration"
                        + " suspends the outer transaction, opens a brand-new one, commits it, and"
                        + " resumes the outer transaction.",
                "N iterations produce N independent transactions: N connection acquisitions, N"
                        + " lock acquisitions, N commit round-trips to the database coordinator."
                        + " Under any meaningful batch size this saturates the connection pool."
                        + " Each held connection prevents other requests from acquiring one, causing"
                        + " cascading latency spikes across all endpoints.",
                "For each method in the flow that declares propagation = REQUIRES_NEW, the"
                        + " preceding flow method (the direct caller) is checked for an invocation"
                        + " whose method name matches the REQUIRES_NEW method and whose insideLoop"
                        + " flag is true.",
                "Matching is by method name only — two methods with the same name in different"
                        + " classes may produce a false positive. Confidence is capped at MEDIUM."
                        + " The rule fires only when REQUIRES_NEW is explicitly declared; default"
                        + " or inherited propagation is not flagged.");

        put(catalog, JpaBatchLoopWithoutFlushClearRule.ID,
                "EntityManager.persist() in a loop without flush() + clear()",
                "database", Severity.WARNING, ALL_LANGUAGES,
                "JPA applications using EntityManager directly for batch operations",
                "A method calls EntityManager.persist() or merge() inside a loop without calling"
                        + " flush() and clear() anywhere in the same method. Every persisted entity"
                        + " accumulates in the first-level cache (persistence context) for the entire"
                        + " duration of the loop.",
                "Every persisted entity stays in the persistence context for the whole loop, so memory grows "
                        + "linearly with the number of rows and the final flush has to dirty-check all of them. If the loop "
                        + "also runs queries, each query triggers an automatic flush that re-checks the whole context, "
                        + "which is where the time becomes quadratic. Imports of many thousands of rows show up as GC "
                        + "pressure, slowness or OutOfMemoryError.",
                "A persist or merge invocation with insideLoop = true on an EntityManager-like"
                        + " receiver (scope or type contains entitymanager, em, session, or similar),"
                        + " where neither flush() nor clear() is present anywhere in the method's"
                        + " invocation list.",
                "flush() + clear() called inside a helper method invoked from the loop is not"
                        + " visible at this level and may produce a false positive. Suppress with"
                        + " diagscope:ignore when batching is intentionally delegated to a helper.");

        // Wave 7: code quality, transactions, resource management, performance, caching, scheduling
        put(catalog, ExceptionConstructorWithoutMessageRule.ID,
                "Exception constructed without a message — getMessage() returns null",
                "correctness", Severity.INFO, ALL_LANGUAGES,
                "Spring and general Java/Kotlin applications throwing exceptions without context",
                "A constructor call to a class whose name ends with 'Exception' or 'Error'"
                        + " is detected with an empty argument list. The resulting exception has"
                        + " getMessage() == null and provides no context beyond its class name.",
                "A context-free exception forces every investigator to trace the stack back to"
                        + " the source to understand what went wrong. In a distributed system"
                        + " where a single user request traverses multiple services, a null"
                        + " message in any one of them makes the entire trace unreadable."
                        + " Including the relevant identifier (orderId, userId, resourceId) in"
                        + " the message turns a 20-minute investigation into a 20-second search.",
                "Invocations whose method name matches .*Exception|.*Error with an empty"
                        + " arguments list are flagged. Confidence is capped at LOW because the"
                        + " match is by naming convention only.",
                "A class named XxxException that does not extend Throwable will produce a"
                        + " false positive. The rule does not track exception classes created"
                        + " solely for testing (inside @Test methods).");

        put(catalog, PropagationSupportsWriteRiskRule.ID,
                "@Transactional(SUPPORTS) method with write operations — may commit without a transaction",
                "transactions", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Transactional(propagation = SUPPORTS) on write methods",
                "A method annotated @Transactional(propagation = SUPPORTS) that also invokes"
                        + " write operations on a persistence API (repository, EntityManager,"
                        + " JdbcTemplate). SUPPORTS means the method runs with a transaction"
                        + " if the caller has one, or without one if it does not.",
                "When SUPPORTS is called from a non-transactional context, each write commits"
                        + " immediately in auto-commit mode. A failure mid-method leaves partial"
                        + " writes in the database — no rollback, no atomicity. This creates"
                        + " silent data-corruption bugs that pass integration tests (which run"
                        + " inside a transaction) and surface only in production with concurrent"
                        + " or partially-failed writes.",
                "Methods with @Transactional where the propagation attribute resolves to"
                        + " SUPPORTS or 2, that also contain invocations of write method names"
                        + " (save, persist, delete, etc.) on persistence-like receivers.",
                "Write detection relies on method names; a method named 'save' that is not"
                        + " a persistence write will produce a false positive. Suppress with"
                        + " diagscope:ignore when SUPPORTS is intentional for a read-only method.");

        put(catalog, StreamIoNotClosedRule.ID,
                "I/O-backed stream (Files.list/walk/lines) not wrapped in try-with-resources",
                "resource-management", Severity.WARNING, ALL_LANGUAGES,
                "Java/Kotlin applications that use Files.list(), Files.walk(), Files.lines(), or BufferedReader.lines()",
                "A call to Files.list(), Files.walk(), Files.lines(), or BufferedReader.lines()"
                        + " is detected outside a try-with-resources block. These methods return"
                        + " a Stream backed by an OS file descriptor that must be explicitly closed.",
                "Unlike collection streams, I/O-backed streams wrap an OS file handle. Without"
                        + " closing, the handle remains open until the stream is garbage-collected"
                        + " — which may never happen in a long-running service. The JVM file-handle"
                        + " limit (typically 1024–65536 per process) is exhausted silently over time,"
                        + " eventually causing IOException: Too many open files on unrelated I/O"
                        + " operations, taking the entire service down.",
                "Invocations of list, walk, lines, or find on a receiver whose type or scope"
                        + " contains 'Files' or 'BufferedReader', where the resourceManaged flag"
                        + " is false. Confidence HIGH for Files.*; MEDIUM for other receivers.",
                "The rule does not track a stream variable assigned to a local and later closed"
                        + " manually in a finally block. Such patterns would still be flagged."
                        + " Suppress with diagscope:ignore when manual close is verified.");

        put(catalog, LogMessageStringConcatRule.ID,
                "Logger call uses string concatenation instead of parameterised substitution",
                "performance", Severity.INFO, ALL_LANGUAGES,
                "Spring and general Java/Kotlin applications using SLF4J, Log4j2, or JUL loggers",
                "A call to a logger method (debug, info, warn, error, etc.) is detected where"
                        + " at least one argument contains the string concatenation operator '+'."
                        + " This causes the JVM to build the message string unconditionally,",
                "Logger arguments formed by string concatenation are evaluated whether or not"
                        + " the configured log level is enabled. In production, DEBUG and TRACE"
                        + " are typically disabled, yet the application still pays the CPU and"
                        + " allocation cost for every concatenation on every invocation."
                        + " In hot paths — loops, reactive pipelines, per-request code — this"
                        + " creates significant GC pressure from throwaway String objects."
                        + " Profiler traces surface this as unexpected allocation spikes with"
                        + " no obvious allocation site.",
                "Logger invocations (identified via DiagnosticSignals.isLoggerCall) where any"
                        + " argument string contains ' + '. Arguments that appear to be constant"
                        + " string-only expressions are excluded. Confidence LOW.",
                "The rule detects '+' in raw argument text and will miss cases where a helper"
                        + " method builds the concatenated string before passing it. It will also"
                        + " flag constant expressions like 'PREFIX + SUFFIX' which the compiler"
                        + " folds at compile time. Confidence is LOW to account for these cases.");

        put(catalog, CacheNameMismatchRule.ID,
                "@CacheEvict or @CachePut targets a cache name that @Cacheable never populates",
                "correctness", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using Spring Cache with @Cacheable and @CacheEvict/@CachePut",
                "Within the same declaring type, the cache names declared on @CacheEvict or"
                        + " @CachePut annotations do not intersect with the cache names declared"
                        + " on @Cacheable annotations. The eviction call targets a cache that"
                        + " the class never populates.",
                "A cache-name mismatch makes eviction a no-op. Stale data from the populated"
                        + " cache is served indefinitely after a write, regardless of how many"
                        + " times the eviction method is called. In financial or catalogue services"
                        + " this means users see outdated prices, stock levels, or permissions"
                        + " after a change is committed to the database. The bug is invisible in"
                        + " development (tests call the eviction and re-read, but from the correct"
                        + " cache, so results look fresh) and surfaces only under production data.",
                "Methods in the same declaring type are grouped. Cache names are extracted from"
                        + " the 'value' or 'cacheNames' attributes of @Cacheable, @CacheEvict,"
                        + " and @CachePut. The set intersection is computed; an empty intersection"
                        + " with a non-empty eviction set triggers the finding.",
                "Cache names set via @CacheConfig at the class level are not extracted; a"
                        + " class using @CacheConfig + bare @CachePut may produce a false positive."
                        + " Suppress with diagscope:ignore in that case.");

        put(catalog, ScheduledFixedRateTooAggressiveRule.ID,
                "@Scheduled(fixedRate) below 500 ms with non-trivial method body",
                "resilience", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Scheduled with fixedRate on non-trivial methods",
                "A @Scheduled method has a fixedRate attribute below 500 ms and contains"
                        + " more than one invocation. The very high polling frequency combined"
                        + " with non-trivial work increases the risk of execution overlap and"
                        + " resource exhaustion.",
                "fixedRate fires the task at a fixed wall-clock interval regardless of how"
                        + " long the previous execution took. With a sub-500ms rate, a task"
                        + " that makes a database query, HTTP call, or does any I/O will trigger"
                        + " hundreds of operations per minute per instance — even when there is"
                        + " nothing to process. Under the default single-threaded scheduler,"
                        + " slow executions queue up indefinitely. Under a thread-pool scheduler,"
                        + " concurrent executions contend over shared resources. Either way,"
                        + " the application degrades under production load.",
                "Methods with @Scheduled where fixedRate (as a long literal) is below 500,"
                        + " and the method has at least 2 invocations. fixedRateString is"
                        + " excluded because the value may be a property placeholder.",
                "The invocation-count threshold is a blunt proxy for cost. A 100ms task"
                        + " with 2 trivial in-memory calls is not problematic. Suppress with"
                        + " diagscope:ignore when the high rate and non-trivial body are"
                        + " intentional and the per-tick cost is demonstrably low.");

        // Wave 8
        put(catalog, RegexCompiledInLoopRule.ID,
                "Pattern.compile() called inside a loop body",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring Boot services that validate or transform strings in loops",
                "Pattern.compile() parses the regular expression and builds a new compiled pattern on every call. "
                        + "Inside a loop, the same expression is compiled N times for no reason.",
                "Compiling a regular expression costs far more than matching with an already compiled one, and "
                        + "each compile allocates a fresh pattern that immediately becomes garbage. Pattern is immutable "
                        + "and thread-safe, so a static final field compiled once is shared by every iteration and every "
                        + "thread.",
                "Invocations of Pattern.compile() with insideLoop == true, matched by scope/receiverType"
                        + " hint containing 'pattern'. Confidence HIGH.",
                "Does not detect Pattern.compile() called inside a helper method that is in turn"
                        + " called from the loop body.");

        put(catalog, StringFormatInLoopRule.ID,
                "String.format() or MessageFormat.format() called inside a loop",
                "performance", Severity.INFO, ALL_LANGUAGES,
                "Services processing collections, streams, or batch payloads in loops",
                "String.format() re-parses the format string and allocates varargs arrays on"
                        + " every call. Inside a loop, this happens N times for the same format"
                        + " spec that never changes.",
                "Benchmarks show String.format to be 3–10× slower than equivalent StringBuilder"
                        + " concatenation for simple substitutions. In high-throughput loops"
                        + " (Kafka consumers, batch processors, report generators) the overhead"
                        + " accumulates into measurable latency and GC pressure.",
                "Invocations of format() with insideLoop == true and scope/receiverType hint"
                        + " containing 'string' or 'messageformat'. Confidence HIGH.",
                "Does not inspect whether the format string is constant or dynamic.");

        put(catalog, TransactionalAsyncCombinationRule.ID,
                "@Transactional and @Async both declared on the same method",
                "transactions", Severity.INFO, ALL_LANGUAGES,
                "Spring services using async processing with database writes",
                "A method annotated with both @Transactional and @Async. Spring applies the async advice first, "
                        + "so the method runs on an executor thread and its transaction starts there: it is a separate "
                        + "transaction from the caller's.",
                "The combination works, but the transaction is independent of the caller's. If the caller rolls "
                        + "back after calling the method, the async work stays committed, and the async method may run "
                        + "before the caller's transaction commits, so it can read data the caller has not yet made "
                        + "visible.",
                "Project-wide scan for methods whose annotations() set contains both 'Transactional'"
                        + " and 'Async'. Confidence HIGH.",
                "Meta-annotations (e.g. a custom @AsyncTransactional that carries both)"
                        + " are not detected.");

        put(catalog, ThreadSleepInFlowRule.ID,
                "Thread.sleep() called in the flow of a web or messaging entrypoint",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "REST controllers, Kafka listeners, and scheduled tasks in servlet-model services",
                "Thread.sleep() blocks the calling thread for the full duration. In a"
                        + " servlet-model or platform-thread web server, every request consumes"
                        + " a pooled thread. A sleeping thread holds that slot and prevents it"
                        + " from serving other requests.",
                "Under a spike — slow downstream, misconfigured retry loop, burst of concurrent"
                        + " requests — all available threads can be pinned simultaneously. The"
                        + " result is a complete service outage: new requests queue indefinitely,"
                        + " health checks time out, and the load balancer marks the instance"
                        + " unhealthy. The root cause is often buried in a utility method"
                        + " invisible to on-call engineers.",
                "Invocations of sleep() where scope/receiverType hint contains 'thread', found"
                        + " anywhere in the flow reachable from a REST/Kafka/Scheduled entrypoint."
                        + " Confidence HIGH.",
                "Does not distinguish a sleep in a dedicated background thread (not serving"
                        + " requests) from one in a request-handling path. Use diagscope:ignore"
                        + " for intentional background-thread sleeps.");

        put(catalog, SequentialFutureJoinInLoopRule.ID,
                "CompletableFuture started and joined within the same loop iteration",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Services that fan out concurrent tasks with CompletableFuture",
                "A loop starts an asynchronous task and joins it before the next iteration (for example "
                        + "supplyAsync(...).join()), so the tasks run one after the other.",
                "A future that is started and immediately joined gives no parallelism: the loop takes the sum of "
                        + "the task durations instead of the longest one. With ten remote calls of 100 ms each that is 1000 "
                        + "ms against about 100 ms. Starting all the futures first and joining them in a second loop is "
                        + "fine: the tasks run concurrently and the total is the longest one.",
                "A join() inside a loop whose receiver is an asynchronous call in the same expression "
                        + "(supplyAsync(...).join()), or a variable assigned from an asynchronous call in the same loop a "
                        + "few lines earlier. Confidence MEDIUM.",
                "A future started in one method and joined in another is not seen. A join on a variable that was "
                        + "started earlier, outside the loop, is deliberately not reported: that is the correct two-phase "
                        + "pattern.");

        // ── Wave 9: virtual threads, reactive context, events, Kafka, testability ────

        put(catalog, VirtualThreadWithSynchronizedRule.ID,
                "synchronized method run on a virtual thread (pins the carrier before JDK 24)",
                "concurrency", Severity.INFO, ALL_LANGUAGES,
                "Quarkus or Spring applications using Java 21+ virtual threads (@RunOnVirtualThread)",
                "A method executed on a virtual thread uses the synchronized keyword or @Synchronized."
                        + " On JDK 21 to 23 a virtual thread that blocks inside synchronized stays pinned to its"
                        + " carrier thread.",
                "Up to JDK 23, blocking inside a synchronized block or method pins the carrier thread: when"
                        + " enough virtual threads pin at once, the effective parallelism drops to the carrier"
                        + " pool size (typically the CPU count). JEP 491 removed this in JDK 24: virtual threads"
                        + " now unmount while holding or waiting for a monitor, and only native frames and class"
                        + " initialisation still pin. On JDK 24 and later (including 25) the finding is moot.",
                "The method's effective annotation set contains 'RunOnVirtualThread' or"
                        + " 'StartVirtualThread' AND the synthetic 'Synchronized' signal. Confidence MEDIUM:"
                        + " the JDK version that runs the code is not visible in source.",
                "Carrier pinning can also occur inside synchronized blocks in helper methods"
                        + " called from a virtual-thread context; those are not reported here"
                        + " because the synchronized modifier is on the helper, not the"
                        + " virtual-thread-annotated method.");

        put(catalog, CompletableFutureInReactiveContextRule.ID,
                "CompletableFuture.supplyAsync() without executor in reactive method",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Spring WebFlux, Quarkus Mutiny, or any Project Reactor / Mutiny application",
                "A method with a reactive return type (Mono, Flux, Uni, Multi) or a non-blocking"
                        + " annotation calls CompletableFuture.supplyAsync() or runAsync() without"
                        + " an explicit Executor argument.",
                "Without an explicit executor, supplyAsync() dispatches to ForkJoinPool.commonPool()."
                        + " This pool is shared across all parallel streams in the JVM. A burst of"
                        + " parallel stream work anywhere in the application can starve the reactive"
                        + " async dispatch. Additionally, commonPool threads are platform threads"
                        + " that may block, undermining back-pressure and making the execution"
                        + " context unpredictable for reactive operators that assume non-blocking"
                        + " execution.",
                "Methods whose return type contains a reactive hint (Mono, Flux, Uni, Multi,"
                        + " Publisher) or whose annotations include @NonBlocking, @Incoming,"
                        + " @Outgoing, etc., with a supplyAsync/runAsync call having exactly one"
                        + " argument (no executor). Confidence HIGH for annotation-based contexts,"
                        + " MEDIUM for return-type inference.",
                "supplyAsync() with a custom executor and a chain of downstream operators that"
                        + " themselves switch context (publishOn, subscribeOn) is still reported;"
                        + " suppress with diagscope:ignore if the context switch is intentional.");

        put(catalog, TransactionalEventListenerMissingPhaseRule.ID,
                "@TransactionalEventListener without explicit phase",
                "transactions", Severity.INFO, ALL_LANGUAGES,
                "Spring applications using ApplicationEventPublisher with @TransactionalEventListener",
                "A @TransactionalEventListener method does not declare an explicit phase attribute. Spring "
                        + "defaults to AFTER_COMMIT, where the originating transaction has already completed.",
                "In the AFTER_COMMIT phase the transactional resource is still bound to the thread, but Spring "
                        + "documents that interactions with it are not committed. A listener that writes (repository.save) "
                        + "without @Transactional(propagation = REQUIRES_NEW) therefore loses those writes silently. "
                        + "Leaving the phase implicit hides that contract from the next reader.",
                "Methods carrying @TransactionalEventListener whose annotation attributes contain no 'phase' key. "
                        + "Confidence HIGH.",
                "A phase declared via a meta-annotation or @AliasFor is not resolved without"
                        + " symbol resolution and will not suppress the finding.");

        put(catalog, KafkaConsumerGroupHardcodedRule.ID,
                "Kafka listener consumer group ID is a hardcoded string literal",
                "kafka", Severity.INFO, ALL_LANGUAGES,
                "Spring Kafka applications using @KafkaListener with groupId",
                "A @KafkaListener groupId attribute contains a plain string literal instead of"
                        + " a property placeholder (${kafka.consumer.group-id}).",
                "Consumer group IDs determine partition assignment and offset tracking in the broker. A hardcoded "
                        + "group ID bakes one environment's identity into the artifact: if two environments share a "
                        + "cluster, a staging consumer joins the production consumer group, takes partitions from it and "
                        + "commits production offsets, so messages are skipped. With separate clusters per environment the "
                        + "literal is harmless, which is why this is informational.",
                "Methods carrying @KafkaListener whose annotationAttributes 'groupId' value is"
                        + " present, non-blank, and does not contain a ${...} placeholder."
                        + " Confidence HIGH. Complement to KAFKA_TOPIC_HARDCODED.",
                "Group IDs that are intentionally identical across all environments (single-env"
                        + " deployments) will produce false positives; suppress with diagscope:ignore.");

        put(catalog, ClockDependencyNotInjectedRule.ID,
                "System clock called directly in Spring bean — not injectable in tests",
                "testability", Severity.WARNING, ALL_LANGUAGES,
                "Spring services, components, or repositories with time-sensitive business logic",
                "A Spring-managed bean calls LocalDateTime.now(), Instant.now(), new Date(),"
                        + " ZonedDateTime.now(), or System.currentTimeMillis() directly, without"
                        + " passing an injected Clock.",
                "Code that reads the system clock directly cannot be deterministically tested."
                        + " Time-sensitive business logic — expiry checks, scheduling windows,"
                        + " audit timestamps, SLA calculations — must be tested at specific instants"
                        + " to be reliable. Without an injectable Clock, tests must use Thread.sleep"
                        + " (slow, flaky) or accept tolerances (imprecise). A single bean with a"
                        + " hardcoded clock call propagates the untestability to every caller.",
                "Invocations of now() with no arguments on a date/time type (LocalDateTime,"
                        + " Instant, ZonedDateTime, etc.), new Date() with no arguments, or"
                        + " System.currentTimeMillis(), inside a Spring-managed bean method"
                        + " that is not a @Bean factory or test lifecycle method. Confidence MEDIUM.",
                "now(ZoneId) without a Clock argument is also flagged; passing a Clock is"
                        + " the strictly testable form. Callers that only need the current"
                        + " timestamp for logging (not for business decisions) may be false positives.");

        put(catalog, UnguardedThreadCreationRule.ID,
                "Raw Thread created in Spring bean without managed executor",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Spring applications using @Component, @Service, or any Spring-managed bean",
                "A Spring-managed bean constructs a raw Thread directly (new Thread(runnable))"
                        + " outside a @Bean factory method.",
                "Manually created threads in Spring beans have no pool bounds, no lifecycle"
                        + " management, no exception handler, and no shutdown hook. Spring cannot"
                        + " stop or wait for them when ApplicationContext.close() is called."
                        + " Under load, each call creates a new thread, exhausting the JVM file-"
                        + " descriptor limit (OutOfMemoryError: unable to create native thread)."
                        + " The application cannot shut down cleanly if background threads are"
                        + " still running.",
                "Constructor invocations where methodName is 'Thread' in a method whose"
                        + " declaring type carries a Spring stereotype annotation, outside @Bean"
                        + " factory or test lifecycle methods. Confidence HIGH.",
                "Thread.ofVirtual().start(...) calls are not currently detected; only the"
                        + " new Thread(...) constructor form is inspected.");

        put(catalog, DriverManagerDataSourceUsedRule.ID,
                "DriverManagerDataSource used — no connection pooling",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Spring JDBC or Spring Data applications using DataSource beans",
                "A DriverManagerDataSource is constructed. This Spring helper wraps"
                        + " DriverManager.getConnection() directly, opening a brand-new database"
                        + " connection on every call.",
                "Every getConnection() call performs a full TCP handshake, SSL negotiation,"
                        + " and database authentication round-trip (typically 10–200 ms each)."
                        + " Database servers cap concurrent connections (often to a few hundred)."
                        + " Under any meaningful load, a burst of requests creates a burst of new"
                        + " connections with no queue or rejection policy, exhausting the server"
                        + " connection limit and causing connection-refused errors for unrelated"
                        + " services sharing the same database.",
                "Constructor invocations where methodName is 'DriverManagerDataSource'."
                        + " Severity WARNING outside @Bean factory methods (definitely wrong),"
                        + " INFO inside @Bean methods (may be intentional for local/test profiles)."
                        + " Test configuration annotations suppress the finding entirely.",
                "DriverManagerDataSource is occasionally used intentionally in embedded database"
                        + " test fixtures that do not run under load; those sites are suppressed"
                        + " when @TestConfiguration or @DataJpaTest is present.");

        // Wave 10
        put(catalog, MissingRequestBodyValidationRule.ID,
                "Request body not validated — @RequestBody without @Valid",
                "rest", Severity.WARNING, ALL_LANGUAGES,
                "@RestController or @Controller classes with POST/PUT/PATCH methods",
                "A POST/PUT/PATCH controller method takes a @RequestBody payload object that is annotated"
                        + " with neither @Valid nor @Validated, so the constraints declared on the payload"
                        + " class (@NotNull, @Size, @Pattern) are never evaluated by Spring MVC.",
                "Invalid input — nulls, out-of-range values, malformed strings — passes to the"
                        + " service layer unchecked. Validation failures that should be 400 Bad"
                        + " Request instead surface as NullPointerException,"
                        + " DataIntegrityViolationException, or silent data corruption, typically"
                        + " as a generic 500 with no user-facing feedback.",
                "Controller methods with @PostMapping, @PutMapping or @PatchMapping that have a"
                        + " @RequestBody parameter without @Valid/@Validated, read from the per-parameter"
                        + " annotations the parsers expose. Plain strings, maps, streams and primitives are"
                        + " ignored. A class-level @Validated does not validate body objects, so it does not"
                        + " suppress the finding.",
                "Controllers that validate by hand (an injected Validator) are false positives;"
                        + " suppress with diagscope:ignore.");

        put(catalog, TransactionalOnRestControllerRule.ID,
                "@Transactional on a controller method ties the transaction to the web layer",
                "transactions", Severity.INFO, ALL_LANGUAGES,
                "Spring MVC controllers annotated with @RestController or @Controller",
                "A method on a @RestController or @Controller class is also annotated with"
                        + " @Transactional, placing a transaction boundary on the HTTP entry point"
                        + " rather than the service layer.",
                "A transaction at the controller method spans only that method: it commits when the method "
                        + "returns, before serialization or view rendering. The cost is architectural. The web layer owns "
                        + "the transaction boundary, so the same business operation cannot be reused from a scheduler or a "
                        + "message listener, and any non-database work done inside the controller method (an HTTP call, a "
                        + "slow computation) runs while the transaction and its connection are held.",
                "Methods carrying both @Transactional and @RestController or @Controller in their"
                        + " effective annotation set. Class-level annotations are merged, so a"
                        + " class-level @Transactional on a controller is also detected."
                        + " One finding per controller class, at its first such method; the evidence lists the rest."
                        + " Confidence HIGH.",
                "Intentional use of @Transactional at the controller level for simple CRUD"
                        + " endpoints with no service layer will produce false positives."
                        + " Suppress with diagscope:ignore when this is intentional.");

        put(catalog, ApplicationContextLocatorRule.ID,
                "applicationContext.getBean() in Spring bean — Service Locator anti-pattern",
                "spring", Severity.WARNING, ALL_LANGUAGES,
                "Spring beans (@Component, @Service, @Repository, etc.) that call getBean()",
                "A Spring-managed bean calls applicationContext.getBean(Type.class) or"
                        + " applicationContext.getBean(\"beanName\") to retrieve a dependency at"
                        + " runtime instead of declaring it as a constructor parameter.",
                "The dependency obtained via getBean() is invisible to the framework: Spring"
                        + " cannot detect circular dependencies at startup, the dependency cannot"
                        + " be mocked in unit tests without mocking the entire ApplicationContext,"
                        + " and the class cannot be instantiated outside a Spring context at all."
                        + " Scoped beans (request-scoped, session-scoped) may behave unexpectedly"
                        + " when looked up outside their natural lifecycle.",
                "Methods on Spring stereotype-annotated classes containing an invocation with"
                        + " methodName == 'getBean', outside @Bean factory methods and test"
                        + " lifecycle methods. Confidence HIGH.",
                "Legitimate uses inside @Bean factory methods (producing prototype beans) are"
                        + " suppressed. ObjectProvider<T> injection is the preferred alternative"
                        + " to getBean() for lazily resolved or prototype-scoped dependencies.");

        put(catalog, AsyncVoidReturnRule.ID,
                "@Async method returns void — failures reach only a default error log",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "@Async methods on Spring-managed beans",
                "An @Async method has a void return type. Exceptions thrown inside the method are passed to "
                        + "AsyncUncaughtExceptionHandler, whose default logs each one at ERROR level and discards it. The "
                        + "caller receives no failure signal.",
                "A void @Async method cannot report failure to its caller, and nothing can retry it "
                        + "programmatically. Spring hands the exception to the AsyncUncaughtExceptionHandler; the default "
                        + "(SimpleAsyncUncaughtExceptionHandler) logs every one at ERROR level with the stack trace. The "
                        + "failure is therefore visible in the log, but it never becomes a metric, an alert or a result "
                        + "unless a custom handler is configured.",
                "Methods carrying @Async whose returnType is void, Void, or Unit. Confidence HIGH.",
                "Methods intentionally fire-and-forget with a configured custom"
                        + " AsyncUncaughtExceptionHandler that alerts/records are false positives."
                        + " Suppress with diagscope:ignore when a custom handler is in place.");

        put(catalog, ConfigurationPropertiesNotValidatedRule.ID,
                "@ConfigurationProperties without @Validated — constraint annotations silently ignored",
                "configuration", Severity.WARNING, ALL_LANGUAGES,
                "@ConfigurationProperties classes with field-level constraint annotations",
                "A class annotated with @ConfigurationProperties does not also carry @Validated."
                        + " Bean Validation annotations on its fields (@NotNull, @Min, @Pattern, etc.)"
                        + " are silently ignored at startup.",
                "Invalid configuration — a missing required property, a port number out of range,"
                        + " a malformed URL — only surfaces as a runtime error deep in the application,"
                        + " often under load or on first use of the affected bean, rather than as a"
                        + " startup failure with a clear, actionable error message naming the"
                        + " invalid field.",
                "Configuration property classes (methods carrying ConfigurationProperties in their"
                        + " effective annotation set) where no method in the same declaring type"
                        + " also carries Validated. One finding per type. Confidence HIGH.",
                "Types that validate programmatically via @PostConstruct or a custom Validator bean"
                        + " will be false positives, and so will a class whose @Bean method carries"
                        + " @Validated (Spring Boot triggers validation from either place)."
                        + " @ConstructorBinding-style classes are detected"
                        + " if the constructor method carries the annotation.");

        put(catalog, CircuitBreakerWithoutFallbackRule.ID,
                "@CircuitBreaker without fallbackMethod",
                "resilience", Severity.INFO, ALL_LANGUAGES,
                "Resilience4j @CircuitBreaker annotations",
                "A method annotated with @CircuitBreaker does not specify a fallbackMethod."
                        + " When the circuit opens, Resilience4j throws CallNotPermittedException"
                        + " directly to the caller — no graceful degradation.",
                "Without a fallback the open circuit surfaces CallNotPermittedException to the caller. That is "
                        + "the documented default and is often right, when the caller or a global exception handler turns "
                        + "it into a meaningful response. It becomes a problem when nothing does, and users see a generic "
                        + "500 error.",
                "Methods carrying @CircuitBreaker in their effective annotation set where the"
                        + " annotationAttributes map for CircuitBreaker contains no 'fallbackMethod'"
                        + " key or an empty value. Confidence HIGH.",
                "Teams that handle CallNotPermittedException via a global @ControllerAdvice"
                        + " exception handler will be false positives at the method level."
                        + " Suppress with diagscope:ignore when global handling is in place.");

        put(catalog, BulkheadWithoutFallbackRule.ID,
                "@Bulkhead without fallbackMethod",
                "resilience", Severity.INFO, ALL_LANGUAGES,
                "Resilience4j @Bulkhead annotations",
                "A method annotated with @Bulkhead does not specify a fallbackMethod."
                        + " When all bulkhead slots are occupied, BulkheadFullException"
                        + " propagates to the caller as-is.",
                "Without a fallback a full bulkhead surfaces BulkheadFullException to the caller. That is the "
                        + "documented default and is often right when the caller or a global exception handler turns it "
                        + "into a \"service busy\" response. It becomes a problem when nothing does, and users see a generic "
                        + "500 error.",
                "Methods carrying @Bulkhead in their effective annotation set where the"
                        + " annotationAttributes map for Bulkhead contains no 'fallbackMethod'"
                        + " key or an empty value. Confidence HIGH.",
                "Global exception handlers covering BulkheadFullException are false positives."
                        + " Suppress with diagscope:ignore when global handling is in place.");

        put(catalog, RateLimiterWithoutFallbackRule.ID,
                "@RateLimiter without fallbackMethod",
                "resilience", Severity.INFO, ALL_LANGUAGES,
                "Resilience4j @RateLimiter annotations",
                "A method annotated with @RateLimiter does not specify a fallbackMethod."
                        + " When the rate limit is exceeded, RequestNotPermitted propagates"
                        + " to callers as an unhandled exception.",
                "Without a fallback an exceeded limit surfaces RequestNotPermitted to the caller. That is the "
                        + "documented default and is often right when a global exception handler maps it to 429 Too Many "
                        + "Requests; it becomes a problem when nothing does and clients see a generic 500.",
                "Methods carrying @RateLimiter in their effective annotation set where the"
                        + " annotationAttributes map for RateLimiter contains no 'fallbackMethod'"
                        + " key or an empty value. Severity INFO (less severe than circuit breaker"
                        + " / bulkhead). Confidence HIGH.",
                "Global exception handlers returning 429 are false positives at the method level.");

        put(catalog, ThreadLocalNotRemovedRule.ID,
                "ThreadLocal.set() without ThreadLocal.remove() — per-request data leak in thread pool",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Methods that set a ThreadLocal value in a thread-pool context",
                "A method calls ThreadLocal.set() without a visible ThreadLocal.remove() in the"
                        + " same method body.",
                "In a thread pool (servlet container, Spring async executor, virtual-thread pool),"
                        + " threads are reused across requests. Without remove(), the stored value"
                        + " survives into the next request on the same thread — user A's data may"
                        + " be readable by user B's request. The failure is intermittent,"
                        + " non-reproducible under low concurrency, and catastrophic in production.",
                "Methods containing a 'set' invocation on a ThreadLocal-looking receiver (scope"
                        + " or receiverType contains 'threadlocal') with no 'remove' invocation on"
                        + " any ThreadLocal receiver in the same method. Confidence MEDIUM — the"
                        + " receiver heuristic may miss renamed variables.",
                "A remove() in a different method (e.g., a filter's doFilter that calls set() in"
                        + " pre-processing and remove() in post-processing as separate calls) will"
                        + " be a false positive. Suppress with diagscope:ignore at those call sites.");

        put(catalog, RunBlockingInSuspendRule.ID,
                "runBlocking inside suspend function — blocks dispatcher thread, risk of deadlock",
                "kotlin", Severity.ERROR, Set.of("kotlin"),
                "Kotlin suspend functions",
                "A Kotlin suspend function calls runBlocking { }.",
                "runBlocking creates a new event loop and blocks the current thread until the"
                        + " coroutine body completes. Inside a suspend function, the current thread"
                        + " is a dispatcher thread. Blocking it starves other coroutines on that"
                        + " dispatcher and defeats structured concurrency. On single-threaded"
                        + " dispatchers (Dispatchers.Main, test dispatchers), this causes a"
                        + " deadlock: the new event loop waits for the outer one to yield — and it"
                        + " never does.",
                "Suspend functions with the synthetic SUSPEND annotation containing a 'runBlocking'"
                        + " invocation in their body. Confidence HIGH.",
                "runBlocking used at the top-level entry point (e.g., main function) to bridge"
                        + " into coroutines is not flagged because entry-point functions are not"
                        + " suspend. Only suspend functions are inspected.");

        put(catalog, EntityManagerClearInTransactionRule.ID,
                "EntityManager.clear() inside @Transactional — detaches entities, writes silently lost",
                "database", Severity.WARNING, ALL_LANGUAGES,
                "@Transactional methods that access the EntityManager",
                "A @Transactional method calls EntityManager.clear() outside a loop, detaching"
                        + " all managed entities mid-transaction.",
                "After clear(), any entity reference obtained earlier is detached. Calling a"
                        + " setter on it, navigating a Hibernate proxy, or triggering a cascaded"
                        + " write silently has no effect — Hibernate's dirty-checking no longer"
                        + " tracks it. The transaction commits without error and the data is simply"
                        + " not persisted. This is one of the hardest JPA bugs to diagnose.",
                "Methods carrying @Transactional containing a 'clear' invocation on an"
                        + " EntityManager receiver (scope contains 'em' or 'entitymanager',"
                        + " or receiverType contains 'EntityManager'), where insideLoop is false."
                        + " Confidence MEDIUM.",
                "The intentional batch pattern (persist → flush → clear → repeat inside a loop)"
                        + " is suppressed because insideLoop is true. False positives may occur if"
                        + " a non-EntityManager object named 'em' has a clear() method.");

        put(catalog, CacheableOnPrivateMethodRule.ID,
                "@Cacheable/@CacheEvict/@CachePut on non-interceptable method — cache never activated",
                "aop", Severity.ERROR, ALL_LANGUAGES,
                "Spring beans with @Cacheable, @CacheEvict, or @CachePut annotations",
                "A Spring caching annotation (@Cacheable, @CacheEvict, @CachePut) is placed on"
                        + " a method that the Spring CGLIB proxy cannot intercept — private, static,"
                        + " or final.",
                "The annotation is silently ignored. Every call reaches the real method; the cache"
                        + " is never populated, cache evictions never fire, and no error or warning"
                        + " is raised at startup or runtime. The failure only surfaces in profiling"
                        + " or when developers notice 'why is the cache always empty?'",
                "Methods carrying Cacheable, CacheEvict, CachePut, or Caching where"
                        + " proxy().interceptable() returns false. Confidence HIGH.",
                "AspectJ compile-time or load-time weaving can advise private/final/static"
                        + " methods; those configurations are false positives. Suppress with"
                        + " diagscope:ignore when AspectJ weaving is configured.");

        put(catalog, EventListenerOnPrivateMethodRule.ID,
                "Private @EventListener on a proxied bean fails the application at start-up",
                "aop", Severity.ERROR, ALL_LANGUAGES,
                "Spring beans with @EventListener or @TransactionalEventListener",
                "A private, non-static @EventListener or @TransactionalEventListener method sits on a bean that"
                        + " Spring proxies (it carries @Transactional, @Async, @Cacheable or matching aspect advice).",
                "Spring calls the listener reflectively on the target bean. On a proxied bean it cannot delegate a"
                        + " private method from the proxy and fails the context with an IllegalStateException"
                        + " (\"Switch its visibility to package or protected\"). The application does not start"
                        + " until the visibility is widened; on a bean without a proxy a private listener works.",
                "Methods carrying @EventListener or @TransactionalEventListener that are private and non-static,"
                        + " where the method or its class carries a proxy-dependent annotation or matching advice."
                        + " Confidence MEDIUM.",
                "A bean proxied for a reason the scan cannot see (configuration, another module) is not reported."
                        + " Final and static listener methods are not covered. The failure is loud at start-up,"
                        + " so it is caught the first time the application runs.");

        put(catalog, TimedOnPrivateMethodRule.ID,
                "@Timed/@Counted/@Observed on non-interceptable method — metrics never recorded",
                "observability", Severity.WARNING, ALL_LANGUAGES,
                "Spring beans with Micrometer instrumentation annotations",
                "A Micrometer instrumentation annotation (@Timed, @Counted, @Observed) is"
                        + " placed on a method that the Spring AOP proxy cannot intercept —"
                        + " private, static, or final.",
                "No timing, count, or span is recorded for this method. Grafana dashboards show"
                        + " zero for the operation, alerting thresholds never fire, and the SLO"
                        + " measurement is silently incomplete. Developers often notice only during"
                        + " an incident review that the metric was never recorded.",
                "Methods carrying @Timed, @Counted, @Observed, or @MeterTag where"
                        + " proxy().interceptable() returns false. Confidence HIGH.",
                "The Micrometer programmatic API (MeterRegistry.timer(...).record(() -> ...)) is"
                        + " a proxy-free alternative that does not require interception; methods"
                        + " using it alongside the annotation for IDE navigation are false positives."
                        + " Suppress with diagscope:ignore.");

        put(catalog, NativeQueryStringConcatRule.ID,
                "Native query built with string concatenation — SQL injection risk",
                "security", Severity.ERROR, ALL_LANGUAGES,
                "JPA/JDBC query-creation methods receiving concatenated SQL strings",
                "A native SQL or JPQL query is constructed with string concatenation (+ operator)"
                        + " passed to createNativeQuery(), createQuery(), or a JdbcTemplate method.",
                "A query built by string concatenation is structurally vulnerable to SQL injection."
                        + " An attacker who controls any concatenated fragment can alter the query"
                        + " structure: extract data from other tables, bypass authentication checks,"
                        + " or modify or delete rows. Native queries bypass the ORM's parameterization"
                        + " safety because the final SQL string is sent directly to the JDBC driver.",
                "Invocations of createNativeQuery, createQuery, createNamedQuery, query,"
                        + " batchUpdate, queryForObject, queryForList, or queryForMap where the"
                        + " first argument text representation contains ' + '. Confidence MEDIUM —"
                        + " the argument text may not always be fully resolved in the AST snapshot.",
                "Concatenation of compile-time constants (only string literals, no runtime values)"
                        + " is safe but will still be flagged. Suppress with diagscope:ignore for"
                        + " verified constant-only concatenation.");

        // Wave 11
        put(catalog, BCryptCreatedPerCallRule.ID,
                "BCryptPasswordEncoder created on every call instead of shared",
                "security", Severity.WARNING, ALL_LANGUAGES,
                "Spring Security applications that hash passwords",
                "A BCryptPasswordEncoder is constructed inside an ordinary method rather than"
                        + " declared once as a bean.",
                "The encoder is stateless and thread-safe. Building it per call scatters the"
                        + " work-factor setting across call sites, so raising the strength misses some of them,"
                        + " and it prevents replacing the encoder in tests. The hashing cost itself is paid in"
                        + " encode() and matches(), not in the constructor.",
                "A constructor call to BCryptPasswordEncoder in a method that is not a @Bean,"
                        + " @PostConstruct or test method.",
                "Only BCryptPasswordEncoder is recognised; other encoders (Argon2, SCrypt, Pbkdf2)"
                        + " are not. A constructor invoked in a field initializer is not a method and is not"
                        + " seen.");

        put(catalog, SimpleDateFormatPerCallRule.ID,
                "SimpleDateFormat created on every call",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin code that formats or parses dates with java.text.SimpleDateFormat",
                "A SimpleDateFormat is constructed inside an ordinary method.",
                "Each construction re-parses the pattern. The common workaround of sharing one"
                        + " instance is unsafe because SimpleDateFormat is not thread-safe and returns corrupted"
                        + " dates under concurrency. DateTimeFormatter is immutable, thread-safe and can be a"
                        + " constant.",
                "A constructor call to SimpleDateFormat in a method that is not a @Bean,"
                        + " @PostConstruct or test method. Confidence is HIGH inside a loop, MEDIUM otherwise.",
                "A SimpleDateFormat kept in a static or instance field is not flagged by this rule,"
                        + " although sharing it is a thread-safety bug in its own right.");

        put(catalog, RandomPerCallRule.ID,
                "Random or SecureRandom created on every call",
                "performance", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin code that draws random numbers",
                "A java.util.Random (with no seed) or a SecureRandom is constructed inside an"
                        + " ordinary method.",
                "SecureRandom seeds itself from the operating system entropy source, which is slow"
                        + " and can block on some hosts, and creating one per call repeats that cost. Both"
                        + " classes are thread-safe, so one shared instance is correct, and a per-call generator"
                        + " cannot be replaced by a seeded one in tests.",
                "A constructor call to SecureRandom, or to Random with no arguments, outside @Bean,"
                        + " @PostConstruct and test methods. Confidence is HIGH for SecureRandom and MEDIUM for"
                        + " Random.",
                "new Random(seed) is treated as deliberate and is not reported."
                        + " SecureRandom.getInstance(...) factory calls are not detected.");

        put(catalog, DeleteAllWithoutIdRule.ID,
                "repository.deleteAll() loads every entity before deleting",
                "database", Severity.WARNING, ALL_LANGUAGES,
                "Spring Data JPA repositories",
                "A repository's no-argument deleteAll() is called.",
                "Spring Data JPA implements deleteAll() by loading every entity and deleting them one"
                        + " at a time. It grows with the table, so a large one means an OutOfMemoryError or a"
                        + " very long transaction, and one DELETE statement per row.",
                "An invocation of deleteAll() with no arguments on a receiver typed as *Repository or"
                        + " *Dao, or named like one. Reactive repositories are skipped. Confidence is HIGH when"
                        + " the receiver type is known, MEDIUM from the name.",
                "Repositories whose deleteAll() is overridden to run a bulk statement, and non-JPA"
                        + " stores where deleteAll() is already a single command, will be false positives."
                        + " Suppress with diagscope:ignore.");

        put(catalog, CacheableOnVoidMethodRule.ID,
                "@Cacheable on a method that returns nothing",
                "correctness", Severity.WARNING, ALL_LANGUAGES,
                "Spring Cache applications",
                "A method annotated with @Cacheable declares a void or Unit return type.",
                "There is no value to cache. Spring stores the null result under the key, so with"
                        + " default settings a later call with the same arguments is a cache hit and the method"
                        + " body is skipped entirely, which silently drops the side effect the method exists"
                        + " for.",
                "The Cacheable annotation on a method whose declared return type is exactly void or"
                        + " Unit.",
                "A Kotlin function with an inferred return type has no declared type in the syntax"
                        + " and is not reported.");

        put(catalog, SystemEnvInBeanRule.ID,
                "System.getenv/getProperty read directly inside a Spring bean",
                "testability", Severity.WARNING, ALL_LANGUAGES,
                "Spring beans (@Component, @Service, @Repository, @Controller, @Configuration)",
                "A Spring-managed class calls System.getenv(...) or System.getProperty(...).",
                "Reading the process environment ties the bean to global state that a unit test"
                        + " cannot replace without mutating the JVM, and it bypasses Spring property sources"
                        + " (profiles, config files, secret stores). @Value, @ConfigurationProperties and"
                        + " Environment are injectable and overridable.",
                "An invocation of getenv or getProperty on System inside a method of a class with a"
                        + " Spring stereotype. Reads of JVM-constant properties such as line.separator,"
                        + " java.io.tmpdir or user.dir are ignored.",
                "Only classes that carry a Spring stereotype are checked; beans declared through"
                        + " @Bean methods in a class without one are reached only when that class is a"
                        + " @Configuration.");

        put(catalog, SensitiveHeaderLoggedRule.ID,
                "Credential header value written to the log",
                "security", Severity.ERROR, ALL_LANGUAGES,
                "Servlet, WebFlux and JAX-RS applications that log requests",
                "A logger call has an argument that reads the Authorization, Cookie, Set-Cookie,"
                        + " X-API-Key, X-Auth-Token or similar header.",
                "Bearer tokens, session ids and API keys end up in log files, backups and third-party"
                        + " aggregators with a much wider audience and longer retention than the credential was"
                        + " meant to have.",
                "A logger invocation whose argument text contains a header read such as"
                        + " getHeader(\"Authorization\"), headers.get(\"Cookie\") or headers[\"X-API-Key\"]. A"
                        + " message that only mentions the header name is not reported.",
                "Logging a whole header map or request object is not detected, and neither is a"
                        + " header name held in a variable.");

        put(catalog, OpenRedirectRiskRule.ID,
                "Redirect to a non-constant location in a REST handler",
                "security", Severity.ERROR, ALL_LANGUAGES,
                "Spring MVC and servlet REST handlers",
                "A handler method calls response.sendRedirect(...) or builds a RedirectView with an"
                        + " argument that is not a literal or a constant.",
                "If any part of the target comes from the request, an attacker can craft a link on"
                        + " your own domain that sends the victim to a phishing site, and the user trusts it"
                        + " because it starts with your host.",
                "A sendRedirect call or RedirectView constructor with a non-constant first argument,"
                        + " in a method with a REST mapping annotation, where the method makes no visible"
                        + " validation call (startsWith, matches, contains, isAllowed, isValid, isSafe)."
                        + " Confidence MEDIUM.",
                "The rule cannot see which parameter feeds the argument, and validation performed in"
                        + " another method is not seen. Redirects returned as a 'redirect:' view name are not"
                        + " detected.");

        put(catalog, PathTraversalRiskRule.ID,
                "File path built from a non-constant argument in a REST handler",
                "security", Severity.ERROR, ALL_LANGUAGES,
                "Spring MVC and JAX-RS handlers that read or write files",
                "A REST handler builds a File, Paths.get or Path.of path from an argument that is not"
                        + " a literal or a constant.",
                "If the argument comes from the request, '../' sequences escape the intended"
                        + " directory, letting a caller read or overwrite arbitrary files the process can reach.",
                "A new File(...), Paths.get(...) or Path.of(...) call with a non-constant argument in"
                        + " a method with a REST mapping annotation, where the method calls none of normalize,"
                        + " toRealPath, getCanonicalPath, startsWith, getFileName or FilenameUtils.getName."
                        + " Confidence MEDIUM.",
                "The rule cannot see which parameter feeds the argument, so configuration-derived"
                        + " paths are flagged too. Sanitisation done in another method is not seen.");

        put(catalog, TimerStartedNotStoppedRule.ID,
                "Micrometer timer sample started but never stopped",
                "observability", Severity.INFO, ALL_LANGUAGES,
                "Micrometer instrumented code",
                "Timer.start(...) (or LongTaskTimer.start) creates a sample that the method never"
                        + " stops.",
                "A sample only records a duration when stop() runs. A dropped sample records nothing,"
                        + " so the metric silently under-reports exactly the executions that mattered, and for a"
                        + " LongTaskTimer the active-task gauge stays incremented.",
                "A start call on Timer or LongTaskTimer whose result is assigned to a variable that"
                        + " has no stop call in the same method, or whose result is discarded. A sample that is"
                        + " returned or passed on is not reported.",
                "A sample stored in a field and stopped by another method is reported, as is one"
                        + " stopped by a helper that receives it through a wrapper object.");

        put(catalog, ObjectInputStreamRiskRule.ID,
                "ObjectInputStream without an ObjectInputFilter",
                "security", Severity.ERROR, ALL_LANGUAGES,
                "Any code that reads Java-serialized data",
                "An ObjectInputStream is constructed and no ObjectInputFilter is set in the same"
                        + " method.",
                "Java deserialization instantiates whatever class the byte stream names and runs its"
                        + " readObject logic. With a gadget class on the classpath, untrusted input becomes"
                        + " remote code execution.",
                "A constructor call to ObjectInputStream in a method that calls none of"
                        + " setObjectInputFilter, createFilter or setSerialFilter.",
                "A JVM-wide filter (-Djdk.serialFilter or ObjectInputFilter.Config.setSerialFilter at"
                        + " startup) or one installed by a helper is not visible, so those programs are false"
                        + " positives.");

        put(catalog, XmlExternalEntityRiskRule.ID,
                "XML parser factory without external entity protection (XXE)",
                "security", Severity.ERROR, ALL_LANGUAGES,
                "Code that parses XML with JAXP (DocumentBuilderFactory, SAXParserFactory,"
                        + " XMLInputFactory)",
                "An XML parser factory is created and the method does not disable DTDs or external"
                        + " entities.",
                "These factories resolve DTDs and external entities by default. A crafted document"
                        + " can read local files or reach internal services (XXE). FEATURE_SECURE_PROCESSING"
                        + " alone limits entity expansion but still resolves external entities.",
                "A newInstance/newFactory call on one of those factories with no setFeature,"
                        + " setAttribute or setProperty call in the same method that mentions"
                        + " disallow-doctype-decl, external-general-entities, external-parameter-entities,"
                        + " ACCESS_EXTERNAL_DTD, ACCESS_EXTERNAL_SCHEMA, SUPPORT_DTD or"
                        + " IS_SUPPORTING_EXTERNAL_ENTITIES. Confidence is MEDIUM when only"
                        + " FEATURE_SECURE_PROCESSING is set.",
                "A factory hardened in another method (a shared builder or utility) is reported."
                        + " TransformerFactory and SchemaFactory are not covered.");

        put(catalog, TransactionalNoRollbackForExceptionRule.ID,
                "@Transactional that refuses rollback for Exception or Throwable",
                "transactions", Severity.ERROR, ALL_LANGUAGES,
                "Spring and Jakarta transactional services",
                "@Transactional names Exception, Throwable or RuntimeException in noRollbackFor (or"
                        + " dontRollbackOn).",
                "noRollbackFor lists exceptions that must not roll the transaction back. Naming one"
                        + " of these covers practically every failure, so a database error or a bug midway"
                        + " through still commits the writes made so far, leaving partial data that no log line"
                        + " explains.",
                "The noRollbackFor, noRollbackForClassName or dontRollbackOn attribute of a"
                        + " Transactional annotation containing Exception, Throwable or RuntimeException."
                        + " Reported once per declaring type and value when the annotation is on the class.",
                "Names that are only visible through a constant or a custom exception base class are"
                        + " not resolved.");

        put(catalog, SemaphoreReleaseNotInFinallyRule.ID,
                "Semaphore permit not released in a finally block",
                "concurrency", Severity.ERROR, ALL_LANGUAGES,
                "Code that limits concurrency with java.util.concurrent.Semaphore",
                "A method acquires a Semaphore permit but does not release it in a finally block.",
                "If anything between acquire() and release() throws, the permit is leaked. A"
                        + " semaphore with N permits tolerates N such failures and then every caller blocks"
                        + " forever, a starvation that looks like a hang and leaves no error behind.",
                "An acquire or acquireUninterruptibly call on a Semaphore (by type, or by a"
                        + " 'semaphore' name hint) with no release call inside a finally block of the same"
                        + " method. Confidence is HIGH when the method releases outside a finally, MEDIUM when"
                        + " it never releases.",
                "A permit released by a different method (a custom lock class) is reported with"
                        + " MEDIUM confidence. tryAcquire is not covered.");

        put(catalog, CountDownLatchNotInFinallyRule.ID,
                "CountDownLatch.countDown() not in a finally block",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Code that coordinates threads with java.util.concurrent.CountDownLatch",
                "countDown() is called outside a finally block in a method where other work runs"
                        + " before it.",
                "If the work before countDown() throws, the latch never reaches zero and every thread"
                        + " blocked in await() stays blocked for good.",
                "A countDown call on a CountDownLatch (by type or a 'latch' name hint) with no"
                        + " countDown inside a finally block of the same method and at least one non-logging"
                        + " call earlier in the method.",
                "A callback that only counts down cannot fail first and is not reported. Work that"
                        + " happens in a different method than the countDown is not seen.");

        put(catalog, VirtualThreadWithThreadLocalRule.ID,
                "ThreadLocal.set() in a method running on a virtual thread",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Quarkus and Java 21+ code using @RunOnVirtualThread",
                "A method annotated with @RunOnVirtualThread stores a value in a ThreadLocal.",
                "Virtual threads are created per task and can number in the millions. Each keeps its"
                        + " own copy of every ThreadLocal it touches, turning per-thread storage into"
                        + " per-request heap for the garbage collector to scan. ScopedValue or explicit"
                        + " parameters carry request context without a per-thread map.",
                "A set call on a ThreadLocal receiver (by type, or by a 'threadlocal' name hint)"
                        + " inside a method with RunOnVirtualThread in its effective annotations.",
                "Only the annotated method itself is inspected; a helper it calls that sets a"
                        + " ThreadLocal is not followed. Virtual threads started with Thread.ofVirtual() are not"
                        + " recognised.");

        put(catalog, ReflectiveAccessInProductionRule.ID,
                "setAccessible(true) in production code",
                "maintainability", Severity.WARNING, ALL_LANGUAGES,
                "Any Java or Kotlin production code",
                "Production code calls setAccessible(true) on a reflected member.",
                "It bypasses encapsulation and ties the code to another library's internals, which then break on "
                        + "upgrade. On Java 17 and later it also fails with InaccessibleObjectException for members of "
                        + "JDK-internal packages unless the package is opened with --add-opens; application classes are not "
                        + "affected.",
                "An invocation of setAccessible with the literal argument true outside test lifecycle"
                        + " methods.",
                "Kotlin's isAccessible = true is a property assignment and is not seen. Code that is"
                        + " intentionally reflective (a serializer, an injector) will be reported.");

        put(catalog, EagerCollectionFetchRule.ID,
                "JPA collection fetched eagerly",
                "database", Severity.WARNING, ALL_LANGUAGES,
                "JPA / Hibernate entities",
                "An entity declares @OneToMany or @ManyToMany with fetch = FetchType.EAGER.",
                "The collection is loaded with every parent: one extra query per parent (N+1) or,"
                        + " when joined, a cartesian product that multiplies rows. It cannot be switched off per"
                        + " query, so every code path that loads the entity pays for it, including those that"
                        + " never read the collection.",
                "The parsers detect a field annotated with OneToMany or ManyToMany whose fetch"
                        + " attribute contains EAGER and mark the class. The finding is reported once per"
                        + " entity, on its first method, and lists the fields.",
                "An entity with no methods (a bare Kotlin data class) is not seen. The finding is"
                        + " located at the first method of the class rather than at the field.");

        put(catalog, LazyBeanCircularIndicatorRule.ID,
                "@Lazy constructor parameter masking a circular dependency",
                "spring", Severity.INFO, ALL_LANGUAGES,
                "Spring beans with constructor injection",
                "A Spring bean's constructor has a parameter annotated with @Lazy.",
                "@Lazy injects a proxy that resolves the real bean on first use. The usual reason is"
                        + " a circular dependency: the cycle is hidden rather than removed, and it returns as a"
                        + " runtime failure or an unexpected proxy. It is also used on purpose to defer an"
                        + " expensive bean, so this is informational.",
                "The parsers detect @Lazy on a constructor parameter and mark the class; the rule"
                        + " reports Spring-stereotyped classes with that marker, once per bean.",
                "@Lazy on a field or a setter is not covered. The finding is located at the first"
                        + " method of the class rather than at the constructor.");

        put(catalog, CacheEvictWithoutAllEntriesOnVoidRule.ID,
                "@CacheEvict without allEntries on a method with no parameters",
                "correctness", Severity.INFO, ALL_LANGUAGES,
                "Spring Cache applications",
                "A parameterless method annotated with @CacheEvict sets neither allEntries = true nor"
                        + " an explicit key.",
                "With no arguments Spring derives an empty key, so the call evicts one entry that was"
                        + " almost certainly never written, and the rest of the cache stays stale.",
                "A CacheEvict annotation on a method with no parameters, a void/Unit (or undeclared)"
                        + " return type, and neither an allEntries = true nor a key/keyGenerator attribute.",
                "A deliberate eviction of the single empty-key entry (a cached no-argument lookup) is"
                        + " reported; suppress with diagscope:ignore.");

        put(catalog, MissingBulkheadOnHttpClientRule.ID,
                "Outbound HTTP call with no bulkhead or circuit breaker on its path",
                "resilience", Severity.INFO, ALL_LANGUAGES,
                "Services calling downstream HTTP APIs with RestTemplate, WebClient or RestClient",
                "A method makes an outbound HTTP call and no method on the path from the entrypoint"
                        + " carries @Bulkhead or @CircuitBreaker.",
                "Without isolation one slow downstream holds a request thread per in-flight call"
                        + " until the pool is exhausted, and every endpoint of the service stops answering,"
                        + " including unrelated ones.",
                "A call on a RestTemplate, WebClient or RestClient receiver, evaluated along the"
                        + " flow: every method from the entrypoint to the call is checked for a Bulkhead or"
                        + " CircuitBreaker annotation. Confidence is capped at MEDIUM and by the path.",
                "Isolation applied outside the application (gateway, service mesh, HTTP client pool"
                        + " limits) is invisible. Feign clients and java.net.http.HttpClient are not covered.");

        put(catalog, HttpClientNoErrorHandlerRule.ID,
                "RestTemplate created without an error handler",
                "resilience", Severity.INFO, ALL_LANGUAGES,
                "Spring applications that create RestTemplate instances",
                "A RestTemplate is constructed in a method that never sets an error handler.",
                "The default handler turns every 4xx and 5xx into an exception, so an expected 404"
                        + " becomes a failure, and unhandled ones surface as generic 500s with the downstream"
                        + " response body lost.",
                "A constructor call to RestTemplate in a method with no setErrorHandler or"
                        + " errorHandler call.",
                "Only a RestTemplate constructed in the method is seen; an injected one is not."
                        + " Accepting the default behaviour deliberately is reasonable, in which case suppress"
                        + " with diagscope:ignore.");

        put(catalog, KafkaListenerManualAckWrongModeRule.ID,
                "Acknowledgment parameter without a manual-ack container factory",
                "kafka", Severity.WARNING, ALL_LANGUAGES,
                "Spring Kafka listeners",
                "A @KafkaListener method declares an Acknowledgment parameter but names no"
                        + " containerFactory.",
                "acknowledge() only exists when the container runs in a manual ack mode. In any other mode Spring "
                        + "passes null for the Acknowledgment parameter, so the first call to acknowledge() throws "
                        + "NullPointerException and the record fails through the error handler.",
                "A KafkaListener or KafkaHandler method with a parameter of type Acknowledgment and"
                        + " no containerFactory attribute. Confidence is LOW because the mode may be set"
                        + " elsewhere.",
                "spring.kafka.listener.ack-mode and the default container factory are not visible in"
                        + " source, so many correct listeners will be reported. Treat it as a prompt to verify"
                        + " the ack mode.");

        put(catalog, CancellationExceptionSwallowedRule.ID,
                "Coroutine cancellation swallowed by a broad catch",
                "kotlin-coroutines", Severity.WARNING, Set.of("kotlin"),
                "Kotlin applications using kotlinx.coroutines",
                "A suspend function catches Exception, Throwable, RuntimeException,"
                        + " IllegalStateException or CancellationException without rethrowing, or uses"
                        + " runCatching.",
                "Cancellation is delivered as a CancellationException thrown from a suspension point. Swallowing "
                        + "it hides the cancellation from the code after the catch: it carries on as if the call had failed "
                        + "normally, running cleanup or compensation logic for work that was abandoned. The next suspension "
                        + "point throws again, but a loop that catches and retries can spin, and a timeout or an abandoned "
                        + "request no longer stops the work promptly.",
                "In a function carrying the suspend modifier, a catch clause of one of those types"
                        + " whose body does not throw, or a runCatching call. The function is accepted when it"
                        + " also catches CancellationException and rethrows it, or calls ensureActive()."
                        + " Confidence is HIGH when CancellationException itself is caught and MEDIUM for the"
                        + " broad types.",
                "The rule cannot see whether the try body contains a suspension point; a catch around"
                        + " non-suspending code is reported although harmless. Only the suspend function's own"
                        + " body is inspected.");

        put(catalog, ReactiveResultDiscardedRule.ID,
                "Reactive pipeline created and discarded, so it never runs",
                "reactive", Severity.ERROR, ALL_LANGUAGES,
                "Project Reactor, Mutiny and RxJava code",
                "A call that returns a Mono, Flux, Uni, Multi or RxJava type is used as a statement"
                        + " and its result is dropped.",
                "Reactive types are lazy: the call only describes the work, and nothing is sent,"
                        + " saved or called until something subscribes. The statement has no effect, with no"
                        + " error and no log line, which is among the most common reactive bugs.",
                "An invocation whose result is ignored and whose callee is a project method declared"
                        + " to return a reactive type, or whose receiver is a typed Mono/Flux/Uni/Multi and the"
                        + " call is not a terminal operator (subscribe, block, await), or whose receiver is a"
                        + " Reactive* client. HIGH confidence for the first two, MEDIUM for the last. The last"
                        + " expression of a Kotlin lambda is not a discarded statement and is skipped.",
                "Callees outside the project are recognised only through a typed receiver, so a"
                        + " discarded call on an untyped variable or the tail of a long call chain is missed."
                        + " Fire-and-forget pipelines that are subscribed elsewhere are not distinguished.");

        put(catalog, BlockingCallOnCommonPoolRule.ID,
                "Blocking call on the shared ForkJoinPool common pool",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Code using parallel streams or CompletableFuture async stages without an executor",
                "A blocking call (sleep, Future.get, JDBC, HTTP, file I/O) runs inside a"
                        + " parallelStream() pipeline or inside supplyAsync/runAsync/thenXxxAsync called without"
                        + " an Executor.",
                "Both use ForkJoinPool.commonPool(), which has about one thread per CPU core and is"
                        + " shared by the whole JVM. A few blocked tasks starve every other parallel stream and"
                        + " async stage, including those in unrelated libraries, which looks like a service-wide"
                        + " stall with healthy CPU.",
                "A terminal operation of a parallelStream()/parallel() pipeline, or an async"
                        + " CompletableFuture stage with no executor argument, whose source lines enclose a call"
                        + " classified as blocking by the shared blocking-call catalog. Confidence is capped at"
                        + " MEDIUM.",
                "Enclosure is derived from source line ranges. A blocking call hidden in a helper"
                        + " method called from the lambda is not followed. A custom common-pool parallelism"
                        + " setting is not visible.");

        put(catalog, UnboundedThreadPoolRule.ID,
                "Thread pool with no bound on threads or queue",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Any code that creates executors",
                "A pool is created with Executors.newCachedThreadPool(), with a ThreadPoolExecutor"
                        + " whose queue is an unbounded LinkedBlockingQueue or whose maximum size is"
                        + " Integer.MAX_VALUE, or a Spring pool configured with"
                        + " setMaxPoolSize/setQueueCapacity(Integer.MAX_VALUE).",
                "A pool is the application's back-pressure. A cached pool starts a thread per task"
                        + " that finds none idle, so a burst or a slow dependency becomes thousands of threads"
                        + " and an OutOfMemoryError. An unbounded queue accepts unlimited work, so memory grows"
                        + " and latency climbs with no rejection to signal it.",
                "A call to newCachedThreadPool on Executors (HIGH confidence), a ThreadPoolExecutor"
                        + " constructor whose arguments contain a capacity-less"
                        + " LinkedBlockingQueue/LinkedBlockingDeque or Integer.MAX_VALUE, or a Spring pool"
                        + " setter given Integer.MAX_VALUE (MEDIUM).",
                "Pool configuration split across several statements or read from properties is not"
                        + " combined. Executors.newFixedThreadPool also uses an unbounded queue but is common"
                        + " and deliberate, so it is not reported.");

        put(catalog, TimerTaskExceptionKillsTimerRule.ID,
                "java.util.Timer: one failing task silently stops all the others",
                "concurrency", Severity.WARNING, ALL_LANGUAGES,
                "Any code that schedules recurring work",
                "A java.util.Timer is created with new Timer(...), or Kotlin's fixedRateTimer is"
                        + " used.",
                "A Timer runs every task on one thread. If any TimerTask throws, that thread terminates and the "
                        + "timer is cancelled: all other scheduled tasks never run again, and scheduling another throws "
                        + "IllegalStateException. The exception itself is only printed by the thread's default uncaught "
                        + "exception handler (stderr) unless the task catches it, so nothing in the application's logging "
                        + "reports that the timer died.",
                "A constructor call to Timer (java.util.Timer; Micrometer's Timer is never"
                        + " constructed with new) or a call to fixedRateTimer, outside test methods. Reported"
                        + " where the timer is created.",
                "The rule cannot see whether each task body catches its own exceptions, so a Timer"
                        + " whose tasks are all guarded is still reported.");

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
