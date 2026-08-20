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
        JsonNode v10 = contract("1.0-alpha.1");
        JsonNode v11 = contract("1.1-alpha.1");
        JsonNode v12 = contract("1.2-alpha.1");
        JsonNode v13 = contract("1.3-alpha.1");
        JsonNode v14 = contract("1.4-alpha.1");
        JsonNode v15 = contract("1.5-alpha.1");

        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("out");
        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--parallelism", "1");
        assertThat(exit).isZero();

        JsonNode result = JSON.readTree(output.resolve("result.json").toFile());

        // ── schema version must match the latest contract ─────────────────────
        assertThat(result.path("schemaVersion").asText())
                .isEqualTo(v15.path("schemaVersion").asText());

        // ── 1.0-alpha.1: root, project, statistics, flow, finding, location ──
        assertFields(result, v10.path("root"));
        assertFields(result.path("project"), v10.path("project"));
        assertFields(result.path("statistics"), v10.path("statistics"));
        assertThat(result.path("flows")).isNotEmpty();
        assertFields(result.path("flows").get(0), v10.path("flow"));
        assertThat(result.path("findings")).isNotEmpty();
        JsonNode finding = result.path("findings").get(0);
        assertFields(finding, v10.path("finding"));
        assertFields(finding.path("location"), v10.path("location"));

        // ── 1.1-alpha.1: configuration, projectPolicy, scanScope ─────────────
        assertFields(result, v11.path("root"));
        JsonNode configuration = result.path("configuration");
        assertFields(configuration, v11.path("configuration"));
        assertFields(configuration.path("projectPolicy"), v11.path("projectPolicy"));
        assertFields(configuration.path("scanScope"), v11.path("scanScope"));

        // ── 1.2-alpha.1: groups, diagnosticCoverage, extended scanScope ───────
        assertFields(result, v12.path("root"));
        assertFields(result.path("groups"), v12.path("groups"));
        assertFields(configuration.path("scanScope"), v12.path("scanScope"));
        JsonNode flow = result.path("flows").get(0);
        assertFields(flow, v12.path("flow"));
        assertFields(flow.path("diagnosticCoverage"), v12.path("diagnosticCoverage"));

        // ── 1.3-alpha.1: ruleVersions ─────────────────────────────────────────
        assertFields(result, v13.path("root"));
        assertThat(result.path("ruleVersions").isObject()).isTrue();
        assertThat(result.path("ruleVersions").path("SILENT_CATCH").asText()).isNotBlank();

        // ── 1.4-alpha.1: scanScope additions ──────────────────────────────────
        assertFields(result, v14.path("root"));
        assertFields(configuration.path("scanScope"), v14.path("scanScope"));

        // ── 1.5-alpha.1: analysisCapabilities ────────────────────────────────
        assertFields(result, v15.path("root"));
        JsonNode caps = result.path("analysisCapabilities");
        assertThat(caps.isObject()).as("analysisCapabilities must be an object").isTrue();
        assertFields(caps, v15.path("analysisCapabilities"));

        JsonNode languages = caps.path("languages");
        assertThat(languages.isObject()).as("languages must be an object").isTrue();
        assertFields(languages, v15.path("analysisCapabilitiesLanguages"));
        assertThat(languages.path("java").asText())
                .as("java capability level must be a non-blank string")
                .isNotBlank();
        assertThat(languages.path("kotlin").asText())
                .as("kotlin capability level must be a non-blank string")
                .isNotBlank();

        JsonNode frameworks = caps.path("frameworks");
        assertThat(frameworks.isObject()).as("frameworks must be an object").isTrue();
        assertFields(frameworks, v15.path("analysisCapabilitiesFrameworks"));
        assertThat(frameworks.path("spring").asText()).isEqualTo("SUPPORTED");

        JsonNode features = caps.path("features");
        assertThat(features.isObject()).as("features must be an object").isTrue();
        assertFields(features, v15.path("analysisCapabilitiesFeatures"));
        assertThat(features.path("springAop").asText()).isEqualTo("PARTIAL");
        assertThat(features.path("kotlinDependencyResolution").asText())
                .isIn("SOURCE_FIRST", "NOT_APPLICABLE");
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
