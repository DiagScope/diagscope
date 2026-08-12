# DiagScope Report

`mixed-flow` — 6 finding(s) across 3 flow(s).

| Metric | Value |
| --- | --- |
| Build system | Maven |
| Findings | 6 |
| Errors | 3 |
| Warnings | 3 |
| Info | 0 |
| Flows | 3 |
| Source files | 6 |
| Methods | 7 |
| Flow boundaries | 6 |
| Parse failures | 0 |
| Total time | <duration> |
| Tool version | `<version>` |

<details><summary>Scan configuration</summary>

- Maximum flow depth: 3
- Parser workers: 1
- Entrypoint types: KAFKA_LISTENER, REACTIVE_MESSAGE, REST, SCHEDULED
- Explicit classpath entries: none
- Additional source roots: none
- Project policy: none
- Disabled rules: none
- Baseline: none (suppressed 0, removed 0, migrated 0)
- Changed since: none (excluded 0)

</details>

## Executive summary

6 finding(s): 3 error(s), 3 warning(s), 0 info. 5 are high confidence and worth triaging first.

### Findings by rule

| Rule | What it flags | Findings | Highest severity | High | Medium | Low |
| --- | --- | --- | --- | --- | --- | --- |
| `HIGH_CARDINALITY_METRIC_TAG` | High-cardinality metric tag | 1 | `ERROR` | 0 | 1 | 0 |
| `KAFKA_LISTENER_ERROR_NOT_PROPAGATED` | Kafka listener swallows the failure | 1 | `WARNING` | 1 | 0 | 0 |
| `KAFKA_SEND_RESULT_IGNORED` | Kafka send result ignored | 1 | `WARNING` | 1 | 0 | 0 |
| `SILENT_CATCH` | Exception caught and ignored | 1 | `ERROR` | 1 | 0 | 0 |
| `SILENT_FAILURE_CONVERSION` | Failure converted into a normal value | 1 | `ERROR` | 1 | 0 | 0 |
| `SYSTEM_OUTPUT` | Diagnostics written to standard output | 1 | `WARNING` | 1 | 0 | 0 |

### Findings by confidence

| Confidence | Findings | What it means |
| --- | --- | --- |
| `HIGH` | 5 | HIGH — the evidence is explicit in the source and the call path from the entrypoint was resolved without ambiguity. Treat it as a real finding. |
| `MEDIUM` | 1 | MEDIUM — the evidence is explicit, but part of the reasoning depends on resolution that static analysis cannot fully prove (interface or proxy dispatch, framework wiring, or a pointcut approximation). Confirm the runtime wiring before acting. |
| `LOW` | 0 | LOW — the situation is plausible but depends on runtime behaviour DiagScope cannot observe (dynamic targets, global handlers, or deep or ambiguous call edges). Use it as a hint, not as a defect. |

## Diagnostic coverage by flow

Score = explicit logging, metric, and instrumentation-annotation signals divided by those signals plus evidence-destroying findings reachable on the same flow. A zero with no findings means that no explicit instrumentation signal was observed.

| Flow | Type | Score | Signals | Findings | Logging | Metrics | Annotations |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Kafka topic=payments | `KAFKA_LISTENER` | 0% | 0 | 2 | 0 | 0 | 0 |
| POST /payments/{id}/capture | `REST` | 25% | 1 | 3 | 0 | 1 | 0 |
| Scheduled cron=0 */5 * * * * | `SCHEDULED` | 0% | 0 | 1 | 0 | 0 | 0 |

### Findings grouped by flow

- Flow `Kafka topic=payments`: `KAFKA_LISTENER_ERROR_NOT_PROPAGATED` at `src/main/java/example/PaymentListener.java:6`, `SILENT_CATCH` at `src/main/java/example/PaymentListener.java:6`
- Flow `POST /payments/{id}/capture`: `HIGH_CARDINALITY_METRIC_TAG` at `src/main/java/example/PaymentMetrics.java:7`, `KAFKA_SEND_RESULT_IGNORED` at `src/main/java/example/PaymentPublisher.java:6`, `SILENT_FAILURE_CONVERSION` at `src/main/java/example/PaymentService.java:15`
- Flow `Scheduled cron=0 */5 * * * *`: `SYSTEM_OUTPUT` at `src/main/java/example/ReconciliationJob.java:5`

### Findings grouped by file

- `src/main/java/example/PaymentListener.java`: `KAFKA_LISTENER_ERROR_NOT_PROPAGATED` (line 6), `SILENT_CATCH` (line 6)
- `src/main/java/example/PaymentMetrics.java`: `HIGH_CARDINALITY_METRIC_TAG` (line 7)
- `src/main/java/example/PaymentPublisher.java`: `KAFKA_SEND_RESULT_IGNORED` (line 6)
- `src/main/java/example/PaymentService.java`: `SILENT_FAILURE_CONVERSION` (line 15)
- `src/main/java/example/ReconciliationJob.java`: `SYSTEM_OUTPUT` (line 5)

## Findings

### ⚠️ KAFKA_LISTENER_ERROR_NOT_PROPAGATED — `src/main/java/example/PaymentListener.java:6`

Listener handles RuntimeException itself and returns normally, so retry, error handler and dead-letter routing never see the failure.

**What this means:** A listener catches an exception and does not rethrow it.

**Why it matters:** The container never sees the error, so error handlers, retries, and dead-letter routing are all skipped and the record is silently dropped.

**How it was detected:** A catch block inside a @KafkaListener/@KafkaHandler method that neither rethrows nor records the failure.

**Suggested action:** Rethrow the failure (or wrap it) so the container error handler, @RetryableTopic or the DLT can act on it.

- Severity: `WARNING` · Confidence: `HIGH`
- Confidence means: HIGH — the evidence is explicit in the source and the call path from the entrypoint was resolved without ambiguity. Treat it as a real finding.
- Affected flows: Kafka topic=payments (`HIGH`, depth 0)
- Fingerprint: `sha256:c8d56953ecac47dcca0912b13987e4e345984403db66bf642098eccaa58effcd`

<details><summary>Call paths (1)</summary>

- `KAFKA_LISTENER` Kafka topic=payments
  - `example.PaymentListener.consume(String)` ← evidence

</details>

<details><summary>Evidence</summary>

- `entrypoint`: `Kafka topic=payments`
- `exceptionType`: `RuntimeException`
- `logged`: `false`
- `method`: `example.PaymentListener.consume(String)`

</details>

### ❌ SILENT_CATCH — `src/main/java/example/PaymentListener.java:6`

Exception is caught and ignored.

**What this means:** An exception is caught in a block that does nothing with it: no log, no rethrow, no recovery action recorded.

**Why it matters:** The failure disappears at this line. The caller keeps running as if the operation succeeded, and no trace of the original error reaches logs or traces.

**How it was detected:** The catch block body is empty (or only contains comments) and carries no DiagScope suppression.

**Suggested action:** Log, propagate, preserve the cause, or use an explicit DiagScope suppression with a reason.

- Severity: `ERROR` · Confidence: `HIGH`
- Confidence means: HIGH — the evidence is explicit in the source and the call path from the entrypoint was resolved without ambiguity. Treat it as a real finding.
- Affected flows: Kafka topic=payments (`HIGH`, depth 0)
- Fingerprint: `sha256:da4fb203d531fa0707e88dc1d1c75e5968402376e61d0797a6192acbe740920f`

<details><summary>Call paths (1)</summary>

- `KAFKA_LISTENER` Kafka topic=payments
  - `example.PaymentListener.consume(String)` ← evidence

</details>

<details><summary>Evidence</summary>

- `exceptionType`: `RuntimeException`
- `method`: `example.PaymentListener.consume(String)`

</details>

### ❌ HIGH_CARDINALITY_METRIC_TAG — `src/main/java/example/PaymentMetrics.java:7`

Metric tag may have unbounded cardinality: paymentId

**What this means:** A metric tag value comes from unbounded data such as an id, a user input, or a message payload.

**Why it matters:** Each distinct value creates a new time series, which inflates cost and can degrade or break the metrics backend exactly during an incident.

**How it was detected:** The tag value is not a literal, constant, or enum, and resolves to a caller-supplied or dynamically computed expression.

**Suggested action:** Keep unique identifiers in logs or traces and use bounded dimensions in metrics.

- Severity: `ERROR` · Confidence: `MEDIUM`
- Confidence means: MEDIUM — the evidence is explicit, but part of the reasoning depends on resolution that static analysis cannot fully prove (interface or proxy dispatch, framework wiring, or a pointcut approximation). Confirm the runtime wiring before acting.
- Affected flows: POST /payments/{id}/capture (`MEDIUM`, depth 2)
- Fingerprint: `sha256:619967b3ba07a12c50eab539f93995c4940292c8f9397ba6916b70778ab0a75b`

<details><summary>Call paths (1)</summary>

- `REST` POST /payments/{id}/capture
  - `example.PaymentController.capture(String)`
    - `example.PaymentService.capture(String)`
      - `example.PaymentMetrics.record(String)` ← evidence

</details>

<details><summary>Evidence</summary>

- `method`: `example.PaymentMetrics.record(String)`
- `micrometerConfirmed`: `true`
- `tag`: `paymentId`
- `value`: `paymentId`
- `valueProvenance`: `PARAMETER`
- `valueType`: `String`

</details>

### ⚠️ KAFKA_SEND_RESULT_IGNORED — `src/main/java/example/PaymentPublisher.java:6`

KafkaTemplate.send() result is ignored by this flow.

**What this means:** The asynchronous result of a Kafka producer send is discarded.

**Why it matters:** A broker-side failure completes the future exceptionally and is never observed, so the message is lost while the code reports success.

**How it was detected:** The send() result is neither assigned, chained with a completion callback, awaited, nor returned, and no producer listener was detected.

**Suggested action:** Verify whether the flow requires broker acknowledgement or explicit asynchronous failure handling.

- Severity: `WARNING` · Confidence: `HIGH`
- Confidence means: HIGH — the evidence is explicit in the source and the call path from the entrypoint was resolved without ambiguity. Treat it as a real finding.
- Affected flows: POST /payments/{id}/capture (`HIGH`, depth 2)
- Fingerprint: `sha256:cdeb8989de1ccd84a2f232807d8fb5b5c8b39c1c5361dda5523d02ebdda23d9f`

**Copy-ready remediation (review before applying):**

```java
kafkaTemplate.send(topic, payload).whenComplete((result, error) -> {
    if (error != null) logger.error("Kafka send failed for {}", messageId, error);
});
```

A ProducerListener configured centrally is also valid; avoid duplicate callbacks. Adapt names and domain fields, then review the change in context.

<details><summary>Call paths (1)</summary>

- `REST` POST /payments/{id}/capture
  - `example.PaymentController.capture(String)`
    - `example.PaymentService.capture(String)`
      - `example.PaymentPublisher.publish(String)` ← evidence

</details>

<details><summary>Evidence</summary>

- `method`: `example.PaymentPublisher.publish(String)`
- `producerListenerVisible`: `false`
- `receiverType`: `KafkaTemplate`
- `resultUsage`: `IGNORED`
- `scope`: `kafkaTemplate`

</details>

### ❌ SILENT_FAILURE_CONVERSION — `src/main/java/example/PaymentService.java:15`

Exception is converted to a normal return value without preserving diagnostic evidence.

**What this means:** An exception is caught and turned into a benign result such as null, an empty collection, false, or a default value.

**Why it matters:** Downstream code cannot distinguish 'no data' from 'the call failed', so the incident surfaces later as wrong data instead of as an error.

**How it was detected:** The catch block returns a constant or empty value and never logs, rethrows, or records the cause.

**Suggested action:** Preserve the cause, emit a diagnostic signal, or return a result containing a stable failure code.

- Severity: `ERROR` · Confidence: `HIGH`
- Confidence means: HIGH — the evidence is explicit in the source and the call path from the entrypoint was resolved without ambiguity. Treat it as a real finding.
- Affected flows: POST /payments/{id}/capture (`HIGH`, depth 1)
- Fingerprint: `sha256:9ed6a377b9b7224a3c9e7f257564d66390481e9372fbd338184c11bc18169a49`

<details><summary>Call paths (1)</summary>

- `REST` POST /payments/{id}/capture
  - `example.PaymentController.capture(String)`
    - `example.PaymentService.capture(String)` ← evidence

</details>

<details><summary>Evidence</summary>

- `method`: `example.PaymentService.capture(String)`
- `returnedExpression`: `false`

</details>

### ⚠️ SYSTEM_OUTPUT — `src/main/java/example/ReconciliationJob.java:5`

System output is used instead of application logging.

**What this means:** Diagnostic text is written with System.out or System.err instead of the logger.

**Why it matters:** The message escapes log routing, sampling, and correlation, so it cannot be searched or alerted on when the incident happens.

**How it was detected:** A direct call to System.out/System.err print methods on a reachable path.

**Suggested action:** Use the configured logger so the message participates in structured production telemetry.

- Severity: `WARNING` · Confidence: `HIGH`
- Confidence means: HIGH — the evidence is explicit in the source and the call path from the entrypoint was resolved without ambiguity. Treat it as a real finding.
- Affected flows: Scheduled cron=0 */5 * * * * (`HIGH`, depth 0)
- Fingerprint: `sha256:bf2b1ee1ff662367ab21286630a74db2262fb878b501c4ac1b9ad25b006a2483`

**Copy-ready remediation (review before applying):**

```java
logger.info("Operation completed for {}", operationId);
```

Use the application's configured logger and a stable, structured message. Adapt names and domain fields, then review the change in context.

<details><summary>Call paths (1)</summary>

- `SCHEDULED` Scheduled cron=0 */5 * * * *
  - `example.ReconciliationJob.execute()` ← evidence

</details>

<details><summary>Evidence</summary>

- `method`: `example.ReconciliationJob.execute()`

</details>

## Flow overview

Flow boundaries are analyzer limits, not defects.

| Entrypoint | Type | Confidence | Methods | Boundaries |
| --- | --- | --- | --- | --- |
| Kafka topic=payments | `KAFKA_LISTENER` | `HIGH` | 2 | 0 |
| POST /payments/{id}/capture | `REST` | `HIGH` | 4 | 5 |
| Scheduled cron=0 */5 * * * * | `SCHEDULED` | `HIGH` | 1 | 1 |

<details><summary>Boundaries — POST /payments/{id}/capture (5)</summary>

- `EXTERNAL` at `src/main/java/example/PaymentMetrics.java:7`: Counter.builder("payment.capture").tag("paymentId", paymentId).register(meterRegistry).increment()
- `EXTERNAL` at `src/main/java/example/PaymentMetrics.java:7`: Counter.builder("payment.capture").tag("paymentId", paymentId).register()
- `EXTERNAL` at `src/main/java/example/PaymentMetrics.java:7`: Counter.builder("payment.capture").tag()
- `EXTERNAL` at `src/main/java/example/PaymentMetrics.java:7`: Counter.builder()
- `EXTERNAL` at `src/main/java/example/PaymentPublisher.java:6`: kafkaTemplate.send()

</details>

<details><summary>Boundaries — Scheduled cron=0 */5 * * * * (1)</summary>

- `EXTERNAL` at `src/main/java/example/ReconciliationJob.java:5`: System.out.println()

</details>
