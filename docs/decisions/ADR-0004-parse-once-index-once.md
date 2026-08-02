# ADR-0004: Parse once, index once

## Status

Accepted.

## Context

Repeated AST traversal and parsing would multiply CPU and allocation cost as rules are added.

## Decision

Each Java source file is parsed once per scan. The adapter maps the AST into reusable parser-neutral evidence and indexes. Rules consume those models instead of traversing the AST independently.

## Consequences

- rules reuse parsing and indexing work;
- memory behavior is more predictable;
- JavaParser remains outside the core;
- evidence-model design becomes an important compatibility boundary;
- retaining unnecessary AST data must be monitored with profiling.
