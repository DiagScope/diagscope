# DiagScope Report

`mixed-flow` — 5 finding(s) across 3 flow(s).

| Metric | Value |
| --- | --- |
| Build system | Maven |
| Findings | 5 |
| Errors | 3 |
| Warnings | 2 |
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
- Entrypoint types: KAFKA_LISTENER, REST, SCHEDULED

</details>

## Findings

### ❌ SILENT_CATCH — `src/main/java/example/PaymentListener.java:6`

Exception is caught and ignored.

**Suggested action:** Log, propagate, preserve the cause, or use an explicit DiagScope suppression with a reason.

- Severity: `ERROR` · Confidence: `HIGH`
- Affected flows: Kafka topic=payments (`HIGH`)
- Fingerprint: `sha256:da4fb203d531fa0707e88dc1d1c75e5968402376e61d0797a6192acbe740920f`

<details><summary>Evidence</summary>

- `exceptionType`: `RuntimeException`
- `method`: `example.PaymentListener.consume(String)`

</details>

### ❌ HIGH_CARDINALITY_METRIC_TAG — `src/main/java/example/PaymentMetrics.java:7`

Metric tag may have unbounded cardinality: paymentId

**Suggested action:** Keep unique identifiers in logs or traces and use bounded dimensions in metrics.

- Severity: `ERROR` · Confidence: `MEDIUM`
- Affected flows: POST /payments/{id}/capture (`MEDIUM`)
- Fingerprint: `sha256:92c8bcf1b244e056cedf1c9e058cd10b30cc900e47081355c600bd74f71ade6c`

<details><summary>Evidence</summary>

- `method`: `example.PaymentMetrics.record(String)`
- `micrometerConfirmed`: `true`
- `tag`: `paymentId`
- `value`: `paymentId`

</details>

### ⚠️ KAFKA_SEND_RESULT_IGNORED — `src/main/java/example/PaymentPublisher.java:6`

KafkaTemplate.send() result is ignored by this flow.

**Suggested action:** Verify whether the flow requires broker acknowledgement or explicit asynchronous failure handling.

- Severity: `WARNING` · Confidence: `HIGH`
- Affected flows: POST /payments/{id}/capture (`HIGH`)
- Fingerprint: `sha256:4e889ac8704ee2e9cc7cb3bc5a80d5c922f3066d4df55abcb109fa506879c255`

<details><summary>Evidence</summary>

- `method`: `example.PaymentPublisher.publish(String)`
- `receiverType`: `KafkaTemplate`
- `resultUsage`: `IGNORED`
- `scope`: `kafkaTemplate`

</details>

### ❌ SILENT_FAILURE_CONVERSION — `src/main/java/example/PaymentService.java:15`

Exception is converted to a normal return value without preserving diagnostic evidence.

**Suggested action:** Preserve the cause, emit a diagnostic signal, or return a result containing a stable failure code.

- Severity: `ERROR` · Confidence: `HIGH`
- Affected flows: POST /payments/{id}/capture (`HIGH`)
- Fingerprint: `sha256:9ed6a377b9b7224a3c9e7f257564d66390481e9372fbd338184c11bc18169a49`

<details><summary>Evidence</summary>

- `method`: `example.PaymentService.capture(String)`
- `returnedExpression`: `false`

</details>

### ⚠️ SYSTEM_OUTPUT — `src/main/java/example/ReconciliationJob.java:5`

System output is used instead of application logging.

**Suggested action:** Use the configured logger so the message participates in structured production telemetry.

- Severity: `WARNING` · Confidence: `HIGH`
- Affected flows: Scheduled cron=0 */5 * * * * (`HIGH`)
- Fingerprint: `sha256:bf2b1ee1ff662367ab21286630a74db2262fb878b501c4ac1b9ad25b006a2483`

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
