package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.core.application.port.in.ScanProjectUseCase;
import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.domain.EntrypointType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "scan",
        mixinStandardHelpOptions = true,
        description = "Analyze a Maven or Gradle JVM project for diagnostic coverage gaps."
)
public final class ScanCommand implements Callable<Integer> {
    private final ScanWorkflow workflow;

    @Option(names = {"-p", "--project"}, required = true, description = "Maven or Gradle project directory")
    private Path project;

    @Option(names = {"-o", "--output"}, description = "Output directory, relative to the project by default", defaultValue = "target/diagscope")
    private Path output;

    @Option(names = "--max-depth", defaultValue = "3", description = "Maximum local call depth, from 0 to 32")
    private int maxDepth;

    @Option(names = "--parallelism", defaultValue = "${sys:diagscope.parallelism:-0}", description = "Java parser workers; 0 selects an automatic bounded value")
    private int parallelism;

    @Option(names = "--format", split = ",", defaultValue = "MARKDOWN,JSON,HTML",
            description = "MARKDOWN, JSON, HTML, SARIF, or any combination")
    private EnumSet<ReportFormat> formats;

    @Option(names = "--fail-on", description = "Exit with code 1 when a finding of this severity or higher exists: ERROR, WARNING, INFO, or NONE", defaultValue = "NONE")
    private FailOn failOn;

    @Option(names = "--baseline", arity = "0..1", fallbackValue = FindingBaseline.DEFAULT_FILE_NAME,
            description = "Suppress findings recorded in this baseline (default path when omitted: ${FALLBACK-VALUE})")
    private Path baseline;

    @Option(names = "--update-baseline",
            description = "Rewrite the selected baseline, or diagscope-baseline.json, with all current findings")
    private boolean updateBaseline;

    @Option(names = "--baseline-migration", description = "Intentional fingerprint migration OLD=NEW; repeat for multiple mappings")
    private List<String> baselineMigrations = List.of();

    @Option(names = "--prune-removed-baseline",
            description = "Discard removed-finding history while updating the baseline")
    private boolean pruneRemovedBaseline;

    @Option(names = "--changed-since",
            description = "Report only findings in files changed since this Git revision")
    private String changedSince;

    @Option(names = "--config", description = "Project policy file; defaults to diagscope.yml when present")
    private Path configuration;

    @Option(names = "--classpath", split = ",",
            description = "Explicit dependency JAR or classes directory; repeat or comma-separate entries")
    private List<Path> classpath = List.of();

    @Option(names = "--source-root", split = ",",
            description = "Additional production source root inside the project; repeat or comma-separate entries")
    private List<Path> sourceRoots = List.of();

    @Option(names = "--entrypoint", split = ",", defaultValue = "REST,KAFKA_LISTENER,REACTIVE_MESSAGE,SCHEDULED", description = "Entrypoint types to analyze")
    private EnumSet<EntrypointType> entrypointTypes;

    public ScanCommand(ScanProjectUseCase useCase, Map<ReportFormat, AnalysisReporter> reporters) {
        this(new ScanWorkflow(useCase, reporters));
    }

    public ScanCommand(ScanWorkflow workflow) {
        this.workflow = java.util.Objects.requireNonNull(workflow, "workflow");
    }

    @Override
    public Integer call() {
        try {
            return workflow.run(new ScanWorkflow.Request(
                    project, output, maxDepth, parallelism, formats, failOn, baseline, updateBaseline,
                    baselineMigrations, pruneRemovedBaseline, changedSince, configuration, classpath,
                    sourceRoots, entrypointTypes
            )).exitCode();
        } catch (UnsupportedProjectException exception) {
            System.err.println("Unsupported project: " + exception.getMessage());
            return 3;
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid scan configuration: " + exception.getMessage());
            return 2;
        } catch (Exception exception) {
            System.err.println("DiagScope failed: " + exception.getMessage());
            return 2;
        }
    }
}
