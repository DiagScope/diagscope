# Fingerprint stability policy

A finding fingerprint is the identity used by baselines, waivers, the `trend` command, and any
dashboard built on `result.json`. Changing it silently would resurrect suppressed findings and make
history unreadable, so identity changes are governed by this policy.

## What identity is built from

`sha256` over, in this exact order: a fixed namespace, the current `fingerprintVersion`, the rule id,
the normalized repository-relative file path, and the sorted rule evidence entries — which carry the
declaring type, the method signature, and the normalized expression.

Every finding carries its `fingerprintVersion` in `result.json` and in the baseline file.

## What must never change a fingerprint

- line and column numbers, or code moving inside the same file;
- catalog wording: title, "what it means", "why it matters", "how we detect it", remediation text;
- severity or confidence changes, including project overrides;
- report formatting, ordering, grouping, or new report formats;
- new metadata fields added additively to `result.json`;
- unrelated findings appearing or disappearing in the same file.

## What may change a fingerprint

Only these, and only as an announced, versioned event:

- renaming a rule id (see [rule lifecycle](RULE_LIFECYCLE.md));
- adding, removing, or renaming an evidence key that participates in identity;
- changing how a path or an expression is normalized;
- changing the digest algorithm or the field order above.

Any of these requires bumping `Finding.FINGERPRINT_VERSION`, even when the JSON contract itself is
unchanged, and requires a `result.json` schema-version bump when field shapes move.

## Migration procedure

1. bump `fingerprintVersion` in the same change that alters identity;
2. record every deterministic `OLD=NEW` mapping the change produces, using the baseline schema `1.1`
   migration list, so existing suppressions survive the rename;
3. where a mapping cannot be computed deterministically, the affected findings are reported as new
   and the change note says so explicitly;
4. describe the change in `CHANGELOG.md` under the release, naming the affected rules;
5. scanning with a baseline written at an older `fingerprintVersion` fails with an explicit message
   instructing the team to run `--update-baseline` after review — it never silently re-suppresses.

## Waivers

Waivers in `diagscope.yml` are pinned to a fingerprint on purpose. After a fingerprint migration, an
unmatched waiver is reported as unused rather than being auto-migrated, because a waiver asserts a
human review of a specific piece of evidence and that assertion does not transfer automatically.
