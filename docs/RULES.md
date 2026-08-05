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

Suppression is local to the supported source construct. Project-wide policies and baseline suppression are future-phase features.

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

Observed local handling may include returning or storing the future/result, waiting with `get`/`join`, or attaching completion/error callbacks. Exact supported fluent shapes remain fixture-driven and conservative.

Recommended response: make acknowledgement or failure participate in the business decision, or document and test the application-level policy that handles it elsewhere.

## `HIGH_CARDINALITY_METRIC_TAG`

Detects a likely unbounded value used as a metric tag.

- Default severity: `ERROR`.
- Confidence: `HIGH` for syntax-identified UUID values and `MEDIUM` for the remaining supported unbounded-value heuristics.
- Risk indicators: identifier-like tag keys, UUID-looking expressions, tokens, email addresses, request identifiers, and other per-entity values.
- Final confidence: capped by reachability of the containing method.

Identifiers usually belong in logs or traces. Metric tags should use bounded dimensions such as provider, operation, result, region, or error category.

Known limitation: Alpha 1 does not provide complete Micrometer receiver typing or data-flow provenance. The adapter discards `tag(...)` calls unless their receiver matches a supported Micrometer-style syntax, but similarly named custom APIs can still resemble those builders and indirectly derived unbounded values may be missed.

Recommended response: move the identifier to structured logs or trace attributes and replace the tag value with a bounded category.

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

## Rule admission criteria

Before adding another rule:

1. compare it with SonarQube, SonarLint, SpotBugs, Checkstyle, and IDE inspections used by validation teams;
2. define the narrower deterministic claim DiagScope can support;
3. prove positive, negative, and ambiguity cases;
4. verify path-confidence capping and deterministic fingerprinting;
5. measure runtime and allocation impact on the fixed corpus;
6. demonstrate useful flow context or a genuinely uncovered diagnostic risk.

A broad low-confidence heuristic is not automatically more valuable than a narrow high-signal rule.
