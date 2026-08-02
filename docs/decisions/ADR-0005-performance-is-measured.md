# ADR-0005: Performance must be measured

## Status

Accepted.

## Context

The scanner is expected to run during development and CI, but premature optimization can introduce complexity, bugs, and non-deterministic behavior.

## Decision

Concurrency, AST-retention, caching, and collection optimizations are not accepted based only on intuition. Repeatable benchmark or JFR evidence is required.

## Consequences

- the project avoids complexity with no demonstrated benefit;
- correctness and deterministic output remain mandatory constraints;
- performance-sensitive pull requests must include comparable measurements;
- internal budgets and regression thresholds are documented.
