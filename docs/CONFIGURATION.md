# Project configuration

DiagScope automatically loads `<project>/diagscope.yml` when it exists. Use `--config <path>` to
select another file. Relative paths must stay inside the analyzed project; an explicit absolute path
is accepted.

```yaml
schemaVersion: "1.0"

rules:
  LOG_WITHOUT_THROWABLE:
    enabled: false
  SENSITIVE_PAYLOAD_LOGGED:
    severity: WARNING

ignoredPaths:
  - "**/generated/**"
  - "src/main/java/legacy/*.java"

sensitiveFields:
  - accountNumber
  - customerReference

customLoggerTypes:
  - com.example.logging.AuditSink

customEntrypointAnnotations:
  REST:
    - BusinessEndpoint
  KAFKA_LISTENER:
    - MessageBoundary
  SCHEDULED:
    - BatchBoundary
```

## Contract

- `schemaVersion` is required and currently must be `"1.0"`.
- Unknown top-level keys, rule properties, rule IDs, entrypoint types, and duplicate YAML keys are
  rejected. A misspelled policy never silently becomes a no-op.
- `rules.<RULE_ID>.enabled: false` prevents the rule from executing.
- `rules.<RULE_ID>.severity` accepts `ERROR`, `WARNING`, or `INFO` case-insensitively and changes the
  reported severity without changing the stable fingerprint.
- `ignoredPaths` uses portable project-relative globs. `*` matches within one directory and `**`
  crosses directories. Matching Java and Kotlin files are removed before parsing.
- `sensitiveFields` augments the built-in sensitive-name vocabulary used by
  `SENSITIVE_PAYLOAD_LOGGED`.
- `customLoggerTypes` accepts simple or fully qualified receiver types. Standard logger method names
  (`trace`, `debug`, `info`, `warn`, `error`, `log`) on those receivers become logging evidence,
  including inside catch blocks.
- `customEntrypointAnnotations` maps an entrypoint type to method-level annotation names. Fully
  qualified names are reduced to their simple annotation name. Custom entrypoints use conservative
  display metadata because a project-specific route/topic/schedule contract is unknown.

## Precedence

1. Explicit CLI input wins: `--config` overrides automatic `diagscope.yml` discovery, and scan
   options such as `--entrypoint`, `--max-depth`, and `--parallelism` remain authoritative.
2. Project policy augments or overrides built-in rule policy.
3. Built-in defaults apply when no configuration file or matching entry exists.

The effective policy and its source file are emitted under `configuration.projectPolicy` and
`configuration.scanScope` in `result.json` and the embedded HTML payload. Markdown prints the same
scope in its scan-configuration section; SARIF carries it in run properties. Baseline suppression
counts and changed-file exclusions are recorded there as well, so zero visible findings can be
distinguished from a scan whose findings were filtered.
