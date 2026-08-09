# diagscope-javaparser

Java source-analysis adapter.

Responsibilities:

- discover sources under `src/main/java`;
- parse source files with JavaParser;
- map the AST into parser-neutral evidence owned by the core;
- index methods, types, and candidate implementations;
- resolve Java-local calls on a best-effort basis.

No JavaParser type may cross the output port into `diagscope-core`.
