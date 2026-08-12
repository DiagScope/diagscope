# Rules

## Rule contract

Every rule must define:

- a stable rule ID;
- default severity;
- an evidence-confidence policy;
- required typed parser-neutral evidence;
- the exact claim the finding makes;
- known limitations;
- a concise remediation message;
- positive, negative, and boundary tests.

Rules operate on `FlowMethod` values. They do not traverse JavaParser AST nodes. Final finding confidence is always the minimum of rule-evidence confidence and the confidence of the path that reached the containing method.

## Suppression policy

An ordinary comment does not suppress a finding. Alpha 1 recognizes a narrow, reviewable directive for supported catch evidence:

```java
catch (CleanupException ignored) {
    // diagscope:ignore SILENT_CATCH -- Best-effort cleanup after the response was committed.
}
```

A directive must name the exact rule and include a reason after `--`. This syntax is intentionally explicit so `// TODO`, commented-out code, or a vague explanatory comment cannot accidentally hide a diagnostic risk.

Source directives remain local to the supported construct. Project-wide rule policies are still a
future step, while CLI baseline suppression is available through `--baseline [path]` and uses the
stable finding fingerprint rather than source comments.

### When to lower confidence instead of suppressing

Suppression removes the finding from the report and from any future review. It is the correct answer only
when the code is provably intentional and the reason survives review. When the analyzer is merely uncertain,
the honest answer is a lower-confidence finding, not silence. Alpha 1 lowers confidence rather than
suppressing in these cases:

| Case | Why not suppression | Alpha 1 behavior |
|---|---|---|
| Handling delegated to a helper method the analyzer cannot follow (unresolved, external, or ambiguous call) | The handling may or may not exist; hiding it would assert something unproven | Finding is kept, capped by the path confidence of the boundary that reached it |
| Cause preserved through a custom result type or factory | Syntax alone cannot prove the cause survives | `MEDIUM` evidence confidence with the evidence expression reported |
| Logger receiver only probably a logger (untyped or externally injected) | A wrong assumption in either direction is a precision bug | Conservative typed-receiver evidence, reduced confidence |
| Handling reached through a single provable interface implementation | Runtime binding is not proven by source | Edge confidence is `MEDIUM`, and every descendant finding is capped by it |
| Same-arity overloads or maximum-depth truncation | The path is unknown, not proven safe | The call stays an explicit flow boundary and no finding is invented past it |

A reviewer who sees a low-confidence finding can act. A reviewer who sees nothing cannot.

## `SILENT_CATCH`

Detects a catch body with no executable handling and no valid explicit suppression directive.

- Default severity: `ERROR`.
- Evidence confidence: `HIGH` when the empty body is syntactically explicit.
- Final confidence: capped by reachability of the containing method.

An ordinary comment inside an otherwise empty catch remains a finding. A valid rule-specific directive with a reason suppresses it.

Known limitation: alpha suppression parsing is deliberately narrow; annotation-based, external-file, and inherited policies are not supported.

Recommended response: preserve or propagate the exception, emit useful structured evidence, or document an intentional best-effort ignore with the explicit directive.

## `SILENT_FAILURE_CONVERSION`

Detects a catch block that converts an exception into an apparently normal return value without logging, rethrowing, or otherwise preserving diagnostic evidence visible to the current syntax-first model.

- Default severity: `ERROR`.
- Typical evidence: `false`, `null`, `Optional.empty()`, or a failure-looking return expression without an observed cause or stable diagnostic code.
- Final confidence: capped by reachability of the containing method.

The problem is not returning a failure object by itself. The problem is making the original failure indistinguishable from an ordinary outcome.

Known limitation: Alpha 1 does not fully prove whether a custom result constructor, helper method, aspect, or external policy preserves the cause. Review the evidence expression and confidence.

Recommended response: preserve the cause, rethrow with chaining, emit a structured diagnostic signal, or return a stable failure code with enough context for investigation.

## `KAFKA_SEND_RESULT_IGNORED`

Detects a Kafka-like `send(...)` result that does not participate in the analyzed local decision path.

- Default severity: `WARNING`.
- Higher evidence confidence requires a receiver that syntax identifies as Kafka-related and a result used only as an expression statement.
- Final confidence: capped by reachability of the containing method.

The rule's claim is deliberately narrow:

> Broker acknowledgement is not observed by this analyzed local path.

It does not claim that the entire application has no global `ProducerListener`, framework configuration, interceptor, or external failure handling.

When the project declares a syntax-visible `ProducerListener` (a type implementing it, or a `setProducerListener(...)` call), the finding is still reported but its confidence drops to `LOW` and the evidence carries `producerListenerVisible=true`. The analyzer cannot prove that the listener is registered on this specific template, so the finding becomes a prompt to confirm the policy instead of a defect claim.

Observed local handling may include returning or storing the future/result, waiting with `get`/`join`, or attaching completion/error callbacks. Exact supported fluent shapes remain fixture-driven and conservative.

Recommended response: make acknowledgement or failure participate in the business decision, or document and test the application-level policy that handles it elsewhere.

## `REACTIVE_MESSAGE_ERROR_NOT_PROPAGATED`

Detects an `@Incoming` Reactive Messaging consumer that catches an exception and returns normally.

- Default severity: `WARNING`.
- Confidence: `MEDIUM` for broad exception types and `LOW` otherwise, capped by flow reachability.
- The consumer is intentionally classified as `REACTIVE_MESSAGE`, not Kafka: its channel connector and configured failure strategy are runtime configuration.

Recommended response: rethrow the exception or return a failed reactive result so the configured connector can apply its retry, nack, or dead-letter policy.

## `MUTINY_FAILURE_RECOVERED_SILENTLY`

Detects a Mutiny `onFailure()` recovery (`recoverWithItem`, `recoverWithNull`, `recoverWithCompletion`, or `recoverWithUni`) with no throwable-looking value in its callback or arguments.

- Default severity: `WARNING`.
- Confidence: `MEDIUM`, capped by flow reachability.

Known limitation: the rule does not prove callback side effects, subscription behavior, or a failure signal produced outside the local method. It only reports an explicit fallback where the source has no visible failure value.

Recommended response: log or count the failure in the recovery callback, or propagate it when a fallback is not an intentional degraded outcome.

## `MUTINY_SUBSCRIPTION_FAILURE_UNOBSERVED`

Detects the one-callback form of Mutiny `subscribe().with(...)`, which receives items but not failures.

- Default severity: `WARNING`.
- Confidence: `MEDIUM`, capped by flow reachability.

Recommended response: provide the second failure callback and record the throwable according to the flow's error policy.

## `HIGH_CARDINALITY_METRIC_TAG`

Detects a likely unbounded value used as a metric tag.

- Default severity: `ERROR`.
- Confidence: `HIGH` for syntax-identified UUID or date/time values and `MEDIUM` for the remaining supported unbounded-value heuristics.
- Values whose provenance is bounded are never reported: string/char/boolean literals, enum constants, and constant fields.
- Risk indicators: identifier-like tag keys, UUID-looking expressions, tokens, email addresses, request identifiers, and other per-entity values.
- Final confidence: capped by reachability of the containing method.

Identifiers usually belong in logs or traces. Metric tags should use bounded dimensions such as provider, operation, result, region, or error category.

Each tag carries its value provenance (`LITERAL`, `ENUM_CONSTANT`, `CONSTANT_FIELD`, `PARAMETER`, `LOCAL_VARIABLE`, `FIELD`, `METHOD_CALL`, `CONCATENATION`, `UNKNOWN`) and its declared value type in the evidence map, so a reader can judge the claim without reopening the file.

Receiver recognition is exact rather than name-shaped: known Micrometer registry types, the `Metrics` facade, the static meter builders (`Counter.builder`, `Timer.builder`, ...), and `Tag`/`Tags` factories. A custom `CustomBuilder.tag(...)` or a `NotAMeterRegistry` field is not Micrometer syntax and produces no evidence.

Known limitation: provenance is local and syntax-only. A value derived indirectly (through a helper method or a field assigned elsewhere) is classified as `METHOD_CALL`, `FIELD`, or `UNKNOWN` and may be missed.

Recommended response: move the identifier to structured logs or trace attributes and replace the tag value with a bounded category.

## `DYNAMIC_METRIC_NAME`

Detects a meter registered with a name that is not a compile-time constant on the analyzed local path.

- Default severity: `WARNING`.
- Confidence: `HIGH` for string concatenation, `MEDIUM` for parameters, locals, fields, and method calls.
- Final confidence: capped by reachability of the containing method.

A dynamic meter name multiplies time series exactly like an unbounded tag, but it is worse: the resulting series cannot be aggregated, and dashboards and alerts silently stop matching.

Recommended response: use a fixed meter name and move the varying part into a bounded tag.

## `PRINT_STACK_TRACE`

Detects direct `printStackTrace()` use in a reached method.

- Default severity: `WARNING`.
- Final confidence: capped by reachability of the containing method.

This rule is useful but not a primary product differentiator because conventional linters often report it.

Recommended response: preserve the throwable in structured logging or propagate it according to the service's error policy.

## `SYSTEM_OUTPUT`

Detects direct `print(...)` or `println(...)` calls through `System.out` or `System.err` in a reached method.

- Default severity: `WARNING`.
- Final confidence: capped by reachability of the containing method.

Known limitation: receiver recognition is syntax-based. The rule does not evaluate runtime redirection or test-only execution paths in Alpha 1.

Recommended response: use structured logging with stable context and the original throwable where relevant.

## `AOP_SELF_INVOCATION`

Detects a call from one method of a class to another method of the same class where the target is only
instrumented through a Spring proxy — `@Transactional`, `@Async`, `@Cacheable`, `@Retryable`,
`@PreAuthorize`, or a matching `@Aspect` advice. Spring proxies wrap the bean reference, not `this`, so
an internal call executes the plain method body and the advice never runs.

- Default severity: `WARNING`.
- Final confidence: `HIGH` for a proxied annotation on the target, `MEDIUM` when the instrumentation
  comes only from a pointcut match, then capped by reachability of the calling method.

Known limitation: the rule does not know whether AspectJ load-time weaving is enabled. Under weaving,
self-invocation is advised normally and the finding is a false positive.

Recommended response: move the annotated method to another bean, or inject a self reference obtained
from the context instead of calling `this`.

## `AOP_ADVICE_NOT_APPLIED`

Detects instrumentation attached to a target a JDK or CGLIB proxy cannot intercept: a `private`,
`static`, or `final` method, or a method of a `final` class.

- Default severity: `WARNING`.
- Final confidence: `HIGH` — the modifiers are read directly from source.

Known limitation: same weaving caveat as above.

Recommended response: make the method `public` (or at least non-final and non-static) on a proxied
bean, or move the behaviour to a method that can be intercepted.

## `AOP_UNMANAGED_ADVICE_TARGET`

Detects a class that carries proxy-dependent annotations or matches an aspect pointcut but shows no
Spring stereotype (`@Component`, `@Service`, `@Repository`, `@Controller`, `@RestController`,
`@Configuration`) and is not returned by a visible `@Bean` factory method. Advice only applies to beans,
so an instance created with `new` is never instrumented.

- Default severity: `INFO`.
- Final confidence: `MEDIUM` — component scanning and external configuration are not visible to source
  analysis, so the class may still be registered somewhere DiagScope cannot see.

Recommended response: confirm the class is a managed bean; if it is created manually, the annotation
is decorative and should be removed or the object should be obtained from the context.

## `KAFKA_ACK_NOT_INVOKED`

Detects a Kafka listener that declares an `Acknowledgment` parameter — which means the container runs in
a manual ack mode — while no method reachable from the listener calls `acknowledge()` or `nack(...)`.
The offset is never committed, so the record is silently reprocessed or the partition stalls.

- Default severity: `ERROR`.
- Final confidence: `HIGH`, capped by reachability of the listener.

Known limitation: an acknowledgement performed by a collaborator that local traversal cannot reach is
not visible, so the rule may report a listener that does acknowledge behind an unresolved call.

Recommended response: acknowledge on the success path and `nack(...)` on the failure path, or move the
container to an automatic ack mode.

## `KAFKA_LISTENER_ERROR_NOT_PROPAGATED`

Detects a listener method that catches an exception and returns normally. Container error handlers,
`@RetryableTopic` and dead-letter routing only run when the listener throws, so a handled-and-returned
failure commits the offset as if the record had been processed.

- Default severity: `WARNING`.
- Final confidence: `HIGH` for `Exception`, `RuntimeException` and `Throwable`, `MEDIUM` for a narrower
  exception type, then capped by reachability.

Known limitation: a listener that deliberately absorbs a poison record is a legitimate design; use an
explicit suppression comment for it.

Recommended response: rethrow (or wrap) the failure so the recovery path configured on the container
can act on it.

## `TX_ROLLBACK_SUPPRESSED`

Detects a catch block inside a `@Transactional` method that neither rethrows nor marks the transaction
rollback-only. Spring rolls back on a thrown unchecked exception; a swallowed failure lets a partially
applied write commit with no trace in the logs or in the response.

- Default severity: `ERROR`.
- Final confidence: `HIGH`, capped by reachability. A catch block that calls `setRollbackOnly()` (or a
  transaction manager `rollback`) is not reported.

Known limitation: `@Transactional(noRollbackFor = ...)` and programmatic transaction templates are not
modelled; the rule reads the annotation on the method or its declaring class.

Recommended response: rethrow, or call
`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` when the flow must continue.

## `JDBC_RESOURCE_NOT_CLOSED`

Detects `getConnection()`, `createStatement()`, `prepareStatement()`, `prepareCall()`,
`executeQuery()`, `getResultSet()` and `getGeneratedKeys()` results assigned to a variable that is neither a try-with-resources resource nor closed
in the same method. The failure surfaces later as pool exhaustion in an unrelated flow.

- Default severity: `ERROR`.
- Final confidence: `HIGH` when the result is assigned to a named variable, `MEDIUM` otherwise, then
  capped by reachability.

Known limitation: a resource handed to a collaborator that closes it is reported; annotate those call
sites with an explicit suppression.

Recommended response: acquire the resource in try-with-resources, or close it in a `finally` block on
every path.

## `DB_RESOURCE_CLOSE_NOT_GUARDED`

Detects a `Connection`, `Statement`, `PreparedStatement`, `ResultSet` or `EntityManager` acquired
outside try-with-resources and released only where the success path reaches the `close()` call. Every
throw between acquisition and release skips the close, so the handle leaks exactly on the paths that
already went wrong and the pool exhaustion surfaces in an unrelated flow.

- Default severity: `ERROR`.
- Final confidence: `HIGH`, capped by reachability. A release inside a `finally` block, or a
  try-with-resources acquisition, is not reported.

Known limitation: the release is matched inside the acquiring method. A handle closed by a
collaborator that receives it as an argument is still reported.

Recommended response: move the acquisition into try-with-resources, or close the handle in a `finally`
block.

## `JPA_ENTITY_MANAGER_NOT_CLOSED`

Detects `createEntityManager()` assigned to a variable that is neither a try-with-resources resource
nor closed in the same method. An application-managed `EntityManager` is owned by the caller: leaving
it open holds the persistence context and its connection.

- Default severity: `ERROR`.
- Final confidence: `HIGH` when the result is assigned to a named variable, `MEDIUM` otherwise, then
  capped by reachability.

Known limitation: an `EntityManager` deliberately kept open across a conversation and closed elsewhere
is reported; suppress those call sites explicitly.

Recommended response: close it in a `finally` block, or inject a container-managed one with
`@PersistenceContext`.

## `JDBC_TEMPLATE_CONNECTION_ESCAPE`

Detects `getConnection()` reached through a `JdbcTemplate`, `NamedParameterJdbcTemplate`,
`JdbcOperations` or `DataSourceUtils`. The template binds the connection to the active transaction and
translates driver errors into the Spring hierarchy; a hand-managed connection has neither, so writes
can land outside the surrounding `@Transactional` boundary and failures arrive as raw `SQLException`.

- Default severity: `WARNING`.
- Final confidence: `HIGH` when the connection is never released or released on the success path only,
  `MEDIUM` when it is released on every path — the transaction-binding concern remains either way.

Known limitation: some low-level work legitimately needs the raw connection (LOB streaming, vendor
APIs). Those call sites should carry an explicit suppression with the reason.

Recommended response: run the statement through the template, or release the handle with
`DataSourceUtils.releaseConnection` in a `finally` block.

## Rule admission criteria

Before adding another rule:

1. compare it with SonarQube, SonarLint, SpotBugs, Checkstyle, and IDE inspections used by validation teams;
2. define the narrower deterministic claim DiagScope can support;
3. prove positive, negative, and ambiguity cases;
4. verify path-confidence capping and deterministic fingerprinting;
5. measure runtime and allocation impact on the fixed corpus;
6. demonstrate useful flow context or a genuinely uncovered diagnostic risk.

A broad low-confidence heuristic is not automatically more valuable than a narrow high-signal rule.

## Explanations and confidence in report output

Every finding is enriched at report time from the rule catalog (`RuleCatalog` in `diagscope-core`):

- `explanation.title`, `explanation.whatItMeans`, `explanation.whyItMatters`, `explanation.howDetected`
- `confidenceRationale` — what `HIGH`, `MEDIUM` or `LOW` means for triage of that finding

Markdown renders them as **What this means / Why it matters / How it was detected** plus a `Confidence means:` line; the HTML report shows the same block above the evidence table; `result.json` carries both fields on each finding. The catalog is presentation-only text: it is not part of the fingerprint, so wording can change without invalidating baselines or suppressions. Rules without catalog entries fall back to a neutral explanation.
