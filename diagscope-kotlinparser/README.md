# diagscope-kotlinparser

Syntax-first Kotlin/JVM source-analysis adapter backed by the embeddable Kotlin compiler PSI.

The initial adapter maps:

- named functions, classes, objects, annotations, and source locations;
- REST, Kafka listener, and scheduled entrypoints;
- local calls and invocation-result usage;
- catch behavior, logging, rethrowing, benign fallback values, and suppression directives;
- Kotlin resource `use`, Spring-managed types, proxy annotations, and Kotlin Spring all-open behavior;
- Micrometer tag and meter-name evidence with local type/provenance classification;
- syntax-decidable AspectJ pointcuts and direct single-implementation interfaces.

The analyzer produces only parser-neutral `diagscope-core` models. Kotlin PSI objects are discarded after each analysis session.

Current boundaries include complete type/classpath resolution, runtime-only or named AspectJ pointcuts, and full cross-language overload/vararg resolution. Kotlin parsing is deterministic and sequential because PSI files share one compiler application environment.
