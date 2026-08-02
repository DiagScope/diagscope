# ADR-0001: Hexagonal architecture

## Status

Accepted.

## Context

The analysis rules must remain independent from JavaParser, command-line concerns, report serialization, and future integrations.

## Decision

The domain model and application service live in `diagscope-core`. Parsers, CLI commands, reporters, and future integrations are adapters behind explicit ports.

## Consequences

- the core can be tested without JavaParser or Picocli;
- new parsers and interfaces can be added without rewriting rules;
- adapter types cannot leak into core models;
- explicit mapping adds some code but keeps dependencies and performance behavior visible.
