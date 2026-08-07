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
 * End-to-end coverage for the observability rules: every finding must come from a fixture method
 * that really loses evidence, and the correct variants of the same shape must stay silent.
 */
class ObservabilityRulesTest {

    @TempDir
    Path temp;

    @Test
    void reports_observability_evidence_loss() throws IOException {
        String json = scan();

        assertThat(json)
                .contains("LOG_WITHOUT_THROWABLE")
                .contains("GENERIC_EXCEPTION_MESSAGE")
                .contains("ASYNC_RESULT_UNOBSERVED")
                .contains("HTTP_CLIENT_ERROR_DISCARDED")
                .contains("SCHEDULED_TASK_SWALLOWS_FAILURE")
                .contains("RETRY_WITHOUT_DIAGNOSTICS")
                .contains("FALLBACK_HIDES_FAILURE")
                .contains("METRIC_CREATED_IN_LOOP")
                .contains("SENSITIVE_PAYLOAD_LOGGED");
    }

    @Test
    void keeps_the_correct_variants_silent() throws IOException {
        String json = scan();

        assertThat(methodsFor(json, "LOG_WITHOUT_THROWABLE"))
                .noneMatch(method -> method.endsWith("logFailureProperly"));
        assertThat(methodsFor(json, "ASYNC_RESULT_UNOBSERVED"))
                .noneMatch(method -> method.endsWith("submitObserved"));
        assertThat(methodsFor(json, "METRIC_CREATED_IN_LOOP"))
                .noneMatch(method -> method.endsWith("countOnce"));
        assertThat(methodsFor(json, "RETRY_WITHOUT_DIAGNOSTICS"))
                .noneMatch(method -> method.endsWith("fetchWithDiagnostics"));
        assertThat(methodsFor(json, "FALLBACK_HIDES_FAILURE"))
                .noneMatch(method -> method.endsWith("recoverDescription"));
        assertThat(methodsFor(json, "HTTP_CLIENT_ERROR_DISCARDED"))
                .noneMatch(method -> method.endsWith("callRemoteObserved"));
    }

    /** Declaring methods reported by one rule, read from the finding objects in the JSON report. */
    private static List<String> methodsFor(String json, String ruleId) {
        var methods = new ArrayList<String>();
        var matcher = Pattern.compile("\"ruleId\"\\s*:\\s*\"" + ruleId + "\".{0,4000}?\"method\"\\s*:\\s*\"([^\"]+)\"",
                Pattern.DOTALL).matcher(json);
        while (matcher.find()) {
            methods.add(matcher.group(1));
        }
        assertThat(methods).as("findings for %s", ruleId).isNotEmpty();
        return methods;
    }

    private String scan() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "observability-patterns");
        Path output = temp.resolve("out");
        int exitCode = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(), "--parallelism", "1");
        assertThat(exitCode).isZero();
        return Files.readString(output.resolve("result.json"), StandardCharsets.UTF_8);
    }
}
