# DiagScope Report

`mixed-flow` — 9 finding(s) across 3 flow(s).

| Metric | Value |
| --- | --- |
| Build system | Maven |
| Findings | 9 |
| Errors | 3 |
| Warnings | 4 |
| Info | 2 |
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

9 finding(s): 3 error(s), 4 warning(s), 2 info. 6 are high confidence and worth triaging first.

### Findings by rule

| Rule | What it flags | Findings | Highest severity | High | Medium | Low |
| --- | --- | --- | --- | --- | --- | --- |
| `HIGH_CARDINALITY_METRIC_TAG` | High-cardinality metric tag | 1 | `ERROR` | 0 | 1 | 0 |
| `KAFKA_DEAD_LETTER_NOT_CONFIGURED` | Kafka listener has no dead-letter topic configured | 1 | `WARNING` | 0 | 1 | 0 |
| `KAFKA_LISTENER_ERROR_NOT_PROPAGATED` | Kafka listener swallows the failure | 1 | `WARNING` | 1 | 0 | 0 |
| `KAFKA_SEND_RESULT_IGNORED` | Kafka send result ignored | 1 | `WARNING` | 1 | 0 | 0 |
| `KAFKA_TOPIC_HARDCODED` | Kafka listener topic name is a hardcoded string literal | 1 | `INFO` | 1 | 0 | 0 |
| `SCHEDULED_EXCEPTION_NOT_HANDLED` | @Scheduled method has no exception boundary | 1 | `INFO` | 0 | 1 | 0 |
| `SILENT_CATCH` | Exception caught and ignored | 1 | `ERROR` | 1 | 0 | 0 |
| `SILENT_FAILURE_CONVERSION` | Failure converted into a normal value | 1 | `ERROR` | 1 | 0 | 0 |
| `SYSTEM_OUTPUT` | Diagnostics written to standard output | 1 | `WARNING` | 1 | 0 | 0 |

### Findings by confidence

| Confidence | Findings | What it means |
| --- | --- | --- |
| `HIGH` | 6 | HIGH — the evidence is explicit in the source and the call path from the entrypoint was resolved without ambiguity. Treat it as a real finding. |
| `MEDIUM` | 3 | MEDIUM — the evidence is explicit, but part of the reasoning depends on resolution that static analysis cannot fully prove (interface or proxy dispatch, framework wiring, or a pointcut approximation). Confirm the runtime wiring before acting. |
| `LOW` | 0 | LOW — the situation is plausible but depends on runtime behaviour DiagScope cannot observe (dynamic targets, global handlers, or deep or ambiguous call edges). Use it as a hint, not as a defect. |

## Diagnostic coverage by flow

Score = explicit logging, metric, and instrumentation-annotation signals divided by those signals plus evidence-destroying findings reachable on the same flow. A zero with no findings means that no explicit instrumentation signal was observed.

| Flow | Type | Score | Signals | Findings | Logging | Metrics | Annotations |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Kafka topic=payments | `KAFKA_LISTENER` | 0% | 0 | 3 | 0 | 0 | 0 |
| POST /payments/{id}/capture | `REST` | 25% | 1 | 3 | 0 | 1 | 0 |
| Scheduled cron=0 */5 * * * * | `SCHEDULED` | 0% | 0 | 2 | 0 | 0 | 0 |

### Findings grouped by flow

- Flow `Kafka topic=payments`: `KAFKA_DEAD_LETTER_NOT_CONFIGURED` at `src/main/java/example/PaymentListener.java:4`, `KAFKA_LISTENER_ERROR_NOT_PROPAGATED` at `src/main/java/example/PaymentListener.java:6`, `SILENT_CATCH` at `src/main/java/example/PaymentListener.java:6`
- Flow `POST /payments/{id}/capture`: `HIGH_CARDINALITY_METRIC_TAG` at `src/main/java/example/PaymentMetrics.java:7`, `KAFKA_SEND_RESULT_IGNORED` at `src/main/java/example/PaymentPublisher.java:6`, `SILENT_FAILURE_CONVERSION` at `src/main/java/example/PaymentService.java:15`
- Flow `Scheduled cron=0 */5 * * * *`: `SCHEDULED_EXCEPTION_NOT_HANDLED` at `src/main/java/example/ReconciliationJob.java:4`, `SYSTEM_OUTPUT` at `src/main/java/example/ReconciliationJob.java:5`

### Findings grouped by file

- `src/main/java/example/PaymentListener.java`: `KAFKA_DEAD_LETTER_NOT_CONFIGURED` (line 4), `KAFKA_TOPIC_HARDCODED` (line 4), `KAFKA_LISTENER_ERROR_NOT_PROPAGATED` (line 6), `SILENT_CATCH` (line 6)
- `src/main/java/example/PaymentMetrics.java`: `HIGH_CARDINALITY_METRIC_TAG` (line 7)
- `src/main/java/example/PaymentPublisher.java`: `KAFKA_SEND_RESULT_IGNORED` (line 6)
- `src/main/java/example/PaymentService.java`: `SILENT_FAILURE_CONVERSION` (line 15)
- `src/main/java/example/ReconciliationJob.java`: `SCHEDULED_EXCEPTION_NOT_HANDLED` (line 4), `SYSTEM_OUTPUT` (line 5)

## Findings

### ⚠️ KAFKA_DEAD_LETTER_NOT_CONFIGURED — `src/main/java/example/PaymentListener.java:4`

Kafka listener has no dead-letter topic configured. Failed messages after retry exhaustion will be silently discarded.

**What this means:** A @KafkaListener method has no errorHandler attribute and no DeadLetterPublishingRecoverer visible at its call site.

**Why it matters:** After retry exhaustion the failed message is silently discarded. No DLT means no visibility into what failed, no ability to replay, and no audit trail. In financial or event-sourced systems this is data loss disguised as successful processing.

**How it was detected:** The @KafkaListener annotation has no errorHandler attribute, and no invocation in the method body references a dead-letter recoverer by name (DeadLetterPublishingRecoverer, DLT, DLQ).

**Suggested action:** Configure a DeadLetterPublishingRecoverer in a DefaultErrorHandler bean, or set the errorHandler attribute on @KafkaListener. This ensures failed messages are preserved for inspection and replay.

- Severity: `WARNING` · Confidence: `MEDIUM`
- Confidence means: MEDIUM — the evidence is explicit, but part of the reasoning depends on resolution that static analysis cannot fully prove (interface or proxy dispatch, framework wiring, or a pointcut approximation). Confirm the runtime wiring before acting.
- Affected flows: Kafka topic=payments (`MEDIUM`, depth 0)
- Fingerprint: `sha256:2b0cc76372c5ffa66401c169fd80e1077e5be1ee780813545cba4a4bcbcabf46`

**Copy-ready remediation (review before applying):**

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template);
    var backoff = new ExponentialBackOff(1000L, 2.0);
    backoff.setMaxElapsedTime(30_000L);
    return new DefaultErrorHandler(recoverer, backoff);
}
```

Register the DefaultErrorHandler as a @Bean so all listeners inherit it without per-listener errorHandler attributes. Adapt names and domain fields, then review the change in context.

<details><summary>Call paths (1)</summary>

- `KAFKA_LISTENER` Kafka topic=payments
  - `example.PaymentListener.consume(String)` ← evidence

</details>

<details><summary>Evidence</summary>

- `errorHandlerSet`: `false`
- `method`: `example.PaymentListener.consume(String)`

</details>

### ℹ️ KAFKA_TOPIC_HARDCODED — `src/main/java/example/PaymentListener.java:4`

@KafkaListener topics = "payments" on 'example.PaymentListener.consume(String)' is a hardcoded string literal. Topic names typically differ between environments; a hardcoded value will fail silently in an environment where the topic does not exist.

**What this means:** A @KafkaListener topics attribute contains a plain string literal instead of a property placeholder (${kafka.topic.name}). Topic names typically differ between environments.

**Why it matters:** In a deployment to an environment where the hardcoded topic does not exist, the listener silently receives no messages. The failure is discovered only when downstream business metrics are missing — a slow feedback loop that is hard to correlate with the deployment event.

**How it was detected:** The @KafkaListener annotation has a topics attribute whose value does not contain a ${…} placeholder pattern. Listeners with no topics attribute (e.g. those using topicPattern or @KafkaHandler class-level setup) are not flagged.

**Suggested action:** Replace the literal topic name with a property placeholder: @KafkaListener(topics = "${kafka.topics.payment-events}"). Define the property in each environment's configuration file (application-dev.yml, application-prod.yml) so the listener automatically subscribes to the correct topic per environment.

- Severity: `INFO` · Confidence: `HIGH`
- Confidence means: HIGH — the evidence is explicit in the source and the call path from the entrypoint was resolved without ambiguity. Treat it as a real finding.
- Affected flows: none
- Fingerprint: `sha256:64f99fe2018fcf81d71f7d1ce494dbc7bebe9d8b99f280cf4410c5fb2509d6ee`

<details><summary>Evidence</summary>

- `declaringType`: `example.PaymentListener`
- `method`: `example.PaymentListener.consume(String)`
- `topics`: `payments`

</details>

### ⚠️ KAFKA_LISTENER_ERROR_NOT_PROPAGATED — `src/main/java/example/PaymentListener.java:6`

Listener handles RuntimeException itself and returns normally, so retry, error handler and dead-letter routing never see the failure.

**What this means:** A listener catches an exception and does not rethrow it.

**Why it matters:** The container never sees the error, so error handlers, retries, and dead-letter routing are all skipped and the record is silently dropped.

**How it was detected:** A catch block inside a @KafkaListener/@KafkaHandler method that neither rethrows nor records the failure.

**Suggested action:** Rethrow the failure (or wrap it) so the container error handler, @RetryableTopic or the DLT can act on it.

- Severity: `WARNING` · Confidence: `HIGH`
- Confidence means: HIGH — the evidence is explicit in the source and the call path from the entrypoint was resolved without ambiguity. Treat it as a real finding.
- Affected flows: Kafka topic=payments (`HIGH`, depth 0)
- Fingerprint: `sha256:ca8b525fe9ba3b47affcae8fbb54f3da6cf5ae169a5ab8ed78bbc475ad4eb621`

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
- Fingerprint: `sha256:1af8214a79d36b210ba8b7ebcbdba79495bae367a5b6e4b8c3d0e9aafb089dee`

**Copy-ready remediation (review before applying):**

```java
catch (SomeException exception) {
    logger.error("Operation failed for {}", operationId, exception);
    throw new ServiceException("Operation failed", exception);
}
```

At a boundary where propagation is not possible, log and record a metric; never leave the block empty. Adapt names and domain fields, then review the change in context.

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
- Fingerprint: `sha256:3aec2e88d4983d5e2ca884e3bf3d3bf7d9bf492e1bc5400f2b910c48cc140885`

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
- Fingerprint: `sha256:487357adfb529ca342bf1d4eaa73b1e83f447e89c6e17e29bfcf6a43e4796187`

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
- Fingerprint: `sha256:bdf70fe3787c60619c05b2579edea1c40deda0aaad7471bf6f8772ba4721c35b`

<details><summary>Call paths (1)</summary>

- `REST` POST /payments/{id}/capture
  - `example.PaymentController.capture(String)`
    - `example.PaymentService.capture(String)` ← evidence

</details>

<details><summary>Evidence</summary>

- `method`: `example.PaymentService.capture(String)`
- `returnedExpression`: `false`

</details>

### ℹ️ SCHEDULED_EXCEPTION_NOT_HANDLED — `src/main/java/example/ReconciliationJob.java:4`

@Scheduled method has no exception boundary or instrumentation. The scheduler logs a failure and runs the task again, but the only trace is a generic framework log line: no metric, no alert and no context about what the job was doing.

**What this means:** A @Scheduled method has no try/catch block and no instrumentation annotation, leaving any exception to propagate directly to the scheduler.

**Why it matters:** The scheduler logs the exception and runs the task again, so the failure is not lost. But the only trace is a generic framework log line: no metric, no alert and no job-level context. A job that fails on every run looks the same as a quiet one until someone reads the logs. (A task that really stops after one exception is a java.util.Timer, reported by TIMER_TASK_EXCEPTION_KILLS_TIMER.)

**How it was detected:** A @Scheduled method that has no catch blocks, carries no instrumentation annotation, and makes at least one external method call.

**Suggested action:** Record failures with job context: catch, log the job name and input and rethrow or count it, annotate the method with @Timed/@Observed, or configure the TaskScheduler with an ErrorHandler that reports every scheduler failure centrally.

- Severity: `INFO` · Confidence: `MEDIUM`
- Confidence means: MEDIUM — the evidence is explicit, but part of the reasoning depends on resolution that static analysis cannot fully prove (interface or proxy dispatch, framework wiring, or a pointcut approximation). Confirm the runtime wiring before acting.
- Affected flows: Scheduled cron=0 */5 * * * * (`MEDIUM`, depth 0)
- Fingerprint: `sha256:693f99bbb716939c6aeb941e64c2c34edffe39e42ed9cc19644143a3bf94a04c`

<details><summary>Call paths (1)</summary>

- `SCHEDULED` Scheduled cron=0 */5 * * * *
  - `example.ReconciliationJob.execute()` ← evidence

</details>

<details><summary>Evidence</summary>

- `method`: `example.ReconciliationJob.execute()`

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
- Fingerprint: `sha256:145a90869697ad5139e4d33a15d3860a411cadd19a336f703b17398a00abb981`

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
