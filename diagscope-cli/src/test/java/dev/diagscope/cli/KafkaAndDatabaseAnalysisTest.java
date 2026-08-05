package dev.diagscope.cli;

import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for Kafka listener shapes and database access: both lose evidence quietly,
 * either by committing an offset for a record that failed or by committing a transaction that
 * never completed.
 */
class KafkaAndDatabaseAnalysisTest {

    @TempDir
    Path temp;

    @Test
    void reports_kafka_and_database_evidence_loss() throws IOException {
        String json = scan();

        // class level @KafkaListener + @KafkaHandler and topicPattern listeners are entrypoints
        assertThat(json).contains("Kafka topic=orders")
                .contains("Kafka topicPattern=")
                .contains("example.pipeline.OrderEventsListener.onOrder");

        assertThat(json).contains("KAFKA_ACK_NOT_INVOKED")
                .contains("KAFKA_LISTENER_ERROR_NOT_PROPAGATED")
                .contains("TX_ROLLBACK_SUPPRESSED")
                .contains("JDBC_RESOURCE_NOT_CLOSED");

        assertThat(json).contains("DB_RESOURCE_CLOSE_NOT_GUARDED")
                .contains("JPA_ENTITY_MANAGER_NOT_CLOSED")
                .contains("JDBC_TEMPLATE_CONNECTION_ESCAPE");

        // try-with-resources and finally-guarded releases are correct and must not be reported
        assertThat(findingMethods(json))
                .noneMatch(method -> method.endsWith("insertSafely"))
                .noneMatch(method -> method.endsWith("loadOrderSafely"));
    }

    /** Declaring methods of every finding, read from the {@code details.method} entry. */
    private static java.util.List<String> findingMethods(String json) {
        var methods = new java.util.ArrayList<String>();
        var matcher = java.util.regex.Pattern
                .compile("\"method\" : \"([^\"]+)\"").matcher(json);
        while (matcher.find()) {
            methods.add(matcher.group(1));
        }
        assertThat(methods).isNotEmpty();
        return methods;
    }

    private String scan() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "kafka-db-patterns");
        Path output = temp.resolve("out");
        int exitCode = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(), "--parallelism", "1");
        assertThat(exitCode).isZero();
        return Files.readString(output.resolve("result.json"), StandardCharsets.UTF_8);
    }
}
