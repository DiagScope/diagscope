package dev.diagscope.cli;

import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFormatTest {
    @TempDir
    Path temp;

    @Test
    void agent_alone_prints_compact_worst_first_text_and_writes_the_file() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");

        var run = scan(project, "--format", "AGENT");

        assertThat(run.exit).isZero();
        assertThat(run.out).startsWith("diagscope ").contains("9 finding(s) in ")
                .containsPattern("(?m)^ERROR HIGH SILENT_CATCH \\S+\\.java:\\d+$")
                .containsPattern("(?m)^  fix: ")
                .contains("Do not add diagscope:ignore comments")
                .doesNotContain("Findings:", "Parse failures");
        assertThat(run.out.indexOf("ERROR ")).isLessThan(run.out.indexOf("WARNING "));
        assertThat(run.out.indexOf("WARNING ")).isLessThan(run.out.indexOf("INFO "));
        assertThat(temp.resolve("out/result.agent.txt")).hasContent(run.out);
    }

    @Test
    void agent_next_to_other_formats_keeps_the_human_summary() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");

        var run = scan(project, "--format", "AGENT,JSON");

        assertThat(run.out).contains("Findings:").doesNotContain("Do not add diagscope:ignore");
        assertThat(temp.resolve("out/result.agent.txt")).isRegularFile();
    }

    @Test
    void files_scope_reports_only_the_listed_files() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var all = scan(project, "--format", "AGENT").out;
        String first = all.lines().filter(line -> line.matches("^(ERROR|WARNING|INFO) .*")).findFirst().orElseThrow();
        String file = first.substring(first.lastIndexOf(' ') + 1, first.lastIndexOf(':'));

        var scoped = scan(project, "--format", "AGENT", "--files", file);

        assertThat(scoped.exit).isZero();
        assertThat(scoped.out).contains(file);
        assertThat(scoped.out.lines().filter(line -> line.matches("^(ERROR|WARNING|INFO) .*"))
                .map(line -> line.substring(line.lastIndexOf(' ') + 1, line.lastIndexOf(':'))))
                .containsOnly(file);

        var none = scan(project, "--format", "AGENT", "--files", "src/main/java/Nope.java");
        assertThat(none.out).contains("no findings");
        assertThat(none.err).contains("does not exist");
    }

    @Test
    void files_outside_the_project_are_a_usage_error() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");

        assertThat(scan(project, "--format", "AGENT", "--files", "../elsewhere/A.java").exit).isEqualTo(2);
    }

    @Test
    void the_gate_still_decides_the_exit_code() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");

        assertThat(scan(project, "--format", "AGENT", "--fail-on", "ERROR").exit).isEqualTo(1);
    }

    private Run scan(Path project, String... extra) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            var args = new java.util.ArrayList<>(java.util.List.of("scan", "--project", project.toString(),
                    "--output", temp.resolve("out").toString(), "--parallelism", "1"));
            args.addAll(java.util.List.of(extra));
            int exit = DiagScopeMain.createCommandLine().execute(args.toArray(String[]::new));
            return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Run(int exit, String out, String err) {
    }
}
