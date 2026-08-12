# Rule lifecycle

Rule identifiers appear in `diagscope.yml`, baselines, waivers, SARIF, and dashboards. Removing or
renaming one is a versioned event with a defined path, never a silent edit.

The lifecycle of every published identifier is recorded in
`dev.diagscope.core.application.rule.RuleLifecycle` and exposed by the CLI.

## States

| State | Evaluated | Meaning |
| --- | --- | --- |
| `ACTIVE` | yes | supported; enabled unless project policy disables it |
| `DEPRECATED` | yes | still reported, scheduled for removal, replacement stated when one exists |
| `REMOVED` | no | identifier reserved forever; configuration referencing it fails with an explanation |

## Transitions

1. **Deprecate.** Add the `RuleLifecycle` entry with the release in which the state was reached and
   the replacement id when the detection moved. The rule keeps running and keeps reporting; the
   deprecation is announced in `CHANGELOG.md` and shown by `rules` and `explain`.
2. **Wait.** A rule stays deprecated for at least one released minor version, so a team can migrate
   configuration and baselines during a normal upgrade rather than during an incident.
3. **Remove.** Flip the entry to `REMOVED`, delete the implementation, and keep the identifier
   reserved. `diagscope.yml` referencing a removed id fails with the removal release and the
   replacement, instead of an anonymous "unknown rule id".

An identifier is never reused for different semantics, and a rename is modelled as deprecate +
replacement id, never as an in-place edit.

## Effect on existing configuration and history

- **Project policy** — a deprecated id keeps working; a removed id is a configuration error, which
  is deliberate: a silently ignored disable line would re-enable a rule without anyone noticing.
- **Baselines and waivers** — entries for a removed rule stop matching and are reported as unused;
  prune them with the documented baseline pruning workflow.
- **Fingerprints** — a rename changes finding identity, so it follows the
  [fingerprint stability policy](FINGERPRINT_POLICY.md) and bumps `fingerprintVersion`.
- **Contract versions** — `RuleVersions` tracks evidence changes of a living rule; it is independent
  of lifecycle state, and both are published in the CLI catalog.

## Inspecting lifecycle state

```bash
java -jar diagscope.jar rules                          # active catalog with version and state
java -jar diagscope.jar rules --include-retired        # plus deprecated and removed identifiers
java -jar diagscope.jar rules --format JSON            # id, version, status, since, replacedBy
java -jar diagscope.jar explain SILENT_CATCH           # includes the lifecycle block
```

## Demotion is not removal

A noisy rule found during field validation is first demoted — to `INFO`, or disabled by default —
and refined. Removal is reserved for detections that are wrong in principle or fully superseded.
