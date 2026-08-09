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
                    "If conversion is intentional, throw a configured rollback exception or mark rollback-only."))
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
