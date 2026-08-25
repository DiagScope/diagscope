# DiagScope 1.0.0 — Release Readiness and Completion Plan

## 1. Purpose

This document defines what should be completed, stabilized, documented, tested, and released before DiagScope moves from the current alpha line to its first stable `1.0.0` release.

This plan assumes that real-repository validation with maintainer review has already been performed successfully.

The objective is therefore no longer to prove that the product idea works.

The objective is:

> **Turn the current analyzer into a stable, trustworthy, installable, supportable developer tool with explicit public contracts.**

New framework families such as Quarkus, Vert.x, Micronaut, and others are intentionally outside the `1.0.0` feature scope.

The first stable release should close the current JVM/Spring product surface before expanding it.

---

# 2. Proposed 1.0.0 Product Scope

DiagScope `1.0.0` should officially cover:

```text
Languages
├── Java
└── Kotlin/JVM

Build layouts
├── Maven
├── Gradle
├── Maven multi-module
├── Gradle multi-module
└── mixed Java/Kotlin projects

Entrypoints
├── REST
├── Kafka listeners
└── scheduled jobs

Spring-aware semantics
├── Spring MVC-style entrypoints
├── Spring Kafka
├── Spring scheduling
├── Spring transactions
├── Spring AOP / proxy semantics
├── Micrometer annotations and APIs
├── JDBC
├── JPA EntityManager ownership
└── source-visible observability annotations

Outputs
├── Markdown
├── JSON
├── HTML
└── SARIF

Workflows
├── local scan
├── configuration policy
├── baseline
├── changed-file scan
├── fail-on severity policy
└── trend comparison
```

The 1.0 scope should **not** imply complete support for every Spring, Java, Kotlin, Kafka, JDBC, JPA, Micrometer, or build-tool behavior.

Support statements must remain capability-specific.

---

# 3. Current Repository Baseline Reviewed

At the time of this plan, the repository already contains:

```text
diagscope-core
diagscope-jvmanalysis
diagscope-javaparser
diagscope-kotlinparser
diagscope-cli
diagscope-test-fixtures
```

The current Maven project version is:

```text
0.1.0-alpha.1-SNAPSHOT
```

The current machine-readable result contract is:

```text
result.json schema: 1.2-alpha.1
```

The baseline format has already evolved independently and includes lifecycle information and fingerprint migrations.

The project already includes:

- deterministic fingerprints;
- deterministic result ordering;
- baseline workflows;
- removed-finding tombstones;
- fingerprint migration;
- `--changed-since`;
- `--fail-on`;
- SARIF;
- strict `diagscope.yml`;
- Java/Kotlin parser parity contracts;
- mixed-language call linking;
- explicit Java classpath solving;
- source-root overrides;
- interactive HTML;
- source snippets;
- trend comparison;
- performance corpus tooling;
- JFR support;
- Apache 2.0 licensing.

The remaining 1.0 work should therefore focus on **stability, usability, release engineering, explicit capability contracts, and operational trust**.

---

# 4. 1.0.0 Release Philosophy

A stable `1.0.0` release creates user expectations that did not exist during alpha.

The following should no longer change casually:

```text
CLI behavior
exit codes
configuration semantics
result.json compatibility
baseline compatibility
fingerprint behavior
rule IDs
severity semantics
confidence semantics
report meaning
security guarantees
```

Internal Java packages do not necessarily become public APIs.

The stable contract should be deliberately narrow.

---

# 5. P0 — Define the Public Stability Contract

This is the most important conceptual change before `1.0.0`.

Create:

```text
docs/STABILITY.md
```

It should explicitly define what DiagScope considers public.

## 5.1 Stable public contracts

Recommended stable contracts:

### CLI command names

```text
diagscope scan
diagscope trend
```

And any additional commands shipped in 1.0.

### CLI option names and meanings

Examples:

```text
--project
--output
--format
--entrypoint
--max-depth
--parallelism
--fail-on
--baseline
--update-baseline
--baseline-migration
--prune-removed-baseline
--changed-since
--config
--source-root
--classpath
```

### Exit codes

Recommended stable contract:

```text
0 = successful execution / configured policy passed
1 = successful scan but configured finding policy failed
2 = invalid configuration or execution/reporting failure
3 = unsupported project/input shape
```

### `result.json`

Stable according to its independent schema version.

### `diagscope.yml`

Stable according to its configuration schema version.

### Baseline files

Stable according to baseline schema and `fingerprintVersion`.

### Finding fingerprint behavior

Stable under a documented fingerprint version.

### Rule IDs

Once released in 1.0, a rule ID must not silently change meaning.

### Severity and confidence semantics

Their interpretation must be stable and documented.

---

## 5.2 Explicitly non-stable contracts

Recommended non-public/internal areas:

```text
Java package layout
internal class names
parser adapter internals
HTML DOM structure
CSS class names
Markdown exact wording
performance implementation details
internal evidence-record structure unless serialized publicly
```

The HTML report should continue stating that:

```text
result.json is the stable machine-readable contract.
```

---

# 6. P0 — Complete the Version Transition

The project currently contains multiple alpha references.

Before release:

```text
0.1.0-alpha.1-SNAPSHOT
```

must become:

```text
1.0.0
```

Update:

- parent POM;
- all module parent references;
- BuildInfo/version provider;
- README;
- CLI documentation;
- CHANGELOG;
- SECURITY;
- architecture docs;
- performance docs where Alpha language appears;
- rule documentation where Alpha-specific wording remains;
- testing documentation;
- baseline documentation;
- generated example reports;
- schema fixtures where appropriate.

Add a repository-wide release check:

```bash
grep -R "Alpha 1\|alpha.1\|0.1.0-alpha" .
```

The release should fail if stale prerelease language exists outside:

- migration documentation;
- historical changelog;
- retained compatibility fixtures.

---

# 7. P0 — Stabilize `result.json`

The current contract is `1.2-alpha.1`.

Do not reset it simply because the application version becomes `1.0.0`.

Recommended stable transition:

```text
1.2-alpha.1
        ↓
1.2
```

unless there is a breaking schema change before release.

Retain compatibility fixtures for:

```text
1.0-alpha.1
1.1-alpha.1
1.2-alpha.1
1.2
```

The stable product version and schema version are intentionally independent:

```text
DiagScope version: 1.0.0
result schema:    1.2
```

---

## 7.1 Add schema publication

Generate or maintain an explicit JSON Schema file:

```text
schemas/result-1.2.schema.json
```

Even if internal compatibility tests already exist, an actual schema artifact makes integrations easier.

Include:

- types;
- required fields;
- enums;
- descriptions;
- examples;
- version metadata.

Ship it with GitHub releases.

---

# 8. P0 — Rule Versioning

Rule IDs are already stable identities.

Before 1.0, add a clear policy for rule-semantic evolution.

Recommended finding metadata:

```json
{
  "ruleId": "SILENT_FAILURE_CONVERSION",
  "ruleVersion": 1
}
```

A wording improvement does not change `ruleVersion`.

A precision fix generally does not require a new rule ID, but may increment `ruleVersion` if the finding semantics materially change.

A fundamentally different claim should normally receive a new rule ID.

Example:

```text
same deterministic claim + better false-positive handling
→ same rule ID

same claim but evidence contract materially changed
→ same ID, increment ruleVersion

different operational claim
→ new rule ID
```

Document this before 1.0 so baselines and CI do not become ambiguous later.

---

# 9. P0 — Generalize Intentional Suppressions / Waivers

The current suppression model is intentionally narrow and source-directive based for specific constructs.

For 1.0, intentional acceptance should be a first-class product concept.

Keep baseline and suppression separate.

## Baseline means

```text
This finding already existed when the team adopted DiagScope.
```

## Suppression / waiver means

```text
A human reviewed this specific finding and intentionally accepts it.
```

Recommended configuration:

```yaml
schemaVersion: "1.0"

suppressions:
  - fingerprint: "sha256:..."
    reason: "Poison messages are intentionally consumed and audited separately."
    expires: "2027-01-31"
```

Minimum requirements:

- fingerprint required;
- reason required;
- expiration optional;
- invalid fingerprints rejected;
- duplicate suppressions rejected;
- expired suppressions reported;
- unused suppressions reported;
- suppression count included in reports;
- suppressed findings optionally available in debug/machine output.

Recommended report fields:

```text
Visible findings
Baseline-suppressed
Policy-suppressed
Expired suppressions
Unused suppressions
```

This avoids disabling an entire rule because of one legitimate exception.

---

# 10. P0 — Introduce an Analysis Capability Model

This is one of the most important additions before 1.0.

A user must be able to distinguish:

```text
0 findings because analysis found no issue
```

from:

```text
0 findings because the construct was not understood
```

Add a parser-neutral capability model.

Conceptually:

```json
{
  "analysisCapabilities": {
    "languages": {
      "java": "SUPPORTED",
      "kotlin": "SOURCE_FIRST"
    },
    "frameworks": {
      "spring": "SUPPORTED"
    },
    "features": {
      "restEntrypoints": "SUPPORTED",
      "kafkaListeners": "SUPPORTED",
      "scheduledJobs": "SUPPORTED",
      "springTransactions": "SUPPORTED",
      "springAop": "PARTIAL",
      "javaDependencyResolution": "OPTIONAL",
      "kotlinDependencyResolution": "SOURCE_FIRST"
    }
  }
}
```

Possible states:

```text
SUPPORTED
PARTIAL
SOURCE_FIRST
NOT_APPLICABLE
NOT_CONFIGURED
UNSUPPORTED
```

The report should never imply full coverage when a capability is partial or unavailable.

---

# 11. P0 — Rule Applicability Matrix

Build on the capability model.

Example HTML/Markdown section:

```text
Rule applicability

SILENT_CATCH                    ✓ analyzed
LOG_WITHOUT_THROWABLE           ✓ analyzed
KAFKA_ACK_NOT_INVOKED           ✓ applicable
TX_ROLLBACK_SUPPRESSED          ✓ applicable
AOP_SELF_INVOCATION             ✓ Spring proxy model applied
HTTP_CLIENT_FAILURE_DISCARDED   — not applicable
```

This is especially important before future framework support.

When Quarkus arrives later, the same rule catalog can explicitly state:

```text
Spring Kafka      supported
Quarkus Messaging future
Vert.x EventBus   future
```

rather than returning misleading zeros.

---

# 12. P0 — Revisit the Diagnostic Coverage Percentage

The current result schema defines flow coverage as:

```text
signals / (signals + findings)
```

This is mathematically deterministic, but it should not yet be presented as a universal percentage of diagnostic completeness.

Twenty logs and zero findings do not prove:

```text
100% diagnostic coverage
```

Recommended action for 1.0:

### Preferred

Rename the current metric to something less absolute:

```text
diagnosticSignalRatio
```

or:

```text
observedDiagnosticSignalRatio
```

and mark it:

```text
experimental
```

Do not use it for CI gating in 1.0.

---

## 12.1 Future true coverage model

A stronger future model could count diagnostic opportunities:

```text
catch block
→ failure preservation opportunity

external call
→ failure evidence opportunity

Kafka publication
→ completion/failure observation opportunity

async boundary
→ failure observation opportunity

DB resource acquisition
→ lifecycle opportunity
```

Then:

```text
coverage =
satisfied opportunities
/
identified opportunities
```

That is more defensible as actual diagnostic coverage.

Do not block 1.0 waiting for this larger model.

---

# 13. P0 — Stabilize Entrypoint Identity

Display metadata and identity should be separate.

Example:

```text
Stable identity
REST:com.example.PaymentController#capture(String)

Display metadata
POST /v1/payments/{paymentId}/capture
```

Routes, topics, and schedules may contain:

- placeholders;
- composed annotations;
- externally supplied properties;
- framework-specific syntax.

A display-value change should not unnecessarily break:

- flow identity;
- trend comparison;
- baseline relationships.

Create or document:

```text
EntrypointIdentity
EntrypointDisplayMetadata
```

before new frameworks are introduced.

---

# 14. P0 — Make `RuleCatalog` the Single Source of Truth

The project now has enough rules that manual synchronization across:

```text
README
docs/RULES.md
HTML
SARIF
RuleCatalog
future CLI help
```

will eventually drift.

There is already evidence of rapid rule growth across roadmap/changelog/documentation.

Make `RuleCatalog` authoritative for:

```text
rule ID
rule version
title
category
default severity
description
what it means
why it matters
how it is detected
recommended action
supported languages
applicability
known limitations
documentation path
```

Then generate or validate:

```text
README rule table
docs/RULES.md summary
SARIF metadata
HTML explanations
CLI rule listing
```

Add:

```text
RuleDocumentationContractTest
```

The build must fail if:

- a registered rule has no catalog entry;
- documentation references a nonexistent rule;
- a default-enabled rule has no explanation;
- a rule lacks Java/Kotlin support metadata.

---

# 15. P0 — Add `diagscope rules`

For 1.0:

```bash
diagscope rules
```

should display the actual installed catalog.

Example:

```text
RULE                               CATEGORY          SEVERITY
SILENT_CATCH                       Error handling    ERROR
SILENT_FAILURE_CONVERSION          Error handling    ERROR
KAFKA_ACK_NOT_INVOKED              Messaging         ERROR
TX_ROLLBACK_SUPPRESSED             Database          ERROR
...
```

Options:

```bash
diagscope rules --format TABLE
diagscope rules --format JSON
diagscope rules --format MARKDOWN
```

This is useful for:

- users;
- documentation generation;
- CI integration;
- support questions.

---

# 16. P0 — Add `diagscope explain`

Example:

```bash
diagscope explain SILENT_FAILURE_CONVERSION
```

Output:

```text
Exception hidden behind a default value

Rule:
SILENT_FAILURE_CONVERSION

Version:
1

Category:
Error handling

Default severity:
ERROR

Supported languages:
Java, Kotlin

What it detects:
...

Why it matters:
...

Known limitations:
...

Suggested action:
...

Suppression:
supported
```

This makes the CLI independently understandable without opening documentation.

---

# 17. P0 — Add `diagscope doctor`

This should be one of the most useful 1.0 commands.

```bash
diagscope doctor --project .
```

Example:

```text
DiagScope Doctor

Runtime
✓ Java 25
✓ DiagScope 1.0.0

Project
✓ Gradle multi-module
✓ 6 modules discovered

Languages
✓ Java: 417 files
✓ Kotlin: 93 files

Sources
✓ src/main/java
✓ src/main/kotlin
✓ build/generated/sources/openapi

Resolution
✓ Java local resolution
⚠ Java dependency classpath not configured
✓ Kotlin source-level hierarchy
⚠ Kotlin dependency resolution is source-first

Framework capabilities
✓ Spring REST
✓ Spring Kafka
✓ Spring Scheduling
✓ Spring AOP
✓ Transactions
✓ Micrometer

Configuration
✓ diagscope.yml schema 1.0
✓ 31 rules enabled
✓ 2 rules overridden

Scan health
✓ 0 parse failures
⚠ 19 unresolved boundaries
⚠ 3 ambiguous boundaries
```

This answers the critical user question:

> Did DiagScope actually understand my project?

---

# 18. P0 — Define a Formal Support Matrix

Create:

```text
docs/SUPPORT_MATRIX.md
```

Do not say simply:

```text
Supports Java, Kotlin and Spring Boot.
```

Instead define exact support levels.

Example:

```text
Language / platform
Java source analysis                 Supported
Kotlin/JVM source analysis           Supported, source-first
Java dependency symbol solving       Supported with explicit --classpath
Kotlin dependency symbol solving     Not supported in 1.0

Build layout
Maven single-module                  Supported
Maven multi-module                   Supported
Gradle single-module                 Supported
Gradle multi-module                  Supported
mixed Java/Kotlin                    Supported

Spring
REST annotations                     Supported
Kafka listener semantics             Supported
Scheduling                           Supported
Transactions                         Supported
AOP/proxy source-visible behavior    Partial
runtime-created proxy state          Unsupported
external configuration               Partial / contextual
AspectJ load-time weaving            Not proven
```

Also document:

- tested operating systems;
- tested JDK runtime;
- analyzed project source levels;
- tested Maven/Gradle families.

Do not promise a version range that is not covered by automated tests.

---

# 19. P0 — Project Language-Level Compatibility Tests

The DiagScope runtime currently requires JDK 25.

That is separate from the Java version used by the analyzed application.

For 1.0, explicitly test projects targeting common JVM language levels.

Recommended fixture matrix:

```text
Java project targeting 17
Java project targeting 21
Java project targeting 25

Java + Kotlin project
Kotlin-only project
```

If all are supported, document them.

If only certain source syntax is guaranteed, document that instead.

The scanner's runtime JDK requirement must not be confused with the target application's source level.

---

# 20. P0 — Distribution Must Become a Product Feature

The current development launcher assumes a repository checkout and built target JAR.

That is fine for contributors, but not a 1.0 installation story.

Provide official release assets.

Minimum:

```text
diagscope-1.0.0.jar
diagscope-1.0.0.jar.sha256
```

Recommended bundle:

```text
diagscope-1.0.0/
├── bin/
│   ├── diagscope
│   └── diagscope.cmd
├── lib/
│   └── diagscope.jar
├── LICENSE
├── NOTICE
└── README.txt
```

Also provide:

```text
diagscope-1.0.0.tar.gz
diagscope-1.0.0.zip
```

The install flow should not require cloning the repository.

---

# 21. P0 — Release Workflow

The current build workflow verifies the Maven reactor.

For 1.0 add:

```text
.github/workflows/release.yml
```

Trigger:

```text
tag: v*
```

Pipeline:

```text
checkout
↓
JDK 25
↓
clean verify
↓
package distribution
↓
product smoke test
↓
generate checksum
↓
generate SBOM
↓
verify reproducibility
↓
create GitHub Release
↓
upload artifacts
```

The GitHub repository currently has no published release, so the first stable release should establish a repeatable release process rather than manually uploading one JAR.

---

# 22. P0 — Product Smoke Test

Compilation tests are not enough for a distributed CLI.

CI must execute the same artifact users download.

Example:

```bash
java -jar distribution/diagscope.jar --version

java -jar distribution/diagscope.jar scan \
  --project fixture \
  --format JSON,HTML,SARIF \
  --fail-on NONE
```

Verify:

- exit code;
- expected findings;
- fingerprint set;
- `result.json` schema;
- HTML generated;
- SARIF valid;
- output paths;
- no unexpected files;
- CLI version.

---

# 23. P0 — Cross-Platform CI

The current public build runs on Ubuntu.

For 1.0 add at least a release-readiness matrix:

```text
ubuntu-latest
macos-latest
windows-latest
```

Important because DiagScope handles:

- paths;
- glob matching;
- Git integration;
- output paths;
- temporary files;
- atomic moves;
- path normalization;
- source roots;
- classpath separators.

Run the core product smoke suite on all three.

Performance benchmarks can remain pinned to one controlled environment.

---

# 24. P0 — Maven Wrapper

Add:

```text
mvnw
mvnw.cmd
.mvn/wrapper/
```

This makes:

- contributor builds reproducible;
- CI consistent;
- release builds consistent.

Then use:

```bash
./mvnw clean verify
```

in CI and release documentation.

---

# 25. P0 — Reproducible Release Artifacts

The build already includes deterministic-output work.

Extend that guarantee to the released JAR.

Release gate:

```text
build artifact twice
↓
SHA-256 must match
```

If exact reproducibility cannot yet be guaranteed, document the remaining non-deterministic metadata explicitly.

---

# 26. P0 — SBOM and Third-Party License Inventory

The shaded CLI contains third-party code, including the Kotlin compiler distribution.

Before 1.0 generate:

```text
SBOM
THIRD_PARTY_LICENSES
NOTICE
```

Recommended SBOM:

```text
CycloneDX JSON
```

Release:

```text
diagscope-1.0.0-sbom.json
```

Verify all runtime dependency licenses are compatible with Apache-2.0 redistribution.

Do not rely solely on shade-plugin NOTICE merging as the complete dependency-license inventory.

---

# 27. P0 — Security Hardening Must Be Executable

`SECURITY.md` already defines strong invariants.

Convert them into tests.

Required security tests:

```text
NoApplicationExecutionTest
NoBuildExecutionTest
SymlinkEscapeTest
RelativeOutputEscapeTest
BaselinePathEscapeTest
ConfigPathPolicyTest
MaliciousHtmlSnippetEscapingTest
NoNetworkDuringScanTest
MalformedYamlSecurityTest
ClasspathInputBoundaryTest
```

The scanner should continue guaranteeing:

```text
no application startup
no Spring context initialization
no application class loading
no hidden Maven/Gradle execution
no source upload
```

---

# 28. P0 — Update `SECURITY.md`

Remove alpha-specific wording.

Add:

```text
Supported versions

1.0.x    supported
<1.0     prerelease / unsupported after migration window
```

Add an actual private vulnerability reporting method.

Prefer enabling GitHub Private Vulnerability Reporting.

Also describe:

- response expectations;
- what counts as a security-sensitive scanner change;
- source-code privacy model.

---

# 29. P0 — Repository Security Configuration

Before 1.0 enable the repository protections appropriate for a public OSS project:

```text
Dependabot alerts
Dependabot security updates
secret scanning
push protection where available
CodeQL / code scanning
private vulnerability reporting
```

Add branch/ruleset protections for:

```text
main
```

Recommended:

- pull request required for changes;
- build required;
- no force push;
- signed release tags if practical.

---

# 30. P0 — Report Trust Language

The report must avoid statements stronger than static evidence supports.

Product rule:

> **Absence of evidence is not evidence of absence.**

Avoid:

```text
Every flow is fully observable.
```

Prefer:

```text
No enabled rule detected a finding in the analyzed portions of these flows.
```

Avoid:

```text
This code cannot be reached at runtime.
```

Prefer:

```text
No supported entrypoint was resolved to this method within the configured analysis boundary.
```

Add tests for the zero-finding wording.

---

# 31. P0 — Report Severity and Confidence Must Stay Visually Independent

The current HTML has improved substantially and includes light/dark modes.

Before 1.0 make sure the final semantic mapping remains explicit.

Recommended:

```text
Severity
ERROR       red
WARNING     amber
INFO        cyan

Confidence
HIGH        violet or dedicated positive-neutral color
MEDIUM      blue
LOW         gray
```

Do not use green in a way that suggests:

```text
HIGH confidence = healthy code
```

because confidence is epistemic certainty, not application health.

---

# 32. P0 — Source-Snippet Privacy Control

HTML reports may contain proprietary code.

Add:

```bash
--source-snippets context
--source-snippets none
```

Potential config:

```yaml
report:
  sourceSnippets: context
```

Recommended default for local usage:

```text
context
```

But users must have an easy way to create a share-safe artifact.

Never include:

- absolute home paths by default;
- environment variables;
- secrets;
- entire files;
- unnecessary method bodies.

---

# 33. P1 — Spring Configuration Awareness

The current roadmap already identifies this.

Before expanding to new frameworks, make Spring analysis context-aware without executing the application.

Read safely:

```text
application.yml
application.yaml
application.properties
application-*.yml

logback.xml
logback-spring.xml
log4j2.xml

Micrometer configuration
Spring Kafka configuration
Spring AOP configuration
OpenTelemetry configuration
```

Use this information to:

```text
raise or lower confidence
explain uncertainty
improve rule applicability
```

Do not infer failure solely because in-repository configuration is missing.

Configuration may come from:

```text
environment variables
Kubernetes
Vault
AWS
Spring Cloud Config
deployment manifests
external secrets
```

---

# 34. P1 — HTML Trend / Comparison Report

`trend` already exists for Markdown and JSON.

Add:

```bash
diagscope trend \
  --base old/result.json \
  --current new/result.json \
  --format HTML
```

Suggested UI:

```text
3 New
4 Fixed
11 Persisting
```

Filters:

```text
NEW
FIXED
PERSISTING
severity
rule
file
```

This is highly valuable for PR review and demos.

---

# 35. P1 — Module as a First-Class Report Dimension

The scanner already understands multi-module projects.

Expose the module consistently on findings and flows.

Example:

```text
payments-api          7 findings
payments-domain       1 finding
payments-storage      4 findings
```

Add filtering:

```text
Module
Rule
Severity
Confidence
```

This becomes especially valuable in enterprise repositories.

---

# 36. P1 — CODEOWNERS Mapping

Optional but high-value.

Read:

```text
.github/CODEOWNERS
```

Map finding path to owner.

Example:

```text
Owner: @payments-platform
```

Useful for:

- PR summaries;
- report grouping;
- issue routing.

CODEOWNERS is routing metadata only and must never alter finding semantics.

---

# 37. P1 — GitHub Action

For `1.0.0`, I strongly recommend shipping at least one first-class CI integration.

A GitHub Action is the best first integration.

Example:

```yaml
- uses: DiagScope/diagscope-action@v1
  with:
    project: .
    baseline: true
    fail-on: WARNING
```

The action should:

- install the correct JDK/runtime automatically;
- run the stable CLI;
- upload HTML;
- upload SARIF optionally;
- write a PR/job summary;
- preserve stable exit-code semantics.

---

# 38. P1 — Maven and Gradle Plugin Decision

Plugins are useful, but should not delay 1.0 if they compromise runtime compatibility.

The DiagScope engine currently targets Java 25.

A Maven or Gradle project being analyzed may execute its build with an older JDK.

Avoid forcing:

```text
application build JVM = Java 25
```

just because DiagScope itself uses Java 25.

Recommended plugin architecture:

```text
Maven/Gradle integration
        ↓
thin wrapper
        ↓
launch isolated DiagScope CLI/runtime
```

rather than loading the Java-25 engine directly into the build process.

If this architecture is not ready:

```text
GitHub Action + standalone CLI = 1.0
Maven/Gradle plugins = 1.1
```

is a better release strategy than shipping fragile plugins.

---

# 39. P1 — Fat JAR Size and Cold-Start Budget

The CLI currently includes the Kotlin compiler embeddable dependency.

Measure before release:

```text
final JAR size
cold CLI startup
Java-only scan startup
Java-only peak RSS
Kotlin scan startup
Kotlin peak RSS
```

Set a recorded 1.0 baseline.

Do not split artifacts unless measurements show a real problem.

If needed later:

```text
diagscope-java
diagscope-full
```

or optional parser adapters.

Do not optimize packaging from intuition.

---

# 40. P1 — CLI Diagnostics Mode

Add a clean diagnostic mode:

```bash
diagscope scan --verbose
diagscope scan --debug
```

`--verbose`:

```text
module discovery
source roots
configuration source
language counts
resolution mode
parser counts
analysis limits summary
```

`--debug`:

```text
internal resolution decisions
boundary details
timing breakdown
```

Normal mode remains concise.

---

# 41. P1 — Standard Error Categories

Avoid returning only:

```text
DiagScope failed: ...
```

for every unexpected problem.

Introduce stable internal categories rendered to users.

Example:

```text
DSC-INPUT-001
DSC-CONFIG-001
DSC-PARSER-001
DSC-REPORT-001
DSC-GIT-001
```

Not necessarily dozens of error codes.

The goal is supportability:

```text
DiagScope failed [DSC-CONFIG-002]:
Unknown rule ID 'SILENT_CATC'.
Did you mean 'SILENT_CATCH'?
```

---

# 42. P1 — Configuration Ergonomics

The strict configuration policy is a strength.

Add:

```bash
diagscope config validate --project .
```

or include it in `doctor`.

Also consider:

```bash
diagscope config print-effective --project .
```

Output:

```yaml
rules:
  SILENT_CATCH:
    enabled: true
    severity: ERROR
...
```

This becomes useful when:

```text
CLI
+
project config
+
built-in defaults
```

combine.

---

# 43. P1 — Deprecation Policy

Create:

```text
docs/DEPRECATION_POLICY.md
```

Recommended stable policy:

### CLI options

A stable flag is not removed without:

1. deprecation warning;
2. replacement path;
3. at least one minor release of overlap.

### Config keys

Same policy.

### Result schema

Breaking change requires next major schema version.

### Rule ID

Never reused for a different claim.

### Fingerprint

Breaking identity change requires `fingerprintVersion` bump.

---

# 44. P1 — Upgrade Guide From Alpha

Create:

```text
docs/UPGRADING_TO_1_0.md
```

Cover:

```text
product version
result schema
baseline schema
fingerprint version
configuration
CLI behavior
rule changes
severity changes
suppression changes
```

Explain whether existing alpha baselines can be used directly.

If migration is needed, provide a deterministic command.

---

# 45. P1 — Baseline Integrity Command

Consider:

```bash
diagscope baseline validate --project .
```

Checks:

```text
schema version
fingerprint version
duplicates
malformed hashes
unknown migrations
stale/removed entries
suppression conflicts
```

This can also live inside `doctor`.

---

# 46. P1 — SARIF Contract Tests

SARIF is now a real integration surface.

Add tests for:

- stable rule IDs;
- valid locations;
- severity mapping;
- URI normalization;
- fingerprints;
- source snippets if included;
- properties/schema metadata.

Validate generated SARIF with an external schema validator during CI if practical.

---

# 47. P1 — HTML Security Tests

Because report source text becomes HTML:

Test source containing:

```text
<script>
</script>
<img onerror=...>
&
<
>
"
'
```

Also test malicious:

```text
rule message
file path
method name
evidence value
configuration value
```

Everything must be escaped.

The HTML report must remain offline with zero external requests.

---

# 48. P1 — Visual and Accessibility Regression Tests

The HTML report is now an important part of the product.

Add snapshots for:

```text
light desktop
dark desktop
mobile
zero findings
many findings
opened finding
source snippet
parse failure
large flow
```

Accessibility checks:

```text
keyboard navigation
focus states
tab semantics
contrast
no color-only meaning
reduced motion
```

---

# 49. P1 — Documentation Restructure for 1.0

Recommended documentation tree:

```text
docs/
├── GETTING_STARTED.md
├── INSTALLATION.md
├── CLI.md
├── CONFIGURATION.md
├── BASELINE.md
├── SUPPRESSIONS.md
├── RULES.md
├── SUPPORT_MATRIX.md
├── REPORTS.md
├── RESULT_JSON_SCHEMA.md
├── STABILITY.md
├── DEPRECATION_POLICY.md
├── SECURITY_MODEL.md
├── PERFORMANCE.md
├── ARCHITECTURE.md
├── TESTING_STRATEGY.md
├── TROUBLESHOOTING.md
├── UPGRADING_TO_1_0.md
└── ROADMAP.md
```

The README should remain a quick entrypoint rather than containing the complete product reference.

---

# 50. P1 — Installation Documentation

Document exactly:

```text
Download JAR
Verify SHA-256
Run version
Run first scan
Open HTML
Configure baseline
Enable CI
```

Example:

```bash
java -jar diagscope-1.0.0.jar --version
java -jar diagscope-1.0.0.jar doctor --project .
java -jar diagscope-1.0.0.jar scan --project .
```

---

# 51. P1 — Support and Troubleshooting Documentation

Create troubleshooting entries for:

```text
Unsupported project
No source roots found
Kotlin resolution limits
Java classpath not supplied
Unknown Git revision
Baseline version mismatch
Fingerprint mismatch
Malformed YAML
SARIF upload problem
Large-memory scan
Too many unresolved boundaries
No findings but partial capabilities
```

`doctor` should link conceptually to this documentation.

---

# 52. P1 — Maven Central Metadata

If any DiagScope artifact or future plugin will be published to Maven Central, prepare root POM metadata:

```text
licenses
developers
scm
issueManagement
ciManagement
distributionManagement
```

This is not required merely to publish a GitHub release JAR, but is necessary for a clean Maven Central story.

---

# 53. P1 — Release Notes Standard

Use a consistent release structure:

```text
# DiagScope 1.0.0

Highlights
Compatibility
New capabilities
Rule changes
CLI changes
Schema changes
Baseline changes
Performance
Known limitations
Upgrade notes
Checksums
```

Do not make users infer compatibility from the changelog.

---

# 54. P1 — Known Limitations Must Be Prominent

A stable version is not expected to know everything.

It is expected to be clear about what it does not know.

Prominently state:

```text
No application execution
No build execution
No runtime Spring context
No reflection/runtime-created behavior
Kotlin dependency resolution remains source-first
Java external dependency resolution requires explicit classpath
AspectJ weaving runtime state may not be provable
external libraries are boundaries
```

This increases trust rather than weakening the product.

---

# 55. P1 — Framework Extension Boundary Before Quarkus / Vert.x

Do not implement Quarkus or Vert.x in 1.0.

But prepare the internal architecture before starting them.

Avoid future parser logic like:

```java
if (spring) ...
else if (quarkus) ...
else if (vertx) ...
```

spread throughout adapters.

Create a framework-semantics extension boundary.

Conceptually:

```java
interface FrameworkSemanticsContributor {
    FrameworkId framework();
    void contribute(ProjectFacts project, SemanticFacts facts);
}
```

Or smaller roles:

```text
EntrypointContributor
MessagingSemanticsContributor
TransactionSemanticsContributor
InstrumentationContributor
AsyncBoundaryContributor
```

For 1.0 this can remain internal.

Spring becomes the first semantics provider.

After 1.0:

```text
Spring
Quarkus
Vert.x
Micronaut
```

can reuse the same parser-neutral rule model.

This is not a public plugin SPI requirement for 1.0.

It is an internal architecture boundary.

---

# 56. P1 — Analyzer Internal Hardening

Before framework expansion, keep large adapter classes manageable.

Target internal responsibilities:

```text
SourceFileDiscoverer
SourceParser
CompilationUnitMapper
MethodIndexBuilder
CallResolver
EntrypointDetector
AspectAnalyzer
CrossLanguageRelinker
```

Do not introduce interfaces mechanically.

Use package-private classes where appropriate.

Goal:

```text
better tests
better profiling
lower change risk
framework isolation
```

---

# 57. P1 — Performance Release Gate

The current internal budgets are strong and should become a release check.

Current engineering targets:

```text
250 JVM files       < 1 s       < 256 MB
1,000 JVM files     < 3 s       < 512 MB
5,000 JVM files     < 12 s      < 1 GB
```

Continue treating these as engineering targets, not public SLA.

Before 1.0 record:

```text
p50
p95
peak heap
RSS
allocated bytes where possible
startup time
JAR size
```

for the fixed corpus.

Release blockers:

```text
unexplained >10% median regression
semantic digest changes
unexpected memory regression
nondeterministic output
```

---

# 58. P1 — Performance Report in Releases

For major releases publish a small table:

```text
Corpus        Files    p50    p95    Peak RSS
small
medium
large
```

This helps users understand the cost of adoption and forces performance regressions to remain visible.

---

# 59. P1 — Determinism Across Operating Systems

Current determinism tests focus on repeated scans.

For 1.0 also verify semantic determinism across:

```text
Linux
macOS
Windows
```

Expected differences such as host paths must be normalized.

Fingerprint and machine-readable semantic output should not change merely because the scan ran on a different OS.

---

# 60. P1 — Git Compatibility Tests

`--changed-since` is part of the product.

Test:

```text
normal branch
detached HEAD
shallow clone
renamed file
deleted file
spaces in file path
non-ASCII path
merge-base scenario
invalid revision
```

Document limitations for shallow CI checkouts.

The GitHub Action should fetch sufficient history automatically when needed.

---

# 61. P1 — Baseline / Trend Compatibility Across Upgrade

Before 1.0 test this user path:

```text
alpha result
↓
upgrade DiagScope
↓
1.0 scan
↓
existing baseline
↓
trend
```

If fingerprint version remains compatible, prove it with tests.

If not, provide migration.

---

# 62. P1 — Fresh-User Release Test

Before publishing 1.0, test on a clean machine/container that has no repository checkout.

Flow:

```text
download artifact
verify checksum
run --version
run doctor
clone sample project
run scan
open output
```

If the README cannot guide this successfully, the release is not ready.

---

# 63. P1 — Example Project and Public Example Report

Provide:

```text
examples/
└── sample-spring-service/
```

or a separate tiny repository.

Also publish a static example:

```text
report.html
```

through GitHub Pages or release assets.

A prospective user should be able to understand the product without building it.

---

# 64. P1 — Issue Templates

Add:

```text
.github/ISSUE_TEMPLATE/
├── bug.yml
├── false-positive.yml
├── false-negative.yml
└── feature.yml
```

For static-analysis projects, false positives and false negatives deserve dedicated templates.

Request fields:

```text
DiagScope version
rule ID
language
framework/build
minimal source
expected result
actual confidence/severity
result schema
```

---

# 65. P1 — Pull Request Template

Add a lightweight PR checklist:

```text
rule fixtures added?
Java/Kotlin parity considered?
performance impact measured?
schema impact?
fingerprint impact?
docs updated?
security invariant affected?
```

This protects quality as contributors arrive.

---

# 66. P1 — Code Ownership Inside DiagScope

Consider:

```text
CODEOWNERS
```

for internal project areas:

```text
core rules
Java parser
Kotlin parser
CLI/reporting
schema
security
```

This becomes useful as the project gains contributors.

---

# 67. P2 — Homebrew

Useful after the first release is stable.

Example:

```bash
brew install diagscope
```

This significantly improves macOS adoption.

It is not a blocker for 1.0 if the GitHub release installation is clean.

---

# 68. P2 — SDKMAN

Also useful for JVM developers:

```bash
sdk install diagscope
```

Not a 1.0 blocker.

---

# 69. P2 — Docker Image

Only add if real users request containerized execution.

A CLI JAR plus GitHub Action may already cover most use cases.

Do not create another distribution channel without demand.

---

# 70. P2 — Native Image

Keep deferred.

Only investigate if measurements show:

```text
JDK availability
startup
memory
distribution size
```

are real adoption blockers.

Kotlin compiler/PSI dependencies may make native compilation costly and complex.

---

# 71. P2 — CODEOWNERS in Analyzed Repositories

This feature can be implemented after 1.0 if desired.

It maps findings to team ownership but does not improve analysis precision.

Useful, but not release-critical.

---

# 72. P2 — More Frameworks

Explicitly after stable 1.0:

```text
Quarkus
Vert.x
Micronaut
```

Before implementing each framework:

1. define entrypoint semantics;
2. define async semantics;
3. define messaging semantics;
4. define instrumentation model;
5. decide which existing rules are applicable;
6. add capabilities/applicability metadata;
7. add positive/negative/ambiguous fixtures;
8. validate on real repositories.

Do not simply add annotation names to Spring-oriented logic.

---

# 73. What I Would NOT Add Before 1.0

Do not block 1.0 on:

```text
LLM-generated findings
LLM CI decisions
autofix
source rewriting
cross-service OpenTelemetry topology
SaaS backend
organization dashboard
native image
Quarkus
Vert.x
Micronaut
large rule-count expansion
arbitrary diagnostic score
```

These create scope without improving the stability of the current product.

---

# 74. Recommended 1.0.0 Priority Table

## Release blockers — P0

| Item | Required before 1.0 |
|---|---|
| Public stability contract | Yes |
| Remove alpha version/wording | Yes |
| Stable `result.json` contract | Yes |
| Rule-version policy | Yes |
| Stable fingerprint contract | Yes |
| Intentional suppression/waiver model | Yes |
| AnalysisCapabilities | Yes |
| Rule applicability | Yes |
| Coverage metric rename/experimental status | Yes |
| Stable entrypoint identity | Yes |
| RuleCatalog source of truth | Yes |
| `diagscope rules` | Yes |
| `diagscope explain` | Yes |
| `diagscope doctor` | Yes |
| Support matrix | Yes |
| Runtime/project language compatibility tests | Yes |
| GitHub Release packaging | Yes |
| Release workflow | Yes |
| Cross-platform smoke CI | Yes |
| Maven Wrapper | Yes |
| Reproducible artifact check | Yes |
| SBOM / third-party licenses | Yes |
| Security invariants as tests | Yes |
| SECURITY.md stable policy | Yes |
| Repository security features | Yes |
| Source-snippet privacy control | Yes |
| Report trust-language review | Yes |

---

## Strongly recommended — P1

| Item | Recommendation |
|---|---|
| Spring configuration awareness | High |
| GitHub Action | Very high |
| HTML trend report | High |
| Module-level report dimension | High |
| CLI verbose/debug | High |
| Effective-config view | High |
| Deprecation policy | High |
| Upgrade guide | High |
| SARIF contract tests | High |
| HTML security/a11y tests | High |
| Multi-OS determinism | High |
| Git edge-case tests | High |
| Maven/Gradle thin integrations | Medium/High |
| Public example report | High |
| Issue templates | Medium |
| Framework semantics boundary | High before next framework |

---

# 75. Suggested Implementation Order

## Milestone A — Stabilize contracts

1. Create `STABILITY.md`.
2. Finalize `result.json` stable version.
3. Freeze fingerprint version.
4. Define rule versioning.
5. Define entrypoint identity.
6. Review severity/confidence semantics.
7. Rename or mark coverage score experimental.
8. Create `SUPPORT_MATRIX.md`.

---

## Milestone B — Complete product policy

9. General suppression/waiver model.
10. `AnalysisCapabilities`.
11. Rule applicability.
12. RuleCatalog synchronization.
13. `diagscope rules`.
14. `diagscope explain`.
15. `diagscope doctor`.
16. Effective configuration diagnostics.

---

## Milestone C — Harden reports

17. Finalize semantic colors.
18. Verify trust language.
19. Source-snippet privacy switch.
20. HTML XSS tests.
21. Accessibility checks.
22. Cross-platform output normalization.
23. HTML trend comparison if schedule permits.

---

## Milestone D — Harden distribution

24. Maven Wrapper.
25. Product smoke test.
26. Linux/macOS/Windows CI.
27. SBOM.
28. Third-party license inventory.
29. reproducible artifact test.
30. release workflow.
31. GitHub Release archive.
32. checksum.
33. clean-machine install test.

---

## Milestone E — Harden security

34. Security invariant tests.
35. Update SECURITY.md.
36. Enable private vulnerability reporting.
37. Enable repository security features.
38. Configure main branch protection/ruleset.
39. Add dependency/update automation.

---

## Milestone F — CI adoption

40. GitHub Action.
41. SARIF end-to-end test.
42. PR summary.
43. artifact upload.
44. baseline workflow example.
45. `--changed-since` Git edge-case tests.

---

## Milestone G — Release documentation

46. `INSTALLATION.md`.
47. `GETTING_STARTED.md`.
48. `SUPPORT_MATRIX.md`.
49. `STABILITY.md`.
50. `DEPRECATION_POLICY.md`.
51. `SUPPRESSIONS.md`.
52. `UPGRADING_TO_1_0.md`.
53. `TROUBLESHOOTING.md`.
54. final README cleanup.
55. final CHANGELOG entry.

---

# 76. Proposed 1.0.0 CLI Surface

Recommended stable top-level commands:

```text
diagscope scan
diagscope trend
diagscope doctor
diagscope rules
diagscope explain
```

Optional before 1.0:

```text
diagscope config validate
diagscope config print-effective
diagscope baseline validate
```

Keep the top-level command set small.

---

# 77. Proposed 1.0.0 Release Assets

```text
DiagScope-1.0.0 Release

diagscope-1.0.0.jar
diagscope-1.0.0.jar.sha256
diagscope-1.0.0.tar.gz
diagscope-1.0.0.zip
diagscope-1.0.0-sbom.json
result-1.2.schema.json
LICENSE
NOTICE
THIRD_PARTY_LICENSES.txt
```

Optionally:

```text
example-report.html
```

---

# 78. Proposed Stable Version Matrix

Example:

```text
Product version
1.0.0

result.json schema
1.2

configuration schema
1.0

baseline schema
1.1

fingerprint version
1

trend schema
1.0

rule catalog
individual ruleVersion values
```

Each evolves independently.

---

# 79. Recommended `CHANGELOG.md` 1.0 Section

```text
## 1.0.0 — First stable release

### Highlights
- stable Java/Kotlin/Spring diagnostic analysis
- stable machine-readable schema
- deterministic baselines and trend comparison
- CI-ready severity policy
- self-contained HTML and SARIF

### Compatibility
...

### Added
...

### Changed
...

### Deprecated
...

### Removed
...

### Fixed
...

### Known limitations
...
```

---

# 80. 1.0.0 Go / No-Go Checklist

## Product

- [ ] All intended Spring/JVM capabilities are documented.
- [ ] No planned pre-1.0 rule family remains half implemented.
- [ ] Real-repository validation outcomes are recorded.
- [ ] Known false-positive classes are documented.
- [ ] Known false-negative classes are documented.
- [ ] `doctor` clearly communicates partial analysis.

## Contracts

- [ ] CLI command names frozen.
- [ ] CLI options frozen.
- [ ] Exit codes frozen.
- [ ] config schema stable.
- [ ] result schema stable.
- [ ] baseline schema stable.
- [ ] fingerprint version stable.
- [ ] rule IDs frozen.
- [ ] rule-version policy documented.
- [ ] deprecation policy documented.

## Reports

- [ ] HTML opens offline.
- [ ] HTML makes zero network calls.
- [ ] source text is safely escaped.
- [ ] source snippets can be disabled.
- [ ] zero-findings language is evidence-honest.
- [ ] severity and confidence are visually distinct.
- [ ] Markdown and HTML agree semantically.
- [ ] SARIF contract validated.

## CLI

- [ ] `--version` prints `1.0.0`.
- [ ] `scan` works from downloaded artifact.
- [ ] `trend` works across compatible previous results.
- [ ] `doctor` implemented.
- [ ] `rules` implemented.
- [ ] `explain` implemented.
- [ ] errors are actionable.
- [ ] unsupported project gives stable exit `3`.
- [ ] policy failure gives stable exit `1`.

## Configuration / baseline

- [ ] invalid YAML fails loudly.
- [ ] unknown rule IDs fail loudly.
- [ ] baseline migration tested.
- [ ] removed tombstones tested.
- [ ] intentional suppressions implemented.
- [ ] expired suppressions visible.
- [ ] effective policy visible.

## Languages

- [ ] Java fixtures cover every rule.
- [ ] Kotlin fixtures cover every rule.
- [ ] mixed Java/Kotlin flows tested.
- [ ] cross-language defaults/varargs tested.
- [ ] older supported Java source levels tested.
- [ ] support matrix matches test coverage.

## Build layouts

- [ ] Maven single module.
- [ ] Maven multi-module.
- [ ] Gradle single module.
- [ ] Gradle multi-module.
- [ ] generated source roots.
- [ ] explicit classpath.
- [ ] unusual but supported source roots.

## Git

- [ ] changed files.
- [ ] renamed files.
- [ ] deleted files.
- [ ] shallow-clone behavior documented/tested.
- [ ] invalid revision.
- [ ] paths with spaces.
- [ ] non-ASCII paths.

## Platforms

- [ ] Linux smoke test.
- [ ] macOS smoke test.
- [ ] Windows smoke test.
- [ ] semantic output normalized across OS.
- [ ] wrapper scripts work where shipped.

## Performance

- [ ] small corpus budget checked.
- [ ] medium corpus budget checked.
- [ ] large corpus budget checked.
- [ ] p50 recorded.
- [ ] p95 recorded.
- [ ] peak heap/RSS recorded.
- [ ] startup time recorded.
- [ ] fat JAR size recorded.
- [ ] no unexplained >10% median regression.
- [ ] semantic digest unchanged across repeated runs.

## Security

- [ ] no application code execution.
- [ ] no build execution.
- [ ] no hidden network access.
- [ ] symlink escape tested.
- [ ] output escape tested.
- [ ] HTML injection tested.
- [ ] source privacy documented.
- [ ] Private Vulnerability Reporting configured.
- [ ] Dependabot configured.
- [ ] secret scanning enabled.
- [ ] code scanning enabled.

## Release

- [ ] Maven Wrapper used.
- [ ] release workflow works from tag.
- [ ] release artifact smoke tested.
- [ ] artifact reproducibility checked.
- [ ] checksum generated.
- [ ] SBOM generated.
- [ ] third-party licenses generated.
- [ ] GitHub Release created from automation.
- [ ] clean-machine install tested.
- [ ] release notes complete.
- [ ] upgrade guide complete.
- [ ] no accidental alpha wording remains.

---

# 81. Definition of Done for DiagScope 1.0.0

I would consider DiagScope `1.0.0` ready when:

> A developer who has never seen the project can download a signed/checksummed release artifact, run `doctor`, scan a Maven or Gradle Java/Kotlin Spring project, understand exactly what was and was not analyzed, review actionable findings in HTML, suppress a deliberate exception with justification, introduce a baseline for existing debt, gate new findings in CI, upload SARIF, compare results between versions, and rely on stable CLI/schema/fingerprint contracts without reading the DiagScope source code.

That is a substantially stronger definition of `1.0` than:

> all currently planned rules are implemented.

---

# 82. Recommended Post-1.0 Roadmap

After `1.0.0`:

## 1.1

```text
Maven/Gradle build plugins if not shipped in 1.0
HTML trend enhancements
CODEOWNERS
installation channels
configuration-aware precision improvements
```

## 1.2+

```text
framework semantics provider architecture fully exercised
Quarkus
Vert.x
Micronaut
```

## Later

```text
cross-service topology
OpenTelemetry evidence correlation
optional SaaS metadata
organization-level trend
```

Framework expansion should not change the stable semantics of existing Spring/JVM rules.

---

# 83. Final Recommendation

Do **not** delay `1.0.0` to add more frameworks.

Do **not** delay it merely to increase the rule count.

The current product already has enough analytical surface.

The key remaining work for a real 1.0 is:

```text
stabilize contracts
make capabilities explicit
make intentional exceptions manageable
make installation real
make releases reproducible
make security guarantees executable
make CI integration first-class
make documentation self-consistent
make upgrade behavior predictable
```

Once those are complete, DiagScope has a defensible stable boundary.

Then Quarkus, Vert.x, and other frameworks can extend an already trustworthy product instead of expanding an alpha.
