# Roadmap

## Phase containment rule

Do not implement a future phase before the current phase meets its own continuation gate. This prevents unvalidated complexity from hiding precision, performance, or product-value problems.

| Phase | Objective | Continuation condition |
|---|---|---|
| 1 | Prove technical value | Valid findings, controlled noise, measured performance, and behavioral repeat interest |
| 2 | Enrich deterministic analysis | Recurring use and stable precision on the Phase 1 corpus |
| 3A | Enable responsible CI adoption | At least one team adopts configuration and baseline workflows |
| 3B | Analyze indirect Spring instrumentation | A demonstrated real-repository need justifies proxy/AOP complexity |
| 4 | Analyze cross-service diagnostic gaps | A design partner, sponsor, or paying pilot commits to validation |

## Phase 1 — Alpha technical-value validation

Alpha 1 scope:

- four-module Java 25 build;
- Maven and Gradle projects, including multi-module builds, per scan;
- direct REST, Kafka-listener, and scheduled entrypoints;
- bounded path-aware local-flow analysis;
- six deterministic rules;
- Markdown and versioned JSON reports;
- stable fingerprints, related flows, confidence, and boundaries;
- validation against three real repositories.

### Technical gate

- at least 10 reviewable findings across 3 repositories;
- at least 80% judged valid by maintainers;
- at most 20% judged noise;
- at least 3 valid issues not previously noticed by those teams;
- scan time and memory recorded for every repository;
- no unexplained nondeterminism across repeated scans.

### Interest gate

The meaningful signal is behavioral. “Interesting” is weak; a request to scan another service or run the scanner again is strong.

If findings are obvious, repetitive, already equivalently covered by existing tools, or treated as noise, improve precision or stop Phase 1. Do not compensate by adding AOP, dashboards, LLM features, cross-service analysis, or a blocking CI integration.

## Phase 2 — Richer deterministic analysis

Candidate work, selected only from real validation evidence:

- log calls that omit the original throwable;
- duplicate or contradictory diagnostic signals;
- sensitive or complete payload logging;
- metrics created in loops or with dynamic names;
- fallback paths and scheduled/Kafka-consumer suppression;
- technical versus business metric patterns;
- SARIF output;
- optional explanations for already deterministic findings.

An LLM may summarize or explain a finding, but it never creates the authoritative finding or decides whether CI passes.

## Phase 3A — Configuration, baseline, and CI

- `diagscope.yml` with rule severity, project-specific sensitive fields, and ignored paths;
- a baseline so legacy repositories fail only on new findings;
- stable schema compatibility and upgrade policy;
- `diagscope-maven-plugin`;
- Spring, logging, Micrometer, and OpenTelemetry configuration context;
- optional comparison with provided dashboard metadata.

Absence of in-repository configuration is not automatically a failure because configuration may be injected externally.

## Phase 3B — Spring AOP and proxies

- discover indirect instrumentation through `@Aspect`, `@Around`, and related constructs;
- identify likely missed advice caused by self-invocation, private/final methods, or unmanaged classes;
- model confidence around proxy and bean-resolution uncertainty.

This phase has high demonstration value and high semantic cost. It begins only after direct source analysis is stable and a real repository proves the need.

## Phase 4 — Cross-service flows

- derive service topology primarily from observed OpenTelemetry traces, even when partial;
- correlate static findings with actual producer/consumer or request paths;
- identify distributed diagnostic blind spots;
- suggest runbook or dashboard improvements only from evidenced topology.

Static reconstruction alone is not a reliable source of truth for distributed Kafka and dynamic routing. Phase 4 requires a committed design partner, sponsor, or paying pilot.

## Explicit non-goals before their phase

- blocking CI without a baseline and policy model;
- broad rule count as a substitute for precision;
- native image work without measured startup pressure;
- global caches without proven invalidation and memory behavior;
- cross-service claims derived only from guessed static topology;
- AI authority over deterministic findings.
