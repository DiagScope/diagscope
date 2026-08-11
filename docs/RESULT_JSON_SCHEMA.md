# `result.json` schema policy

`result.json` is a versioned integration contract, not merely an implementation detail of the HTML
report. Consumers must inspect the top-level `schemaVersion` before processing the document.

## Version format

DiagScope uses `MAJOR.MINOR[-prerelease]`:

- **MAJOR** changes when an existing field is removed, renamed, changes type, or changes meaning;
- **MINOR** changes when optional fields or enum values are added without invalidating existing
  readers;
- prerelease suffixes identify contracts that may still change before the corresponding stable
  release.

Changing wording in messages, explanations, or recommendations does not require a schema bump.
Changing the finding identity inputs requires a separate `fingerprintVersion` bump even if the JSON
shape stays the same.

## Compatibility rules

- Object readers must ignore fields they do not recognise.
- Enum readers should preserve or display unknown values instead of failing the whole report.
- Existing fields keep their type and meaning throughout a major version.
- New required fields are only allowed in a new major version. Additions within a major version are
  optional from a consumer's perspective.
- Array ordering is deterministic for reproducible reports, but consumers must not use array indexes
  as identity. Findings use `fingerprint`; flows use `id`.
- Paths use `/` separators in finding fingerprints. Display paths may follow the host filesystem.

## Change procedure

1. Update `JsonReporter.SCHEMA_VERSION` according to the rules above.
2. Retain the previous contract fixture under
   `diagscope-cli/src/test/resources/schema/`.
3. Add a fixture for the new version and keep the previous compatibility test passing unless this is
   an intentional major-version break.
4. Document migration notes in `CHANGELOG.md`.

The current contract is `1.3-alpha.1`; it adds a top-level `ruleVersions` map with the evidence
contract version of every registered rule, and reviewed-waiver lifecycle counts
(`waivedFindings`, `expiredWaivers`, `unusedWaivers`) under `configuration.scanScope`. Both are
additive, so consumers of the earlier contracts remain valid.

The previous contract is `1.2-alpha.1`; it adds diagnostic coverage components per flow, explicit
flow/file finding groups, optional deterministic remediation snippets, and baseline lifecycle
counts. These are additive fields: consumers of `1.0-alpha.1` and `1.1-alpha.1` remain valid.
The retained `result-contract-1.0-alpha.1.json` lists the fields older automation depends on, while
`result-contract-1.1-alpha.1.json`, `result-contract-1.2-alpha.1.json`, and
`result-contract-1.3-alpha.1.json` cover the additions.
`ResultSchemaCompatibilityTest` validates a real scan against all retained contracts.

The flow coverage percentage is intentionally decomposed in JSON. Each reached method contributes
at most one logging, metric, and instrumentation-annotation signal; findings linked to the same flow
are evidence-destroying constructs. The score is `signals / (signals + findings)`, rounded to the
nearest integer, and is zero when neither side has evidence.
Coverage is calculated from the complete scan before changed-file and baseline suppression, so an
accepted finding remains a diagnostic gap instead of silently improving the score.
