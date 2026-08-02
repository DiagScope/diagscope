# Code guidelines

## Principles

- immutability by default;
- explicit composition;
- dependencies pointing toward the core;
- small methods on measured critical paths;
- no abstraction without a current use case;
- exceptions with actionable context;
- deterministic output;
- conservative conclusions when resolution is incomplete.

## Language

Documentation, source comments, user-facing messages, rule descriptions, and durable architecture notes are written in English. Identifiers follow conventional Java English naming.

## Records

Use records for immutable values and analysis evidence. Make defensive copies of collections in compact constructors.

Records provide value semantics for their components; they do not make a mutable collection component immutable. Never expose a caller-owned mutable collection from a domain record.

## Null handling

Do not use `null` to represent unresolved or absent analysis results. Use `Optional` only at boundaries where absence is a meaningful domain state.

## Collections

- prefer ordered collections when ordering appears in public output;
- use indexed maps for frequent lookups;
- preserve deterministic iteration order where output depends on it;
- canonicalize evidence before hashing or serialization;
- pre-size collections only when a reliable estimate exists;
- do not replace standard collections with specialized structures without measurements.

## Flow and confidence invariants

- every reached non-root method has an explainable path;
- every call attempt has a resolution reason;
- an unresolved or truncated edge remains visible as a boundary;
- child path confidence is the minimum of parent confidence and edge confidence;
- finding confidence cannot exceed containing-method path confidence;
- weakness in one branch must not lower an independently resolved branch;
- traversal is cycle-safe and depth-bounded.

## Logging

The scanner should log only high-level lifecycle or diagnostic events by default. Do not add per-file, per-method, or per-node logs on the normal path.

## Exceptions

- use `UnsupportedProjectException` for unsupported project shape;
- use `IllegalArgumentException` for invalid scan options and programmer preconditions;
- use `IllegalStateException` for internal infrastructure failures;
- the CLI maps exceptions to stable exit codes;
- rules must not fail the scan because of unexpected source code: lower confidence, record unresolved evidence, or skip the unsupported construct.

## Comments

Document decisions, assumptions, performance constraints, and limitations. Do not restate line by line what the code already expresses.

## Performance-sensitive code

Readable loops are preferred on measured hot paths. Streams are acceptable outside hot paths or when they do not create measurable overhead. Any less-readable optimization must include evidence from a repeatable benchmark or JFR profile.
