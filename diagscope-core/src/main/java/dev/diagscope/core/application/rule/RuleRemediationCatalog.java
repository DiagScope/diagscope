package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Finding;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Copy-ready remediations only for rules whose safe shape is deterministic. */
public final class RuleRemediationCatalog {
    private static final String REVIEW = "Adapt names and domain fields, then review the change in context.";

    private static final Map<String, Snippets> SNIPPETS = Map.ofEntries(
            Map.entry(PrintStackTraceRule.ID, snippets(
                    "logger.error(\"Operation failed for {}\", operationId, exception);",
                    "logger.error(\"Operation failed for {}\", operationId, exception)",
                    "Pass the Throwable as the final logging argument so the stack trace is retained.")),
            Map.entry(SystemOutputRule.ID, snippets(
                    "logger.info(\"Operation completed for {}\", operationId);",
                    "logger.info(\"Operation completed for {}\", operationId)",
                    "Use the application's configured logger and a stable, structured message.")),
            Map.entry(LogWithoutThrowableRule.ID, snippets(
                    "logger.error(\"Operation failed for {}\", operationId, exception);",
                    "logger.error(\"Operation failed for {}\", operationId, exception)",
                    "Keep the caught Throwable as the final argument; do not interpolate it into the message.")),
            Map.entry(IgnoredKafkaSendResultRule.ID, snippets(
                    "kafkaTemplate.send(topic, payload).whenComplete((result, error) -> {\n"
                            + "    if (error != null) logger.error(\"Kafka send failed for {}\", messageId, error);\n"
                            + "});",
                    "kafkaTemplate.send(topic, payload).whenComplete { _, error ->\n"
                            + "    if (error != null) logger.error(\"Kafka send failed for {}\", messageId, error)\n"
                            + "}",
                    "A ProducerListener configured centrally is also valid; avoid duplicate callbacks.")),
            Map.entry(AsyncResultUnobservedRule.ID, snippets(
                    "future.whenComplete((value, error) -> {\n"
                            + "    if (error != null) logger.error(\"Async operation failed for {}\", operationId, error);\n"
                            + "});",
                    "future.whenComplete { _, error ->\n"
                            + "    if (error != null) logger.error(\"Async operation failed for {}\", operationId, error)\n"
                            + "}",
                    "Return or compose the future when the caller owns completion handling.")),
            Map.entry(JdbcResourceLeakRule.ID, snippets(
                    "try (var connection = dataSource.getConnection();\n"
                            + "     var statement = connection.prepareStatement(sql);\n"
                            + "     var rows = statement.executeQuery()) {\n"
                            + "    // consume rows\n"
                            + "}",
                    "dataSource.connection.use { connection ->\n"
                            + "    connection.prepareStatement(sql).use { statement ->\n"
                            + "        statement.executeQuery().use { rows -> /* consume rows */ }\n"
                            + "    }\n"
                            + "}",
                    "Do not close framework-managed connections that your framework explicitly owns.")),
            Map.entry(EntityManagerLeakRule.ID, snippets(
                    "EntityManager entityManager = entityManagerFactory.createEntityManager();\n"
                            + "try {\n"
                            + "    // use entityManager\n"
                            + "} finally {\n"
                            + "    entityManager.close();\n"
                            + "}",
                    "val entityManager = entityManagerFactory.createEntityManager()\n"
                            + "try {\n"
                            + "    // use entityManager\n"
                            + "} finally {\n"
                            + "    entityManager.close()\n"
                            + "}",
                    "Do not close a container-managed EntityManager injected with @PersistenceContext.")),
            Map.entry(MdcContextLostRule.ID, snippets(
                    "var context = MDC.getCopyOfContextMap();\n"
                            + "executor.execute(() -> {\n"
                            + "    try {\n"
                            + "        if (context == null) MDC.clear(); else MDC.setContextMap(context);\n"
                            + "        task.run();\n"
                            + "    }\n"
                            + "    finally { MDC.clear(); }\n"
                            + "});",
                    "val context = MDC.getCopyOfContextMap()\n"
                            + "executor.execute {\n"
                            + "    try {\n"
                            + "        if (context == null) MDC.clear() else MDC.setContextMap(context)\n"
                            + "        task.run()\n"
                            + "    }\n"
                            + "    finally { MDC.clear() }\n"
                            + "}",
                    "Prefer the framework's TaskDecorator/context-propagation facility when one is configured.")),
            Map.entry(TransactionalRollbackSuppressedRule.ID, snippets(
                    "catch (RuntimeException exception) {\n"
                            + "    logger.error(\"Transactional operation failed for {}\", operationId, exception);\n"
                            + "    throw exception;\n"
                            + "}",
                    "catch (exception: RuntimeException) {\n"
                            + "    logger.error(\"Transactional operation failed for {}\", operationId, exception)\n"
                            + "    throw exception\n"
                            + "}",
                    "If conversion is intentional, throw a configured rollback exception or mark rollback-only.")),
            Map.entry(SilentCatchRule.ID, snippets(
                    "catch (SomeException exception) {\n"
                            + "    logger.error(\"Operation failed for {}\", operationId, exception);\n"
                            + "    throw new ServiceException(\"Operation failed\", exception);\n"
                            + "}",
                    "catch (exception: SomeException) {\n"
                            + "    logger.error(\"Operation failed for {}\", operationId, exception)\n"
                            + "    throw ServiceException(\"Operation failed\", exception)\n"
                            + "}",
                    "At a boundary where propagation is not possible, log and record a metric; never leave the block empty.")),
            Map.entry(LockNotReleasedRule.ID, snippets(
                    "lock.lock();\n"
                            + "try {\n"
                            + "    // critical section\n"
                            + "} finally {\n"
                            + "    lock.unlock();\n"
                            + "}",
                    "lock.lock()\n"
                            + "try {\n"
                            + "    // critical section\n"
                            + "} finally {\n"
                            + "    lock.unlock()\n"
                            + "}",
                    "Always acquire the lock immediately before try {} so the finally block is guaranteed to run.")),
            Map.entry(OptionalGetWithoutCheckRule.ID, snippets(
                    "// Instead of optional.get():\n"
                            + "T value = optional.orElseThrow(() ->\n"
                            + "    new EntityNotFoundException(\"Entity not found for id: \" + id));",
                    "// Instead of optional.get():\n"
                            + "val value = optional.orElseThrow {\n"
                            + "    EntityNotFoundException(\"Entity not found for id: $id\")\n"
                            + "}",
                    "Use orElseThrow() so the exception carries a meaningful message about what was missing.")),
            Map.entry(OptionalOrElseNullRule.ID, snippets(
                    "// Instead of optional.orElse(null):\n"
                            + "T value = optional.orElseThrow(() ->\n"
                            + "    new EntityNotFoundException(\"Missing value for \" + key));\n"
                            + "// Or if null interop is truly required:\n"
                            + "T nullable = optional.orElse(null); // diagscope:ignore OPTIONAL_OR_ELSE_NULL -- legacy API requires nullable return",
                    "// Instead of optional.orElse(null):\n"
                            + "val value = optional.orElseThrow {\n"
                            + "    EntityNotFoundException(\"Missing value for $key\")\n"
                            + "}",
                    "Prefer orElseThrow() or orElse(defaultValue). If null is truly required for a legacy API, suppress with an inline comment.")),
            Map.entry(MapGetDereferencedWithoutCheckRule.ID, snippets(
                    "// Instead of map.get(key).method():\n"
                            + "T value = map.getOrDefault(key, defaultValue);\n"
                            + "// Or guard explicitly:\n"
                            + "T value = Objects.requireNonNull(map.get(key),\n"
                            + "    () -> \"Missing entry for key: \" + key);",
                    "// Instead of map[key]!!.method() or map.get(key)!!.method():\n"
                            + "val value = map.getOrDefault(key, defaultValue)\n"
                            + "// Or use Kotlin's getOrElse:\n"
                            + "val value = map.getOrElse(key) {\n"
                            + "    throw IllegalStateException(\"Missing entry for key: $key\")\n"
                            + "}",
                    "Use getOrDefault(), computeIfAbsent(), or an explicit null check before dereferencing.")),
            Map.entry(TransactionIsolationDangerousRule.ID, snippets(
                    "// Remove the dangerous isolation override:\n"
                            + "@Transactional  // uses READ_COMMITTED by default\n"
                            + "public Result query() { ... }",
                    "// Remove the dangerous isolation override:\n"
                            + "@Transactional  // uses READ_COMMITTED by default\n"
                            + "fun query(): Result { ... }",
                    "READ_COMMITTED (the database default) prevents dirty reads. Only override isolation when you have a documented, reviewed reason.")),
            Map.entry(RequiresNewInLoopRule.ID, snippets(
                    "// Collect work outside the loop, then call once:\n"
                            + "List<Item> items = buildItems(inputs);\n"
                            + "processBatch(items);  // REQUIRES_NEW runs once\n\n"
                            + "// Or use saveAll() which batches internally:\n"
                            + "repository.saveAll(buildEntities(inputs));",
                    "// Collect work outside the loop, then call once:\n"
                            + "val items = buildItems(inputs)\n"
                            + "processBatch(items)  // REQUIRES_NEW runs once\n\n"
                            + "// Or use saveAll() which batches internally:\n"
                            + "repository.saveAll(buildEntities(inputs))",
                    "Each REQUIRES_NEW call suspends the outer transaction and opens a new connection. Batch outside the loop.")),
            Map.entry(JpaBatchLoopWithoutFlushClearRule.ID, snippets(
                    "int batchSize = 50;\n"
                            + "for (int i = 0; i < items.size(); i++) {\n"
                            + "    entityManager.persist(toEntity(items.get(i)));\n"
                            + "    if (i % batchSize == 0) {\n"
                            + "        entityManager.flush();\n"
                            + "        entityManager.clear();\n"
                            + "    }\n"
                            + "}",
                    "val batchSize = 50\n"
                            + "items.forEachIndexed { i, item ->\n"
                            + "    entityManager.persist(toEntity(item))\n"
                            + "    if (i % batchSize == 0) {\n"
                            + "        entityManager.flush()\n"
                            + "        entityManager.clear()\n"
                            + "    }\n"
                            + "}",
                    "Match batchSize to hibernate.jdbc.batch_size. Alternatively use repository.saveAll() which handles batching automatically.")),
            Map.entry(BulkOperationInLoopRule.ID, snippets(
                    "// Instead of save() in a loop:\n"
                            + "repository.saveAll(entities);",
                    "// Instead of save() in a loop:\n"
                            + "repository.saveAll(entities)",
                    "Use saveAll() / deleteAllById() to collapse N round-trips into one. For JPA, also set spring.jpa.properties.hibernate.jdbc.batch_size.")),
            Map.entry(KafkaDeadLetterNotConfiguredRule.ID, snippets(
                    "@Bean\n"
                            + "public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {\n"
                            + "    var recoverer = new DeadLetterPublishingRecoverer(template);\n"
                            + "    var backoff = new ExponentialBackOff(1000L, 2.0);\n"
                            + "    backoff.setMaxElapsedTime(30_000L);\n"
                            + "    return new DefaultErrorHandler(recoverer, backoff);\n"
                            + "}",
                    "@Bean\n"
                            + "fun errorHandler(template: KafkaTemplate<String, Any>): DefaultErrorHandler {\n"
                            + "    val recoverer = DeadLetterPublishingRecoverer(template)\n"
                            + "    val backoff = ExponentialBackOff(1000L, 2.0).apply {\n"
                            + "        maxElapsedTime = 30_000L\n"
                            + "    }\n"
                            + "    return DefaultErrorHandler(recoverer, backoff)\n"
                            + "}",
                    "Register the DefaultErrorHandler as a @Bean so all listeners inherit it without per-listener errorHandler attributes.")),
            Map.entry(SpanNotClosedRule.ID, snippets(
                    "Span span = tracer.nextSpan().name(\"operation\").start();\n"
                            + "try (var scope = tracer.withSpan(span)) {\n"
                            + "    // work\n"
                            + "} catch (Exception e) {\n"
                            + "    span.error(e);\n"
                            + "    throw e;\n"
                            + "} finally {\n"
                            + "    span.end();\n"
                            + "}",
                    "val span = tracer.nextSpan().name(\"operation\").start()\n"
                            + "tracer.withSpan(span).use {\n"
                            + "    try {\n"
                            + "        // work\n"
                            + "    } catch (e: Exception) {\n"
                            + "        span.error(e)\n"
                            + "        throw e\n"
                            + "    } finally {\n"
                            + "        span.end()\n"
                            + "    }\n"
                            + "}",
                    "Always end() the span in a finally block. Use try-with-resources on the Scope to avoid scope leaks."))
    );

    private RuleRemediationCatalog() {
    }

    public static Optional<Remediation> forFinding(Finding finding) {
        Objects.requireNonNull(finding, "finding");
        Snippets snippets = SNIPPETS.get(finding.ruleId());
        if (snippets == null) return Optional.empty();
        boolean kotlin = Finding.normalizedPath(finding.location()).toLowerCase(java.util.Locale.ROOT).endsWith(".kt");
        return Optional.of(kotlin
                ? new Remediation("kotlin", snippets.kotlin(), snippets.note())
                : new Remediation("java", snippets.java(), snippets.note()));
    }

    private static Snippets snippets(String java, String kotlin, String note) {
        return new Snippets(java, kotlin, note + ' ' + REVIEW);
    }

    public record Remediation(String language, String snippet, String note) {
        public Remediation {
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(snippet, "snippet");
            Objects.requireNonNull(note, "note");
        }
    }

    private record Snippets(String java, String kotlin, String note) {
    }
}
