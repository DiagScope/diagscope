# DiagScope

**Will this code explain itself when it fails in production?**

DiagScope is a static analyzer for Java and Kotlin/JVM projects. It follows real call flows — REST
endpoints, Kafka consumers, scheduled jobs, reactive handlers, plain public methods — and reports code
that silently destroys diagnostic evidence: swallowed exceptions, ignored async results, leaking locks,
blocking calls on event loops, N+1 queries, and more. It reads source files only; it never compiles or
runs the project it analyzes.

This repository is the analyzer itself. It is under active development (alpha).

| If you want to… | Go to |
|---|---|
| **Use** DiagScope on a project | [Homebrew tap](https://github.com/DiagScope/homebrew-tap) — install, scan, collect reports, set a gate, use it with AI assistants |
| See what it looks like | <https://diagscope.github.io> |
| **Work on** DiagScope | keep reading |

## Contents

- [Requirements](#requirements)
- [Repository map](#repository-map)
- [How it works](#how-it-works)
- [Build](#build)
- [Run locally](#run-locally)
- [Test](#test)
- [Add a rule](#add-a-rule)
- [Teach a parser a new fact](#teach-a-parser-a-new-fact)
- [Conventions that are enforced](#conventions-that-are-enforced)
- [Performance and profiling](#performance-and-profiling)
- [CI and releases](#ci-and-releases)
- [Documentation map](#documentation-map)

## Requirements

- **JDK 25** (the build enforces it; see [ADR-0002](docs/decisions/ADR-0002-java-25.md))
- **Maven 3.9+**
- Git

```bash
java -version    # must report 25
mvn -version     # must report 3.9.x
```

## Repository map

```text
diagscope-core            parser-neutral domain, ports, flow builder, rule engine and all rules
diagscope-jvmanalysis     layout detection (Maven/Gradle), analyzer composition, Java↔Kotlin call relinking
diagscope-javaparser      JavaParser adapter: Java source → core domain
diagscope-kotlinparser    Kotlin compiler PSI adapter: Kotlin source → core domain
diagscope-cli             Picocli commands, composition root, reporters (Markdown/JSON/HTML/SARIF/agent text), gate, init-agent
diagscope-maven-plugin    Maven `scan` goal: runs the engine in a build and applies the gate
diagscope-test-fixtures   small sample projects used by integration tests (resources only)
docs/                     architecture, CLI, configuration, rule lifecycle, ADRs, roadmap
scripts/                  run-fixture, benchmark, JFR profiling, corpus validation, CI action helpers
action.yml                GitHub composite action that runs a scan in CI
```

Dependencies point inward:

```text
diagscope-jvmanalysis ─► diagscope-core
diagscope-javaparser ──► diagscope-core, diagscope-jvmanalysis
diagscope-kotlinparser ► diagscope-core, diagscope-jvmanalysis
diagscope-cli ─────────► all of the above
diagscope-maven-plugin ► diagscope-cli (its public ScanWorkflow only)
```

`diagscope-core` compiles with the JDK alone. It must never import JavaParser, Kotlin PSI, Picocli,
Jackson, Spring, Kafka or Micrometer types: parsers translate source into core records at their
boundary, and rules only ever see those records. An architectural test enforces it. Background:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## How it works

```text
source files ─► parser adapters ─► AnalyzedProject ─► flows from entrypoints ─► rules ─► findings ─► reporters
```

1. **Parse.** `JavaParserProjectAnalyzer` and `KotlinParserProjectAnalyzer` turn each method into a
   `MethodModel`: annotations (plus their attributes), invocations, catch blocks, resolved calls, return
   type. `CompositeProjectAnalyzer` merges both languages and relinks calls across them.
2. **Find entrypoints.** REST handlers, Kafka listeners, reactive messaging handlers and scheduled methods
   (and, on request, every public method).
3. **Build flows.** `LocalFlowBuilder` walks the call graph from each entrypoint to a depth limit. Every
   reached method carries the path that led there and a *confidence* — a finding is never more confident
   than its path. Unresolved, ambiguous, external or too-deep calls become explicit *flow boundaries*
   instead of guesses.
4. **Run rules.** `RuleEngine` runs `DiagnosticRule`s per flow and `ProjectRule`s over the whole project,
   then deduplicates findings by fingerprint and merges their related flows.
5. **Report.** Reporters write `report.md`, `report.html`, `result.json`, SARIF and `result.agent.txt` (compact text
   for coding assistants). The CLI then applies baseline, changed-file scope and waivers, and the **gate**
   (`gate:` in `diagscope.yml`, [ADR-0007](docs/decisions/ADR-0007-gate-policy.md)) decides the exit code.

Key types, all in `diagscope-core`: `MethodModel`, `InvocationEvidence`, `CatchEvidence`, `Flow`,
`FlowMethod`, `Finding`, `Confidence`, `RuleEngine`, `RuleCatalog`.

## Build

```bash
mvn clean verify                       # everything: compile, all tests, package
mvn -DskipTests package                # just produce the jar (faster)
```

The runnable jar is `diagscope-cli/target/diagscope.jar` (shaded, self-contained).

## Run locally

Build the jar first (`mvn -DskipTests package`); the scripts below run it.

```bash
# scan the bundled sample project and write reports to target/fixture-report/
./scripts/run-fixture.sh

# scan any project
./bin/diagscope scan --project /path/to/project --output /tmp/diagscope-out
# …which is the same as
java -jar diagscope-cli/target/diagscope.jar scan --project /path/to/project

# useful while developing
./bin/diagscope scan --project /path/to/project --format AGENT   # compact text, as an assistant sees it
./bin/diagscope init-agent --project /path/to/project --dry-run # what it would add to a project
./bin/diagscope doctor --project /path/to/project     # what DiagScope understands about it
./bin/diagscope rules                                 # the rule catalog with versions
./bin/diagscope explain SILENT_CATCH                  # one rule's documentation
```

Open `/tmp/diagscope-out/report.html` to see the result; `result.json` is the machine-readable form
(schema in [docs/RESULT_JSON_SCHEMA.md](docs/RESULT_JSON_SCHEMA.md)). All CLI options are in
[docs/CLI.md](docs/CLI.md); the project policy file (`diagscope.yml`) is in
[docs/CONFIGURATION.md](docs/CONFIGURATION.md).

To try a change on a snippet, copy a sample from
`diagscope-test-fixtures/src/main/resources/fixtures/` somewhere, edit it, rebuild the jar and scan it.

## Test

Tests live next to the module they cover; the layers are described in
[docs/TESTING_STRATEGY.md](docs/TESTING_STRATEGY.md).

| Layer | Where | What it pins down |
|---|---|---|
| Rule unit tests | `diagscope-core/src/test/.../application/rule` | One rule against hand-built `MethodModel`s and flows: positive, negative, boundary |
| Domain and flow tests | `diagscope-core`, `diagscope-javaparser` | Confidence, paths, cycles, depth, fingerprints |
| Parser tests | `diagscope-javaparser`, `diagscope-kotlinparser` | Source snippets → the facts the parsers must extract |
| Fixture / parity tests | `diagscope-cli` (`JavaRuleParityTest`, `KotlinRuleParityTest`) | Every registered rule fires on real Java and Kotlin source, and its safe variants stay silent |
| Contract tests | `diagscope-cli` (`RuleDocumentationContractTest`, `ResultSchemaCompatibilityTest`) | Catalog ↔ registration ↔ versions consistency, `result.json` compatibility |
| Golden reports | `diagscope-cli` (`GoldenReportTest`) | The exact Markdown/JSON of the `mixed-flow` sample |

```bash
mvn clean verify                                              # everything

mvn -pl diagscope-core test                                   # one module
mvn -pl diagscope-core test -Dtest=BlockingCallsTest          # one class

# CLI tests need the modules they depend on built in the same run
mvn -pl diagscope-cli -am test -Dtest=KotlinRuleParityTest -Dsurefire.failIfNoSpecifiedTests=false

# an intentional report change: regenerate the goldens, then review the diff
mvn -pl diagscope-cli -am test -Dtest=GoldenReportTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Ddiagscope.golden.update=true
git diff diagscope-cli/src/test/resources/golden
```

## Add a rule

A rule is a class in `diagscope-core/src/main/java/dev/diagscope/core/application/rule/` plus its
registration, documentation and tests. The checklist below is what the contract tests enforce.

**1. Pick the kind.**

| Implement | When |
|---|---|
| `DiagnosticRule` (`evaluate(Flow)`) | The finding depends on how code is reached: it should carry the entrypoint → method path, be capped by path confidence, or look at callers (for example a missing `@Bulkhead` anywhere on the path). Only code reachable from an entrypoint is evaluated. |
| `ProjectRule` (`evaluate(AnalyzedProject)`) | The finding is local to a method or type, or needs a whole-project view. It also covers code no entrypoint reaches. |

**2. Write the rule.** Use the evidence the parsers already provide on `MethodModel` — `annotations()`,
`annotationAttribute(...)`, `invocations()`, `catches()`, `calls()`, `returnType()`, `id().parameterTypes()` —
and the shared helpers instead of new string heuristics:

- `RuleSupport` — Spring-bean/REST/test checks, constructor and receiver matching, dynamic-expression check;
- `DiagnosticSignals` — logging, throwables, metrics, MDC;
- `BlockingCalls` and `ExecutionContexts` — what blocks, and which thread a method runs on.

Every `Finding` needs a stable rule id, severity, confidence, source location, message, recommendation and an
**evidence map**. Rules prefer to stay silent over guessing: when in doubt, lower the confidence.

**3. Register it.** In `diagscope-cli/.../DiagScopeMain.java`, add the rule to the right list
(`flowRules` for a `DiagnosticRule`, the project-rule list otherwise) **and** to `registeredRuleIds()`.

**4. Document it.** Add a `put(catalog, MyRule.ID, …)` entry to `RuleCatalog`: title, category, default severity,
supported languages (`ALL_LANGUAGES` or `Set.of("kotlin")`), what it means, why it matters, how it is detected,
known limitations. `diagscope explain` prints this, and `RuleDocumentationContractTest` fails if a registered rule
has no entry. Bump `RuleVersions` only when you change what an existing rule reports.

**5. State its basis.** Add the rule to `RuleSources`: `SOURCED` with the URLs of the framework documentation or
source you read, or `PATTERN` when it is a generally accepted practice without a single normative source.
`RuleSourcesContractTest` fails for a rule with no basis, and `diagscope explain` prints it. Verify framework claims
against the source, not memory ([docs/RULE_AUDIT.md](docs/RULE_AUDIT.md) lists what that has already corrected).

**6. Test it.**

- A unit test next to the others: build `MethodModel`s and `InvocationEvidence` by hand and assert on the findings
  (`Wave11RulesTest` is a compact example). Cover a positive, a negative and a boundary case.
- Add the positive **and a safe variant** to the Java and Kotlin parity fixtures
  (`diagscope-test-fixtures/src/main/resources/fixtures/{java,kotlin}-rule-parity/…`), then assert in
  `JavaRuleParityTest` / `KotlinRuleParityTest` that the rule fires and the safe variant does not. A rule that
  declares Kotlin support must fire on Kotlin source.
- Regenerate the goldens (command above) and check the diff only adds the new rule version.

**7. Update the docs and changelog.** `CHANGELOG.md`, and `docs/RULES.md` when the rule deserves a longer
explanation than the catalog entry.

## Teach a parser a new fact

If a rule needs something `MethodModel` does not carry, extend **both** parsers so Java and Kotlin stay in step.
Two established patterns, in order of preference:

1. **Synthetic annotation.** The adapter adds a marker annotation, optionally with attributes, to the method or
   class: `Final`, `Synchronized`, `Suspend`, `FieldInjectionPresent`, `EagerCollectionFetch` (with the fields and
   line), `ParameterNames`. Rules read it with `hasAnnotation` / `annotationAttribute`. No domain change needed.
   See `JavaParserProjectAnalyzer.mapMethod`/`mapUnit` and `KotlinParserProjectAnalyzer.mapFunction`/`typeInfo`.
2. **New field on the evidence record**, when the fact belongs to one call (`InvocationEvidence.lastInLambda`).
   Keep the previous constructor overload so existing callers still compile.

Add a parser test that feeds a source snippet and asserts the fact, then a parity fixture that exercises it end to
end. Parsers do not read `src/test`; only `src/main/java` and `src/main/kotlin` are analyzed.

## Conventions that are enforced

- **Dependency direction** — see [Repository map](#repository-map). JavaParser/PSI types never reach `diagscope-core`.
- **Every finding has evidence, location, severity and confidence.** Confidence is capped by the flow path.
- **Fingerprints are part of the public contract.** A finding's identity is built from the rule id, the file and the
  rule's *evidence map* (not line numbers). Do not add, remove or rename evidence keys of an existing rule; changing
  identity needs a `Finding.FINGERPRINT_VERSION` bump and a migration
  ([docs/FINGERPRINT_POLICY.md](docs/FINGERPRINT_POLICY.md)).
- **Deterministic output.** The same sources and configuration must produce byte-identical reports.
- **No default-level logging** inside AST, method or file loops.
- **Prefer a conservative result over a guess.** Unresolved calls are boundaries, not targets.
- **Performance changes need evidence** — a benchmark or JFR profile
  ([docs/PERFORMANCE.md](docs/PERFORMANCE.md)).
- **Rules are retired, not deleted**, once released ([docs/RULE_LIFECYCLE.md](docs/RULE_LIFECYCLE.md)).

## Performance and profiling

```bash
./scripts/benchmark.sh /path/to/project 5          # repeated scans, timings per phase
./scripts/profile-jfr.sh /path/to/project          # JFR recording of a scan
./scripts/validate-corpus.sh /path/to/project ID   # record results on a fixed corpus
```

## CI and releases

`.github/workflows/build.yml` runs `mvn clean verify` on JDK 25 for every push to `main` and every pull request.
`action.yml` is the composite GitHub Action other repositories use to run DiagScope
([docs/CI.md](docs/CI.md)).

Releases are built by `.github/workflows/release.yml` when a version tag is pushed, or by running the workflow manually
with the `tag` input. The POM is the source of truth for the version.

```bash
mvn versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false   # 1. bump every module
git add -A && git commit -m "chore: release 0.2.0"               # 2. commit
git tag v0.2.0 && git push && git push --tags                    # 3. tag; the workflow does the rest
```

The workflow fails fast if the POM version does not match the tag. `alpha`, `beta` and `rc` versions are
published as pre-releases. The Homebrew formula lives in
[DiagScope/homebrew-tap](https://github.com/DiagScope/homebrew-tap).

## Documentation map

| Topic | Document |
|---|---|
| Architecture and module responsibilities | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/decisions/](docs/decisions) |
| Writing and testing code here | [docs/DEVELOPMENT_GUIDE.md](docs/DEVELOPMENT_GUIDE.md), [docs/CODE_GUIDELINES.md](docs/CODE_GUIDELINES.md), [docs/TESTING_STRATEGY.md](docs/TESTING_STRATEGY.md) |
| CLI, configuration, CI | [docs/CLI.md](docs/CLI.md), [docs/CONFIGURATION.md](docs/CONFIGURATION.md), [docs/CI.md](docs/CI.md), [docs/MAVEN_PLUGIN.md](docs/MAVEN_PLUGIN.md) |
| Rules: reference, backlog, lifecycle | [docs/RULES.md](docs/RULES.md), [docs/RULE_CANDIDATES.md](docs/RULE_CANDIDATES.md), [docs/RULE_LIFECYCLE.md](docs/RULE_LIFECYCLE.md) |
| Output contracts | [docs/RESULT_JSON_SCHEMA.md](docs/RESULT_JSON_SCHEMA.md), [docs/FINGERPRINT_POLICY.md](docs/FINGERPRINT_POLICY.md) |
| Rule audit and calibration | [docs/RULE_AUDIT.md](docs/RULE_AUDIT.md), `scripts/calibrate.sh` |
| Using it with AI assistants | [docs/CLI.md](docs/CLI.md#init-agent), [docs/CONFIGURATION.md](docs/CONFIGURATION.md#gate) |
| What can and cannot be analyzed | [docs/CAPABILITY_MODEL.md](docs/CAPABILITY_MODEL.md), [docs/GLOSSARY.md](docs/GLOSSARY.md) |
| Performance, roadmap | [docs/PERFORMANCE.md](docs/PERFORMANCE.md), [docs/ROADMAP.md](docs/ROADMAP.md) |

Contributing rules and the pull-request checklist: [CONTRIBUTING.md](CONTRIBUTING.md). Security reports:
[SECURITY.md](SECURITY.md). License: [Apache-2.0](LICENSE).
