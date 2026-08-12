# Capability model

What DiagScope analyzes, what it treats as unknown, and what is knowingly out of reach. The purpose
of this document is to make the tool's blind spots explicit before a team makes it blocking in CI.

DiagScope reads source only. It never compiles the project, never executes a build, never starts the
application, and never reads runtime configuration from outside the repository.

## Languages and builds

| Input | Support |
| --- | --- |
| Java production sources (JavaParser adapter) | analyzed |
| Kotlin/JVM production sources (Kotlin PSI adapter) | analyzed |
| Mixed Java/Kotlin modules, including cross-language call relinking | analyzed |
| Maven and Gradle projects, single and multi-module | analyzed |
| Conventional roots `src/main/java`, `src/main/kotlin` | discovered automatically |
| Literal Gradle `sourceSets.main`, Maven `build-helper`, Kotlin Maven plugin `sourceDirs` | discovered automatically |
| Dynamically computed source roots | only through explicit `--source-root` |
| Dependency symbols | only through explicit `--classpath` (caller-declared JARs/class directories) |
| Test sources, generated sources, bytecode-only modules, Groovy/Scala/other JVM languages | not analyzed |
| Android resource/manifest wiring, annotation-processor output not present in source | not analyzed |

## Frameworks and constructs recognized

**Entrypoints** — Spring MVC/WebFlux handlers; Quarkus REST JAX-RS resources with a class-level
`@Path` and a standard method-level HTTP annotation (`@GET`, `@POST`, `@PUT`, `@PATCH`, `@DELETE`,
`@HEAD`, or `@OPTIONS`); Spring and Quarkus scheduled methods; Kafka listeners including class-level
`@KafkaListener` with `@KafkaHandler`/`@DltHandler`; SmallRye Reactive Messaging consumers with
method-level `@Incoming`; async methods; and any additional method-level annotation declared under
`customEntrypointAnnotations` in `diagscope.yml`. Inherited and recursively meta-annotated variants
are resolved in both languages.

**Logging** — SLF4J/Log4j2/JUL-shaped logger calls, plus custom logger types declared in
`diagscope.yml`; MDC put/remove/clear; `printStackTrace` and `System.out`/`System.err`.

**Metrics and tracing** — Micrometer `Counter`, `Timer`, `Gauge`, `DistributionSummary`, tag
construction, meter naming, and `@Observed`, `@Timed`, `@Counted`, `@NewSpan`, `@WithSpan`,
`@ContinueSpan` as positive instrumentation evidence.

**Messaging** — Kafka producer sends and result handling, producer listeners, consumer error
propagation, and manual `Acknowledgment` handling.

**Data access** — `@Transactional` attributes including propagation, self-invocation, JDBC
`Connection`/`PreparedStatement`/`ResultSet` lifecycles, try-with-resources and `finally` guarding,
`JdbcTemplate` connection escape, and manually created `EntityManager` instances.

**Resilience and async** — `CompletableFuture`, executor submissions, `@Async`, retry and fallback
constructs, and HTTP client error handling for `RestTemplate`, `WebClient`, and `HttpClient`.

**Spring AOP** — `@Aspect` advice, AspectJ pointcut expressions that are decidable from source,
self-invocation proxy bypass, non-proxyable targets, and unmanaged advice targets.

**Quarkus boundary** — REST routes are derived from source `@Path` values only. Quarkus HTTP root
path configuration, `@ApplicationPath`, custom `@HttpMethod` annotations, and Reactive Messaging
`@Outgoing` channels are not yet modeled. `@Incoming` creates a framework-neutral
`REACTIVE_MESSAGE` flow: DiagScope reports its declared logical channel but does not infer its
connector as Kafka. Mutiny coverage is limited to syntax-visible `onFailure()` recovery operators
and one-callback `subscribe().with(...)` subscriptions; cancellation, back-pressure, retries, and
context propagation remain out of reach.
Spring proxy/AOP rules do not claim CDI/ArC interceptor semantics.

## Resolution model

Call paths are resolved from source facts only: declared types, transitive interfaces, inherited and
default methods, single-implementation interfaces, constructor/injected/parameter/chained receiver
mapping, and source-decidable overloads including varargs, Kotlin defaults exposed by
`@JvmOverloads`, and substitutable generics.

Every path carries the confidence of its weakest inference. Where a receiver, overload, or bean is
not decidable from source, the path ends at a **terminal boundary** and the finding's confidence is
lowered instead of the path being guessed.

## Knowingly out of reach

- runtime bean graphs, conditional configuration, profiles, and property-driven wiring;
- reflection, dynamic proxies created outside recognized Spring constructs, service loaders,
  bytecode weaving, and code generated at build time but absent from source;
- cross-service, cross-process, or trace-level flows;
- library-internal behavior beyond the signatures visible on the declared classpath;
- semantic judgement of whether a swallowed failure is intentional — that is a maintainer decision,
  which is why waivers require a written reason;
- any assertion that missing local configuration means missing instrumentation: absent logging,
  Micrometer, tracing, or Spring configuration can only lower confidence, never raise it.

## How gaps are recorded

A construct that DiagScope cannot decide appears as a terminal boundary with an explicit resolution
reason in `result.json`, in the Markdown call paths, and in the HTML flow drill-down. Missing
coverage discovered during real scans is filed against the relevant rule family and reviewed with
[VALIDATION_MATRIX.md](VALIDATION_MATRIX.md) before new inference is added.
