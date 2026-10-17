package dev.diagscope.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InitAgentCommandTest {
    @TempDir
    Path temp;

    @Test
    void creates_instructions_hook_and_settings_and_is_idempotent() throws IOException {
        assertThat(init()).isZero();

        String claude = Files.readString(temp.resolve("CLAUDE.md"));
        assertThat(claude).contains("diagscope scan --format AGENT --entrypoint", "PUBLIC_METHOD", "--files", "Stop hook");
        assertThat(Files.readString(temp.resolve("AGENTS.md"))).doesNotContain("Stop hook");
        assertThat(temp.resolve(InitAgentCommand.HOOK_PATH)).isExecutable();
        var hooks = new ObjectMapper().readTree(temp.resolve(InitAgentCommand.SETTINGS_PATH).toFile())
                .path("hooks").path("Stop");
        assertThat(hooks).hasSize(1);
        assertThat(hooks.get(0).path("hooks").get(0).path("command").asText()).endsWith("diagscope-stop.sh");

        String before = Files.readString(temp.resolve(InitAgentCommand.SETTINGS_PATH));
        assertThat(init()).isZero();
        assertThat(Files.readString(temp.resolve(InitAgentCommand.SETTINGS_PATH))).isEqualTo(before);
        assertThat(Files.readString(temp.resolve("CLAUDE.md"))).isEqualTo(claude);
    }

    @Test
    void keeps_existing_content_and_replaces_only_its_own_block() throws IOException {
        Files.writeString(temp.resolve("CLAUDE.md"), "# Mine\n\nKeep this.\n");
        Files.createDirectories(temp.resolve(".claude"));
        Files.writeString(temp.resolve(InitAgentCommand.SETTINGS_PATH),
                "{\"permissions\":{\"allow\":[\"Bash(ls)\"]},\"hooks\":{\"Stop\":[{\"hooks\":"
                        + "[{\"type\":\"command\",\"command\":\"echo other\"}]}]}}");

        assertThat(init()).isZero();
        String once = Files.readString(temp.resolve("CLAUDE.md"));
        assertThat(once).startsWith("# Mine\n\nKeep this.\n").contains(InitAgentCommand.BEGIN);

        Files.writeString(temp.resolve("CLAUDE.md"), once.replace("fix what it reports", "STALE") + "\nTail.\n");
        assertThat(init()).isZero();
        String twice = Files.readString(temp.resolve("CLAUDE.md"));
        assertThat(twice).doesNotContain("STALE").endsWith("Tail.\n").contains("Keep this.");
        assertThat(twice.split("diagscope:begin", -1)).hasSize(2);

        var settings = new ObjectMapper().readTree(temp.resolve(InitAgentCommand.SETTINGS_PATH).toFile());
        assertThat(settings.path("permissions").path("allow").get(0).asText()).isEqualTo("Bash(ls)");
        assertThat(settings.path("hooks").path("Stop")).hasSize(2);
    }

    @Test
    void dry_run_and_agents_target_write_what_they_say() throws IOException {
        assertThat(DiagScopeMain.createCommandLine().execute(
                "init-agent", "--project", temp.toString(), "--dry-run")).isZero();
        try (var files = Files.list(temp)) {
            assertThat(files).isEmpty();
        }

        assertThat(DiagScopeMain.createCommandLine().execute(
                "init-agent", "--project", temp.toString(), "--target", "AGENTS")).isZero();
        assertThat(temp.resolve("AGENTS.md")).isRegularFile();
        assertThat(temp.resolve("CLAUDE.md")).doesNotExist();
        assertThat(temp.resolve(".claude")).doesNotExist();
    }

    @Test
    void rejects_unsafe_command_and_profile() {
        assertThat(DiagScopeMain.createCommandLine().execute(
                "init-agent", "--project", temp.toString(), "--command", "x; rm -rf /")).isEqualTo(2);
        assertThat(DiagScopeMain.createCommandLine().execute(
                "init-agent", "--project", temp.toString(), "--gate-profile", "a b")).isEqualTo(2);
    }

    @Test
    void the_generated_hook_is_valid_shell_and_blocks_only_when_the_scan_fails() throws Exception {
        assertThat(init()).isZero();
        Path hook = temp.resolve(InitAgentCommand.HOOK_PATH);
        assertThat(run("bash", "-n", hook.toString()).exit).isZero();

        // Fake scanner standing in for the jar, so the hook's decisions are tested without a JVM scan.
        Path fake = temp.resolve("fake.sh");
        Files.writeString(fake, "#!/usr/bin/env bash\necho \"ARGS: $*\"\nexit ${FAKE_EXIT:-0}\n");
        fake.toFile().setExecutable(true);
        run("git", "init", "-q", temp.toString());
        Files.createDirectories(temp.resolve("src"));
        Files.writeString(temp.resolve("src/A.java"), "class A {}");
        Files.writeString(temp.resolve("README.md"), "x");

        var failing = run(temp, "{}", "FAKE_EXIT", "1", hook.toString());
        assertThat(failing.exit).isEqualTo(2);
        assertThat(failing.out).contains("--files src/A.java", "--fail-on ERROR --fail-on-confidence HIGH", "--format AGENT",
                "--entrypoint REST,KAFKA_LISTENER,REACTIVE_MESSAGE,SCHEDULED,PUBLIC_METHOD");

        assertThat(failing.out).contains("--output /").doesNotContain("--baseline");
        Files.writeString(temp.resolve("diagscope-baseline.json"), "{}");
        assertThat(run(temp, "{}", "FAKE_EXIT", "1", hook.toString()).out).contains("--baseline");
        Files.delete(temp.resolve("diagscope-baseline.json"));
        Files.writeString(temp.resolve("diagscope.yml"), "schemaVersion: \"1.0\"\ngate:\n  maxFindings: {}\n");
        assertThat(run(temp, "{}", "FAKE_EXIT", "1", hook.toString()).out).doesNotContain("--fail-on");
        Files.delete(temp.resolve("diagscope.yml"));
        assertThat(temp.resolve("target")).doesNotExist();

        assertThat(run(temp, "{\"stop_hook_active\": true}", "FAKE_EXIT", "1", hook.toString()).exit).isZero();
        assertThat(run(temp, "{}", "FAKE_EXIT", "0", hook.toString()).exit).isZero();

        Files.delete(temp.resolve("src/A.java"));
        var noJava = run(temp, "{}", "FAKE_EXIT", "1", hook.toString());
        assertThat(noJava.exit).isZero();
    }

    private int init() {
        return DiagScopeMain.createCommandLine().execute(
                "init-agent", "--project", temp.toString(), "--command", temp.resolve("fake.sh").toString());
    }

    private Result run(String... command) throws Exception {
        return run(null, null, null, null, command);
    }

    private Result run(Path dir, String stdin, String envKey, String envValue, String... command) throws Exception {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (dir != null) {
            builder.directory(dir.toFile());
            builder.environment().put("CLAUDE_PROJECT_DIR", dir.toString());
            builder.environment().put(envKey, envValue);
        }
        var process = builder.start();
        if (stdin != null) process.getOutputStream().write(stdin.getBytes());
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), out);
    }

    private record Result(int exit, String out) {
    }
}
