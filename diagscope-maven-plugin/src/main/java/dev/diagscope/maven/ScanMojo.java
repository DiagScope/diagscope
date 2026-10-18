package dev.diagscope.maven;

import dev.diagscope.cli.DiagScopeMain;
import dev.diagscope.cli.FailOn;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.cli.ScanWorkflow;
import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.EntrypointType;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Scans the module's sources with DiagScope and fails the build when the gate is breached.
 *
 * <p>It runs the same engine as {@code diagscope scan} on the module's base directory, so a result here matches the
 * command line. Tolerance is configured the same way: a {@code gate:} section in {@code diagscope.yml}, or the
 * {@code failOn} parameters below. Without either, the scan only reports.</p>
 */
@Mojo(name = "scan", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class ScanMojo extends AbstractMojo {
    /** Maven's default build output directory name; used when the model has no build directory. */
    private static final String FALLBACK_OUTPUT = "target/diagscope";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Skips the scan entirely. */
    @Parameter(property = "diagscope.skip", defaultValue = "false")
    boolean skip;

    /** Breaks the build at this severity or above: ERROR, WARNING, INFO or NONE. Overrides the gate of diagscope.yml. */
    @Parameter(property = "diagscope.failOn")
    String failOn;

    /** With failOn, only findings of at least this confidence (LOW, MEDIUM, HIGH) break the build. */
    @Parameter(property = "diagscope.failOnConfidence")
    String failOnConfidence;

    /** A named profile of the gate section of diagscope.yml; not combinable with failOn. */
    @Parameter(property = "diagscope.gateProfile")
    String gateProfile;

    /** A baseline file whose findings are ignored, so only new ones are judged. */
    @Parameter(property = "diagscope.baseline")
    File baseline;

    /** Project policy file. Defaults to diagscope.yml in the module, then in the multi-module root. */
    @Parameter(property = "diagscope.config")
    File configuration;

    /** Only findings in files changed since this Git revision are reported. */
    @Parameter(property = "diagscope.changedSince")
    String changedSince;

    /** Where the reports go. */
    @Parameter(property = "diagscope.outputDirectory", defaultValue = "${project.build.directory}/diagscope")
    File outputDirectory;

    /** Report formats: MARKDOWN, JSON, HTML, SARIF, AGENT. */
    @Parameter(property = "diagscope.formats", defaultValue = "MARKDOWN,JSON,HTML")
    List<String> formats;

    /** Entrypoint types: REST, KAFKA_LISTENER, REACTIVE_MESSAGE, SCHEDULED, PUBLIC_METHOD. Empty selects the defaults. */
    @Parameter(property = "diagscope.entrypoints")
    List<String> entrypoints;

    /** Also analyzes src/test with the test rules. */
    @Parameter(property = "diagscope.includeTests", defaultValue = "false")
    boolean includeTests;

    /** Maximum local call depth, from 0 to 32. */
    @Parameter(property = "diagscope.maxDepth", defaultValue = "3")
    int maxDepth;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("DiagScope is skipped.");
            return;
        }
        if ("pom".equals(project.getPackaging())) {
            getLog().debug("DiagScope skips a module with pom packaging.");
            return;
        }
        File basedir = project.getBasedir();
        if (basedir == null) {
            throw new MojoExecutionException("DiagScope needs a project directory.");
        }

        ScanWorkflow.Outcome outcome;
        try (PrintStream out = stream(getLog()::info); PrintStream err = stream(getLog()::error)) {
            outcome = DiagScopeMain.createScanWorkflow().run(request(basedir.toPath()), out, err);
        } catch (UnsupportedProjectException exception) {
            getLog().warn("DiagScope skipped " + project.getArtifactId() + ": " + exception.getMessage());
            return;
        } catch (IllegalArgumentException exception) {
            throw new MojoExecutionException("Invalid DiagScope configuration: " + exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new MojoExecutionException("DiagScope failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("DiagScope was interrupted.", exception);
        }

        if (outcome.gateBreached()) {
            throw new MojoFailureException("DiagScope gate failed for " + project.getArtifactId()
                    + ". Reports: " + outcome.outputDirectory());
        }
    }

    ScanWorkflow.Request request(Path basedir) throws MojoExecutionException {
        return new ScanWorkflow.Request(
                basedir,
                (outputDirectory == null ? basedir.resolve(FALLBACK_OUTPUT).toFile() : outputDirectory).toPath(),
                maxDepth, 0,
                enumSet(ReportFormat.class, formats, "format"),
                blank(failOn) ? null : enumValue(FailOn.class, failOn, "failOn"),
                baseline == null ? null : baseline.toPath(),
                false, List.of(), false,
                blank(changedSince) ? null : changedSince,
                configurationFile(basedir),
                List.of(), List.of(),
                enumSet(EntrypointType.class, entrypoints, "entrypoint"),
                includeTests,
                blank(gateProfile) ? null : gateProfile,
                List.of(),
                blank(failOnConfidence) ? null : enumValue(Confidence.class, failOnConfidence, "failOnConfidence"));
    }

    /** An explicit file wins; otherwise the module's own diagscope.yml, then the one at the multi-module root. */
    private Path configurationFile(Path basedir) {
        if (configuration != null) return configuration.toPath().toAbsolutePath();
        if (Files.isRegularFile(basedir.resolve("diagscope.yml"))) return null;
        for (MavenProject parent = project.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.getBasedir() == null) continue;
            Path candidate = parent.getBasedir().toPath().resolve("diagscope.yml");
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath();
        }
        return null;
    }

    private static <E extends Enum<E>> EnumSet<E> enumSet(Class<E> type, List<String> values, String what)
            throws MojoExecutionException {
        if (values == null || values.isEmpty()) return null;
        var result = EnumSet.noneOf(type);
        for (String value : values) {
            if (!blank(value)) result.add(enumValue(type, value, what));
        }
        return result.isEmpty() ? null : result;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String what)
            throws MojoExecutionException {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new MojoExecutionException("Unknown " + what + " '" + value + "'; expected one of "
                    + List.of(type.getEnumConstants()));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** A stream that hands each completed line to the Maven log, so scan output reads like any other plugin. */
    private static PrintStream stream(Consumer<String> sink) {
        return new PrintStream(new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();

            @Override
            public void write(int value) {
                if (value == '\n') {
                    flushLine();
                } else if (value != '\r') {
                    line.write(value);
                }
            }

            /** Only a newline ends a log line; a flush in the middle of a printf must not split it. */
            @Override
            public void close() {
                if (line.size() > 0) flushLine();
            }

            private void flushLine() {
                sink.accept(line.toString(StandardCharsets.UTF_8));
                line.reset();
            }
        }, true, StandardCharsets.UTF_8);
    }
}
