# ADR-0002: Java 25 baseline

## Status

Superseded by [ADR-0009](ADR-0009-java-17-runtime-baseline.md): the artifacts now target Java 17 and are built with JDK 21 or newer.

## Context

DiagScope is a new project and does not need compatibility with older Java runtimes during the prototype phase.

## Decision

DiagScope compiles and runs on Java 25 without preview features.

## Consequences

- the project can use current stable language and runtime capabilities;
- preview-API migration risk is avoided;
- contributors and CI must provide JDK 25;
- compatibility with older runtimes is not an Alpha 1 goal.
