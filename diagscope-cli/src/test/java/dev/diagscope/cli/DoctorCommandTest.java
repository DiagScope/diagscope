package dev.diagscope.cli;

import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DoctorCommandTest {

    @TempDir
    Path temp;

    // ── basic invocation ──────────────────────────────────────────────────────

    @Test
    void doctor_exits_zero_for_a_valid_maven_project() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.exitCode).isZero();
        assertThat(output.stdout).contains("DiagScope Doctor");
    }

    @Test
    void doctor_reports_runtime_java_and_diagscope_version() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.exitCode).isZero();
        assertThat(output.stdout).contains("Runtime");
        assertThat(output.stdout).contains("Java ");
        assertThat(output.stdout).contains("DiagScope ");
    }

    @Test
    void doctor_identifies_maven_build_system_and_module_count() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.exitCode).isZero();
        assertThat(output.stdout).contains("Project");
        assertThat(output.stdout).containsAnyOf("Maven single-module", "Maven multi-module");
        assertThat(output.stdout).contains("module");
    }

    @Test
    void doctor_counts_java_source_files() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.exitCode).isZero();
        assertThat(output.stdout).contains("Languages");
        assertThat(output.stdout).matches("(?s).*Java: \\d+ files?.*");
    }

    @Test
    void doctor_lists_source_roots_relative_to_project() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.exitCode).isZero();
        assertThat(output.stdout).contains("Sources");
        assertThat(output.stdout).contains("src/main/java");
    }

    @Test
    void doctor_warns_when_classpath_is_not_configured() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.stdout).contains("Resolution");
        assertThat(output.stdout).contains("Java local resolution");
        assertThat(output.stdout).contains("classpath not configured");
    }

    @Test
    void doctor_does_not_warn_about_classpath_when_supplied() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path dummyJar = temp.resolve("deps/lib.jar");
        Files.createDirectories(dummyJar.getParent());
        Files.createFile(dummyJar);
        var output = capture("doctor",
                "--project", project.toString(),
                "--classpath", dummyJar.toString());

        assertThat(output.stdout).contains("Java dependency classpath: 1 entry");
        assertThat(output.stdout).doesNotContain("classpath not configured");
    }

    @Test
    void doctor_shows_configuration_section_without_diagscope_yml() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.stdout).contains("Configuration");
        assertThat(output.stdout).containsAnyOf("No diagscope.yml found", "diagscope.yml");
    }

    @Test
    void doctor_shows_rule_count_from_project_configuration() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "configuration-policy");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.exitCode).isZero();
        assertThat(output.stdout).contains("Configuration");
        assertThat(output.stdout).contains("rules enabled");
    }

    @Test
    void doctor_exits_1_for_a_non_existent_project() {
        var output = capture("doctor", "--project", temp.resolve("does-not-exist").toString());

        assertThat(output.exitCode).isEqualTo(1);
        assertThat(output.stdout).contains(FAIL_SYMBOL);
    }

    @Test
    void doctor_exits_1_for_a_directory_without_build_descriptors() throws Exception {
        Path emptyDir = temp.resolve("no-build");
        Files.createDirectories(emptyDir);
        var output = capture("doctor", "--project", emptyDir.toString());

        assertThat(output.exitCode).isEqualTo(1);
        assertThat(output.stdout).contains(FAIL_SYMBOL);
    }

    @Test
    void doctor_prints_no_issues_message_on_clean_project() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        // Provide a dummy classpath entry to suppress the classpath warning.
        // mixed-flow is Java-only (no Kotlin) and has no Spring dependencies, so the
        // only potential warning is the missing classpath.
        Path dummyJar = temp.resolve("deps/lib.jar");
        Files.createDirectories(dummyJar.getParent());
        Files.createFile(dummyJar);
        var output = capture("doctor",
                "--project", project.toString(),
                "--classpath", dummyJar.toString());

        assertThat(output.exitCode).isZero();
        // No warnings → summary indicates readiness
        assertThat(output.stdout).contains("ready to scan");
    }

    // ── scan health ───────────────────────────────────────────────────────────

    @Test
    void doctor_with_scan_health_shows_parse_failures_and_boundary_counts() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var output = capture("doctor",
                "--project", project.toString(),
                "--with-scan-health");

        assertThat(output.exitCode).isZero();
        assertThat(output.stdout).contains("Scan health");
        assertThat(output.stdout).matches("(?s).*\\d+ parse failure.*");
        assertThat(output.stdout).matches("(?s).*\\d+ unresolved boundar.*");
        assertThat(output.stdout).matches("(?s).*\\d+ entrypoint.*analyzed.*");
    }

    @Test
    void doctor_without_scan_health_flag_omits_scan_health_section() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.stdout).doesNotContain("Scan health");
    }

    // ── Gradle ───────────────────────────────────────────────────────────────

    @Test
    void doctor_identifies_gradle_multi_module_project() {
        Path project = FixtureCatalog.copyTo(temp, "gradle-multi-module");
        var output = capture("doctor", "--project", project.toString());

        assertThat(output.exitCode).isZero();
        assertThat(output.stdout).containsAnyOf("Gradle multi-module", "Gradle single-module");
    }

    // ── CLI surface ───────────────────────────────────────────────────────────

    @Test
    void doctor_is_registered_as_a_top_level_subcommand() {
        var output = capture("--help");

        assertThat(output.stdout).contains("doctor");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static final String FAIL_SYMBOL = "✗";

    private static Captured capture(String... args) {
        var outBuf = new ByteArrayOutputStream();
        var errBuf = new ByteArrayOutputStream();
        PrintStream prevOut = System.out;
        PrintStream prevErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            exit = DiagScopeMain.createCommandLine().execute(args);
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        return new Captured(
                exit,
                outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8)
        );
    }

    private record Captured(int exitCode, String stdout, String stderr) {}
}
