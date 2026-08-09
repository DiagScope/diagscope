# diagscope-jvmanalysis

Shared infrastructure for JVM language adapters.

Responsibilities:

- detect conventional Maven and Gradle modules plus safe literal production roots from Gradle
  `sourceSets.main`, Maven `build-helper`, and Kotlin Maven `sourceDirs`;
- compose parser-neutral fragments produced by language-specific analyzers;
- deterministically merge methods, entrypoints, aspects, and parse failures;
- relink conservative Java-to-Kotlin and Kotlin-to-Java calls after all declarations are visible,
  using source-inferred argument types and callable shapes to distinguish overloads, `@JvmOverloads`
  defaults, varargs, and generic candidates;
- evaluate syntax-decidable AspectJ pointcuts consistently and apply advice across languages.

This module depends only on `diagscope-core`. It must not import JavaParser or Kotlin PSI types.
