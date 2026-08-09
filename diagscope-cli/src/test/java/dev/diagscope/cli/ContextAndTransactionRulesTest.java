package dev.diagscope.cli;

import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the logging-context, duplicate-signal and transaction-boundary rules.
 * Each rule is checked with a positive case, a correct variant that must stay silent, and a
 * near-boundary variant (plain REQUIRED propagation, a wrap that keeps the cause).
 */
class ContextAndTransactionRulesTest {

    @TempDir
    Path temp;

    @Test
    void reports_lost_context_duplicated_records_and_missing_transaction_boundaries() throws IOException {
        String json = scan();

        assertThat(json)
                .contains("MDC_CONTEXT_LOST")
                .contains("DUPLICATE_DIAGNOSTIC_SIGNAL")
                .contains("TX_PROPAGATION_MISMATCH");

        assertThat(methodsFor(json, "MDC_CONTEXT_LOST"))
                .anyMatch(method -> method.contains(".dispatchWithoutContext("))
                .anyMatch(method -> method.contains(".leakContext("))
                .noneMatch(method -> method.contains(".dispatchWithContext("));

        assertThat(methodsFor(json, "DUPLICATE_DIAGNOSTIC_SIGNAL"))
                .anyMatch(method -> method.contains(".logAndRethrow("))
                .anyMatch(method -> method.contains(".logAndWrapWithoutCause("))
                .noneMatch(method -> method.contains(".wrapWithCause("));
    }

    @Test
    void reports_the_transactional_methods_whose_declared_boundary_never_happens() throws IOException {
        String json = scan();

        List<String> transactional = valuesFor(json, "TX_PROPAGATION_MISMATCH", "transactionalMethod");
        assertThat(transactional)
                .anyMatch(method -> method.contains(".auditInNewTransaction("))
                .anyMatch(method -> method.contains(".requireExistingTransaction("))
                .noneMatch(method -> method.contains(".saveInSameTransaction("));
    }

    @Test
    void treats_timed_methods_as_existing_instrumentation() throws IOException {
        String json = scan();

        assertThat(methodsFor(json, "RETRY_WITHOUT_DIAGNOSTICS"))
                .anyMatch(method -> method.contains(".loadQuietly("))
                .noneMatch(method -> method.contains(".loadTimed("));
    }

    private static List<String> methodsFor(String json, String ruleId) {
        return valuesFor(json, ruleId, "method");
    }

    private static List<String> valuesFor(String json, String ruleId, String evidenceKey) {
        var values = new ArrayList<String>();
        var matcher = Pattern.compile("\"ruleId\"\\s*:\\s*\"" + ruleId + "\".{0,4000}?\""
                + evidenceKey + "\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL).matcher(json);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertThat(values).as("%s of findings for %s", evidenceKey, ruleId).isNotEmpty();
        return values;
    }

    private String scan() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "context-and-transactions");
        Path output = temp.resolve("out");
        int exitCode = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(), "--parallelism", "1");
        assertThat(exitCode).isZero();
        return Files.readString(output.resolve("result.json"), StandardCharsets.UTF_8);
    }
}
