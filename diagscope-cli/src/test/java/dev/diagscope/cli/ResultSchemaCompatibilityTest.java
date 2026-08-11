package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResultSchemaCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void current_result_preserves_the_previous_contract_and_satisfies_the_current_one() throws Exception {
        JsonNode previousContract = contract("1.0-alpha.1");
        JsonNode intermediateContract = contract("1.1-alpha.1");
        JsonNode legacyContract = contract("1.2-alpha.1");
        JsonNode currentContract = contract("1.3-alpha.1");

        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("out");
        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--parallelism", "1");
        assertThat(exit).isZero();

        JsonNode result = JSON.readTree(output.resolve("result.json").toFile());
        assertThat(result.path("schemaVersion").asText())
                .isEqualTo(currentContract.path("schemaVersion").asText());
        assertFields(result, previousContract.path("root"));
        assertFields(result.path("project"), previousContract.path("project"));
        assertFields(result.path("statistics"), previousContract.path("statistics"));
        assertThat(result.path("flows")).isNotEmpty();
        assertFields(result.path("flows").get(0), previousContract.path("flow"));
        assertThat(result.path("findings")).isNotEmpty();
        JsonNode finding = result.path("findings").get(0);
        assertFields(finding, previousContract.path("finding"));
        assertFields(finding.path("location"), previousContract.path("location"));

        assertFields(result, intermediateContract.path("root"));
        JsonNode configuration = result.path("configuration");
        assertFields(configuration, intermediateContract.path("configuration"));
        assertFields(configuration.path("projectPolicy"), intermediateContract.path("projectPolicy"));
        assertFields(configuration.path("scanScope"), intermediateContract.path("scanScope"));

        assertFields(result, legacyContract.path("root"));
        assertFields(result.path("groups"), legacyContract.path("groups"));
        assertFields(configuration.path("scanScope"), legacyContract.path("scanScope"));
        JsonNode flow = result.path("flows").get(0);
        assertFields(flow, legacyContract.path("flow"));
        assertFields(flow.path("diagnosticCoverage"), legacyContract.path("diagnosticCoverage"));

        assertFields(result, currentContract.path("root"));
        assertFields(configuration.path("scanScope"), currentContract.path("scanScope"));
        assertThat(result.path("ruleVersions").isObject()).isTrue();
        assertThat(result.path("ruleVersions").path("SILENT_CATCH").asText()).isNotBlank();
    }

    private static JsonNode contract(String version) throws Exception {
        try (InputStream input = ResultSchemaCompatibilityTest.class.getResourceAsStream(
                "/schema/result-contract-" + version + ".json")) {
            assertThat(input).isNotNull();
            return JSON.readTree(input);
        }
    }

    private static void assertFields(JsonNode object, JsonNode requiredFields) {
        assertThat(object.isObject()).isTrue();
        requiredFields.forEach(field -> assertThat(object.has(field.asText()))
                .as("required field %s", field.asText())
                .isTrue());
    }
}
