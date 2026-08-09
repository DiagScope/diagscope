# diagscope-javaparser

Java source-analysis adapter.

Responsibilities:

- discover Java sources from the shared conventional, build-declared, and explicit-root layout;
- parse source files with JavaParser, using source-first mode by default or source/JDK/caller-declared
  dependency solvers when an explicit classpath is provided;
- map the AST into parser-neutral evidence owned by the core;
- index methods, types, and candidate implementations;
- resolve typed overloads, transitive interfaces, inherited/default methods, receiver chains, and
  recursively composed/inherited entrypoint and advice annotations.

The adapter never invokes Maven or Gradle to build a classpath. Dependency JARs/classes must be
supplied explicitly by the caller, and a failed resolution remains a terminal boundary.

No JavaParser type may cross the output port into `diagscope-core`.
