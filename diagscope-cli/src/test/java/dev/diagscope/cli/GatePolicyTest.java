package dev.diagscope.cli;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GatePolicyTest {
    @TempDir
    Path temp;

    @Test
    void a_condition_requires_both_minimums_and_the_rule_filter() {
        var condition = new GatePolicy.Condition(Severity.WARNING, Confidence.HIGH, Set.of("A"));

        assertThat(condition.describe()).isEqualTo("severity >= WARNING, confidence >= HIGH, rules [A]");
        assertThat(GatePolicy.NONE.evaluate(java.util.List.of())).isEmpty();
        assertThat(new GatePolicy(java.util.List.of(), Map.of(Severity.ERROR, 0)).isEmpty()).isFalse();
    }

    @Test
    void negative_limits_are_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new GatePolicy(java.util.List.of(), Map.of(Severity.INFO, -1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
    }

    @Test
    void yaml_gate_fails_the_scan_and_the_cli_flag_overrides_it() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        write(project, """
                schemaVersion: "1.0"
                gate:
                  failOn:
                    - severity: INFO
                  profiles:
                    lenient:
                      maxFindings:
                        ERROR: 1000
                """);

        assertThat(scan(project)).isEqualTo(1);
        assertThat(scan(project, "--gate-profile", "lenient")).isZero();
        assertThat(scan(project, "--fail-on", "NONE")).isZero();
        assertThat(scan(project, "--fail-on", "INFO", "--gate-profile", "lenient")).isEqualTo(2);
        assertThat(scan(project, "--gate-profile", "missing")).isEqualTo(2);
    }

    @Test
    void confidence_and_rule_filters_narrow_what_breaks_the_build() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        // mixed-flow: ERROR/MEDIUM HIGH_CARDINALITY_METRIC_TAG, ERROR/HIGH SILENT_CATCH, 3 WARNINGs.
        gate(project, "failOn:\n    - severity: ERROR\n      confidence: HIGH\n");
        assertThat(scan(project)).isEqualTo(1);

        gate(project, "failOn:\n    - severity: ERROR\n      rules: [HIGH_CARDINALITY_METRIC_TAG]\n"
                + "      confidence: HIGH\n");
        assertThat(scan(project)).isZero();

        gate(project, "failOn:\n    - severity: INFO\n      rules: [SILENT_CATCH]\n");
        assertThat(scan(project)).isEqualTo(1);
    }

    @Test
    void fail_on_confidence_narrows_fail_on_and_cannot_stand_alone() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");

        // mixed-flow has ERROR/HIGH SILENT_CATCH, and ERROR/MEDIUM HIGH_CARDINALITY_METRIC_TAG.
        assertThat(scan(project, "--fail-on", "ERROR", "--fail-on-confidence", "HIGH")).isEqualTo(1);
        assertThat(scan(project, "--fail-on", "INFO", "--fail-on-confidence", "HIGH")).isEqualTo(1);
        // PaymentMetrics.java holds only the MEDIUM one: it breaks a plain ERROR gate and passes a HIGH one.
        String medium = "src/main/java/example/PaymentMetrics.java";
        assertThat(scan(project, "--fail-on", "ERROR", "--files", medium)).isEqualTo(1);
        assertThat(scan(project, "--fail-on", "ERROR", "--fail-on-confidence", "HIGH", "--files", medium)).isZero();
        assertThat(scan(project, "--fail-on-confidence", "HIGH")).isEqualTo(2);
        assertThat(scan(project, "--fail-on", "NONE", "--fail-on-confidence", "HIGH")).isZero();
    }

    @Test
    void max_findings_tolerates_up_to_the_limit() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");

        gate(project, "maxFindings:\n    WARNING: 3\n");
        assertThat(scan(project)).isZero();

        gate(project, "maxFindings:\n    WARNING: 2\n");
        assertThat(scan(project)).isEqualTo(1);
    }

    @Test
    void malformed_gates_are_rejected_like_the_rest_of_the_schema() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        for (String gate : new String[] {
                "gate:\n  failOn:\n    - confidence: HIGH\n",
                "gate:\n  failOn:\n    - severity: ERROR\n      rules: [NOT_A_RULE]\n",
                "gate:\n  maxFindings:\n    ERROR: -1\n",
                "gate:\n  unknown: 1\n"}) {
            write(project, "schemaVersion: \"1.0\"\n" + gate);
            assertThat(scan(project)).as(gate).isEqualTo(2);
        }
    }

    private int scan(Path project, String... extra) {
        var args = new java.util.ArrayList<>(java.util.List.of("scan", "--project", project.toString(),
                "--output", temp.resolve("out").toString(), "--format", "JSON", "--parallelism", "1"));
        args.addAll(java.util.List.of(extra));
        return DiagScopeMain.createCommandLine().execute(args.toArray(String[]::new));
    }

    private static void gate(Path project, String body) throws IOException {
        write(project, "schemaVersion: \"1.0\"\ngate:\n  " + body);
    }

    private static void write(Path project, String yaml) throws IOException {
        Files.writeString(project.resolve("diagscope.yml"), yaml);
    }
}
