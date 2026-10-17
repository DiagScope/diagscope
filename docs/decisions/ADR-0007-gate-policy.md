# ADR-0007: A project gate in `diagscope.yml` instead of a single severity flag

## Status

Accepted and implemented.

## Context

`--fail-on <severity>` was the only way to break a build. It cannot express what teams ask for first: "fail on
errors, but only when we are confident", "tolerate twenty warnings while we clean up", "stricter on release
branches", or "lenient for code an assistant just generated". It also lives in pipeline YAML, away from the code it
judges, and every CI system (Action, Gradle, a future Maven plugin) would have to reimplement whatever grows on top
of it.

## Decision

1. The policy is a `gate:` section of `diagscope.yml`: `failOn` conditions (any match breaches; each has a minimum
   severity, a minimum confidence and an optional rule list), `maxFindings` per severity, and named `profiles`.
2. The gate is evaluated last, on the findings left after baseline, changed-file filtering and waivers. The baseline
   is therefore the ratchet; no second mechanism is added.
3. Explicit CLI input wins, as for every other option: `--fail-on` replaces the gate, `--gate-profile` selects a
   profile, and combining them is an error. `--fail-on` lost its `NONE` default so that "unset" is distinguishable;
   the Action and the Gradle script pass it only when the user set it.
4. A profile replaces the default gate; nothing is merged. Merging semantics ("does `maxFindings` inherit?") are the
   kind of surprise a gate must not have.
5. The gate is a decision about the result, not part of it. `result.json` is unchanged, so one report can be judged
   by several profiles. The decision is visible through the exit code and the list of breached conditions on
   standard error.
6. The implementation lives in the CLI module (`GatePolicy`), not in core: analysis stays free of build policy, and
   embedders using `ScanWorkflow` get the same behaviour.

## Not decided here

- **Per-rule categories and per-path gates.** Rules have no category taxonomy, and path-specific tolerance can be
  approximated by `ignoredPaths` today. Adding either is additive.
- **Recording the gate outcome in `result.json`.** Worth doing when a consumer needs it (a PR comment, an assistant
  reading the verdict); it would be a minor schema bump.
- **Built-in profile names.** Shipping `strict`/`lenient` presets would freeze opinions about which findings are
  trustworthy; the calibration corpus does not yet support that for assistant-generated code.

## Consequences

- Existing configurations and pipelines behave as before: no `gate:` section and no `--fail-on` still exit `0`.
- A Maven or Gradle plugin and an assistant integration can reduce to "run the scan, honour the exit code", because
  tolerance is configured in one versioned place.
- Confidence becomes user-visible policy. A rule whose confidence is mislabelled now changes who gets blocked, which
  raises the cost of the calibration work already under way.
