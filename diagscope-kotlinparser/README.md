# diagscope-kotlinparser

Syntax-first Kotlin/JVM source-analysis adapter backed by the embeddable Kotlin compiler PSI.

The adapter maps:

- named functions, classes, objects, annotations, and source locations;
- direct, inherited, and recursively meta-annotated REST, Kafka listener, and scheduled entrypoints;
- local calls, trailing-lambda arguments, and invocation-result usage;
- catch behavior, logging, rethrowing, benign fallback values, and suppression directives;
- constructor/property/parameter injection receivers and chained injected properties;
- Kotlin resource `use`, inherited/composed Spring proxy annotations, and Kotlin Spring all-open behavior;
- Micrometer tag and meter-name evidence with local type/provenance classification;
- syntax-decidable AspectJ pointcuts and inherited/composed advice targets;
- same-arity overload selection, default and vararg arity, generic identities, transitive interfaces,
  inherited methods, and interface default methods.

The analyzer produces only parser-neutral `diagscope-core` models. Kotlin PSI objects are discarded after each analysis session.

Every registered rule is exercised against Kotlin source by the parity and metric fixtures. Current
boundaries include complete type/classpath resolution, runtime-only or named AspectJ pointcuts,
cross-language default/vararg/generic substitution, and dynamically computed build configuration.
Kotlin parsing remains deterministic and sequential because PSI files share one compiler application
environment; parallelism stays gated on real profiling evidence.
