# Example report

The values below are illustrative. The current Markdown contract separates scan context, flow boundaries, and findings:

```markdown
# DiagScope Report

- Tool version: `0.1.0-alpha.1-SNAPSHOT`
- Project: `payment-service`
- Source files: 84
- Methods: 391
- Flows: 17
- Findings: 3
- Parse failures: 0
- Flow boundaries: 12
- Maximum flow depth: 3
- Parser workers: 8
- Entrypoint types: KAFKA_LISTENER, REACTIVE_MESSAGE, REST, SCHEDULED
- Total time: 438 ms

## Flow Overview

### POST /payments/{paymentId}/capture

- Type: `REST`
- Method: `com.example.PaymentController.capture(UUID)`
- Confidence: `HIGH`
- Reached methods: 4
- Boundaries: 1

  - `EXTERNAL` at `src/main/java/com/example/PaymentPublisher.java:61`: kafkaTemplate.send()

## Findings

### ❌ SILENT_FAILURE_CONVERSION

- Fingerprint: `<64-character SHA-256>`
- Severity: `ERROR`
- Confidence: `HIGH`
- Location: `src/main/java/com/example/PaymentService.java:86`
- Related flows: POST /payments/{paymentId}/capture (`HIGH`)

Exception is converted to a normal return value without preserving diagnostic evidence.

**Recommendation:** Preserve the cause, emit a diagnostic signal, or return a result containing a stable failure code.

Evidence:

- `method`: `com.example.PaymentService.capture(UUID)`
- `returnedExpression`: `false`
```

The versioned JSON report additionally exposes effective configuration, complete reached-method paths, call edges and resolution reasons, structured parse failures, source ranges, phase durations in nanoseconds, fingerprints, ordered evidence, and structured related-flow identities and confidence.
