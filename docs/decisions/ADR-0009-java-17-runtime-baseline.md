# ADR-0009: Java 17 runtime baseline, built with JDK 21 or newer

## Status

Accepted and implemented. Supersedes [ADR-0002](ADR-0002-java-25.md).

## Context

ADR-0002 put the whole project on Java 25 because compatibility with older runtimes was not an Alpha 1 goal. That was
reasonable for a command-line tool installed by Homebrew, which brings its own JDK. It is a barrier for the two uses
that run DiagScope inside someone else's environment:

- the Maven plugin runs in Maven's JVM, so a team must run Maven on the JDK the plugin needs; hardly any team builds on
  25 today;
- an assistant hook or a CI job that already has a JDK has to install another.

Nothing in the code needs Java 25. Two things were checked rather than assumed:

- A clean build with `--release 17` failed only on `List.getFirst()` and `getLast()` (Java 21 `SequencedCollection`),
  in 17 files. An earlier claim that the code already compiled for 17 was wrong: the check reused classes compiled for
  21 and recompiled nothing.
- After replacing them, all 556 tests passed on a real JDK 17 runtime, the shaded CLI jar scanned Java and Kotlin
  projects on 17 with the same findings as before, and the Maven plugin ran inside a Maven started on JDK 17.

## Decision

1. **The artifacts target Java 17** (`maven.compiler.release` 17): every module, the shaded CLI jar and the Maven
   plugin run on 17 or newer.
2. **The build needs JDK 21 or newer**, not 17. The enforcer rule changes from `[25,26)` to `[21,)` because the
   checkstyle version in use is compiled for 21. This separates "what runs DiagScope" from "what builds it".
3. **CI proves the baseline instead of trusting it.** The build runs on JDK 25 with `--release 17`, and the tests run on
   a forked JVM of 17, 21 and 25 (`-Djvm`), so a newer API fails the 17 leg and a regression on a newer JVM fails its
   own. A second job starts Maven itself on 17 and runs the plugin against a sample project
   (`scripts/ci/maven-plugin-smoke.sh`).
4. **Release builds are unchanged** (JDK 25 builds the jar); only the class file version of the result drops.
5. **No Java 18 to 25 API in main or test code.** `--release 17` enforces it at compile time. The two replacements made
   were `get(0)` and `get(size() - 1)`.

## Not decided here

- **The Homebrew formula** still depends on `openjdk@25`. It works, and it can be relaxed to a lower JDK once a release
  built on this baseline is published.
- **Java 11.** Records, sealed types and text blocks are used throughout, which need 17.
- **Features that need a newer JDK.** If a rule ever needs a 21+ API, it is a new ADR and the legs above show what breaks.

## Consequences

- Maven plugin users and CI jobs can use the JDK they already have, from 17.
- The user-facing install documentation (Homebrew, releases, site) keeps saying Java 25 until a release built on this
  baseline ships; the published 0.1.4 still requires it.
- Contributors need JDK 21 or newer to build, and can run the tests against 17 with `-Djvm=/path/to/java17`.
- Two extra CI jobs per push; the matrix is the price of the guarantee.
