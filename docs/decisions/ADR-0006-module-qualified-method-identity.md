# ADR-0006: Qualify a method's identity by module when two declarations collide

## Status

Accepted and implemented (`result.json` 1.7-alpha.1).

## Context

Analysis is keyed by qualified type name and method signature (`MethodId`). When two modules of a monorepo declare
the same class, the second declaration is ignored: its methods reach no flow and no rule. Since `result.json`
1.6-alpha.1 this is reported as an identity conflict instead of a parse failure, which made the loss measurable:

| Project | Methods analyzed | Methods dropped |
| --- | --- | --- |
| quarkus-quickstarts | 801 | 132 (14% of 933) |
| spring-data-examples | 4,809 | 46 |
| vertx-examples | 837 | 4 |

Quickstart and example repositories are the extreme case, but any multi-module service repository with a repeated
class name (`Quote`, `Config`, `Application`, a DTO duplicated per service) loses the same way, and the user sees a
clean report.

What identity feeds, which decides how much a change can break:

- **Finding fingerprints** hash the rule, the normalized repository-relative path and the rule evidence (declaring
  type, method signature, expression). The path already contains the module directory, and the evidence uses
  `MethodId.displayName()`. Keeping `displayName()` as it is leaves every existing fingerprint unchanged: baselines
  and waivers survive, and only methods that were dropped before produce new findings.
- **Flow identity** is `<entrypoint type>:<entrypoint method displayName>`, built in at least five places
  (`JsonReporter`, `MarkdownReporter`, `FlowDiagnosticCoverage`, `RelatedFlow.id`, the `AnalysisResult` invariant of
  one coverage score per flow). Two entrypoints with the same display name in different modules (two `OrderController`
  classes) would collide here.
- **Call resolution** looks up `declaringType#name`. With two candidates in different modules it must choose, or
  declare the call ambiguous.

## Decision

1. **Qualify on collision only.** `MethodId` gets a `module` component. It is empty for every declaration that is
   not a repeat. When a second declaration of the same type and signature is found in another module, it keeps
   the module as its qualifier instead of being dropped. The first declaration, in file order, keeps an empty module.
   A project without collisions therefore produces exactly the same identities, flow ids and fingerprints as today.
2. **`displayName()` does not change.** A new `qualifiedName()` appends `@<module>` when the module is set, and is used
   only where uniqueness is required (flow ids). Finding evidence keeps `displayName()`, which is what keeps
   fingerprints stable.
3. **The module is a directory.** It is the entry of `ProjectLayout.modules()` that contains the file (longest
   prefix), as a relative path with `/`. It is not the Maven `artifactId`: the directory is deterministic and already
   in every path.
4. **Call resolution prefers the caller's module.** Among candidates for a type, the one in the caller's module wins.
   If the caller's module has none and the candidates come from several other modules, the call is `AMBIGUOUS`; it is
   not resolved to the first one, because that would attach the wrong body to the flow.
5. **Flow ids stay as they are unless they would collide.** Only a flow whose `<type>:<displayName>` is already taken
   gets the `@<module>` suffix. Consumers that store flow ids see no change in projects without collisions.
6. **Identity conflicts remain for what is still a duplicate:** the same type and signature twice in one module
   (two source sets, Java and Kotlin sharing a name). They keep their array and statistics.

## Consequences

- Methods that were silently dropped are analyzed, so scans of monorepos report findings that were missing. This is the
  purpose, but it will read as new findings in existing baselines. The release note says so, and the numbers above
  are the expected size.
- No `fingerprintVersion` bump, no baseline migration: identities of existing findings do not change. A contract test
  must prove it by comparing fingerprints before and after on the corpus.
- `result.json` stays compatible: ids appear unchanged unless they would collide; the suffix is a new, additive form.
  `schemaVersion` moves to 1.7-alpha.1 when flows carry a `module` field.
- Cost: flows and methods grow by the dropped share (14% on the worst corpus project). The performance guard of
  ADR-0005 applies.
- Ambiguous cross-module calls add `AMBIGUOUS` boundaries where a wrong first-match used to hide them. That is
  accurate, and it is visible in `doctor`.

## Alternatives considered

- **Always qualify by module.** Simple rule, but every flow id and path string in every report changes, and consumers
  of unambiguous projects pay for a problem they do not have.
- **Scan each module on its own.** Keeps identities unique but loses every flow that crosses a module boundary, which
  is where most services put their controller-to-repository path.
- **Keep dropping and report it** (the state since 1.6-alpha.1). Honest, but it leaves a known 14% blind spot.
- **Use the Maven `artifactId`.** Needs build-file parsing for Gradle and Maven alike and fails when modules share a
  name; the directory does not.

## Known limit

Type-level facts (supertypes, interfaces, kind) are keyed by qualified type name, so a repeat of a class in another
module is analyzed with the method bodies of its own module but with the type information of the first declaration.
Two modules that declare the same class name with different supertypes can resolve an interface call through the wrong
hierarchy. Keying types by module is the next step if this shows up in practice.

## Plan and acceptance

1. `MethodId.module`, `qualifiedName()`, and assignment on collision in the Java, Kotlin and composite merges.
2. Module-aware call resolution in both adapters and the cross-language linker.
3. Flow-id disambiguation in the five places above; the one-score-per-flow invariant keeps holding.
4. `result.json` 1.7-alpha.1 (`module` on flows and methods), contract fixture, `RESULT_JSON_SCHEMA.md`, `CHANGELOG`.

Accepted when, on the calibration corpus: identity conflicts of kind `TYPE`/`METHOD` drop to the genuine same-module
duplicates; `parsedMethods` of quarkus-quickstarts rises from 801 to about 933; every fingerprint present before is
still present; no two flows share an id; and the scan time grows by no more than the share of added methods.

## Result on the calibration corpus

quarkus-quickstarts: 801 → 933 methods analyzed, identity conflicts 192 → 0, 4 findings that were hidden are now
reported. spring-data-examples: 4,809 → 4,855 methods, conflicts 72 → 0, 4 new findings. vertx-examples: 837 → 841.
No flow id is duplicated, and every fingerprint present before is still present in all seven projects.
