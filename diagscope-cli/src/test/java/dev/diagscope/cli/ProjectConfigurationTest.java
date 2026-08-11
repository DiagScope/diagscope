package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectConfigurationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void applies_the_effective_policy_to_java_and_kotlin_analysis() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "configuration-policy");
        Path output = temp.resolve("out");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--parallelism", "1");

        assertThat(exit).isZero();
        JsonNode result = JSON.readTree(output.resolve("result.json").toFile());
        assertThat(result.path("schemaVersion").asText()).isEqualTo("1.3-alpha.1");
        assertThat(result.path("statistics").path("sourceFiles").asInt()).isEqualTo(2);
        assertThat(result.path("configuration").path("scanScope").path("configurationFile").asText())
                .isEqualTo("diagscope.yml");
        assertThat(result.path("configuration").path("projectPolicy").path("disabledRules"))
                .anySatisfy(rule -> assertThat(rule.asText()).isEqualTo("LOG_WITHOUT_THROWABLE"));

        assertThat(result.path("flows")).hasSize(2);
        assertThat(result.path("flows").findValuesAsText("method"))
                .anyMatch(method -> method.contains("JavaPolicyEndpoint.execute"))
                .anyMatch(method -> method.contains("KotlinPolicyJob.run"))
                .noneMatch(method -> method.contains("GeneratedEndpoint"));

        assertThat(result.path("findings").findValuesAsText("ruleId"))
                .contains("SENSITIVE_PAYLOAD_LOGGED")
                .doesNotContain("LOG_WITHOUT_THROWABLE", "SILENT_CATCH");
        result.path("findings").forEach(finding -> {
            if ("SENSITIVE_PAYLOAD_LOGGED".equals(finding.path("ruleId").asText())) {
                assertThat(finding.path("severity").asText()).isEqualTo("WARNING");
            }
        });
        assertThat(result.path("findings").findValuesAsText("argument"))
                .contains("accountNumber", "secretAlias");
    }

    @Test
    void explicit_configuration_path_is_reported_and_overrides_auto_discovery() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "configuration-policy");
        Path policyDirectory = project.resolve("config");
        Files.createDirectories(policyDirectory);
        Files.move(project.resolve("diagscope.yml"), policyDirectory.resolve("team-policy.yml"));
        Path output = temp.resolve("out");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--parallelism", "1", "--config", "config/team-policy.yml");

        assertThat(exit).isZero();
        JsonNode result = JSON.readTree(output.resolve("result.json").toFile());
        assertThat(result.path("configuration").path("scanScope").path("configurationFile").asText())
                .isEqualTo("config/team-policy.yml");
    }

    @Test
    void configured_policy_and_findings_are_deterministic_across_repeated_scans() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "configuration-policy");
        Path firstOutput = temp.resolve("first");
        Path secondOutput = temp.resolve("second");

        int firstExit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", firstOutput.toString(),
                "--format", "JSON", "--parallelism", "1");
        int secondExit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", secondOutput.toString(),
                "--format", "JSON", "--parallelism", "1");

        assertThat(firstExit).isZero();
        assertThat(secondExit).isZero();
        JsonNode first = JSON.readTree(firstOutput.resolve("result.json").toFile());
        JsonNode second = JSON.readTree(secondOutput.resolve("result.json").toFile());
        assertThat(first.path("configuration")).isEqualTo(second.path("configuration"));
        assertThat(first.path("findings")).isEqualTo(second.path("findings"));
    }

    @Test
    void unknown_configuration_keys_are_rejected() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "configuration-policy");
        Files.writeString(project.resolve("diagscope.yml"), """
                schemaVersion: "1.0"
                unexpectedPolicy: true
                """);

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("out").toString(),
                "--format", "JSON", "--parallelism", "1");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void unknown_rules_are_rejected() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "configuration-policy");
        Files.writeString(project.resolve("diagscope.yml"), """
                schemaVersion: "1.0"
                rules:
                  MADE_UP_RULE:
                    enabled: false
                """);

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("out").toString(),
                "--format", "JSON", "--parallelism", "1");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void relative_configuration_path_cannot_escape_the_project() {
        Path project = FixtureCatalog.copyTo(temp, "configuration-policy");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", temp.resolve("out").toString(),
                "--format", "JSON", "--parallelism", "1", "--config", "../outside.yml");

        assertThat(exit).isEqualTo(2);
    }
}
