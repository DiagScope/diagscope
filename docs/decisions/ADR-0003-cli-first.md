# ADR-0003: CLI-first distribution

## Status

Accepted.

## Context

The prototype must be easy to run against repositories without modifying their builds or deploying a service.

## Decision

The first distribution is an executable fat JAR exposed through a CLI. The Maven plugin is postponed until Phase 3.

## Consequences

- repositories can be analyzed without changing their POM;
- the tool can run locally and in any CI environment;
- the CLI is an adapter over the same reusable core;
- Maven lifecycle integration is intentionally deferred.
