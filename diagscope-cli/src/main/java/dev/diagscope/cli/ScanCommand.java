package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.application.ScanPolicyMetadata;
import dev.diagscope.core.application.port.in.ScanProjectUseCase;
import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Severity;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(
        name = "scan",
        mixinStandardHelpOptions = true,
        description = "Analyze a Maven or Gradle JVM project for diagnostic coverage gaps."
)
public final class ScanCommand implements Callable<Integer> {
    private final ScanProjectUseCase useCase;
    private final Map<ReportFormat, AnalysisReporter> reporters;

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

    @Option(names = "--entrypoint", split = ",", defaultValue = "REST,KAFKA_LISTENER,SCHEDULED", description = "Entrypoint types to analyze")
    private EnumSet<EntrypointType> entrypointTypes;

    public ScanCommand(ScanProjectUseCase useCase, Map<ReportFormat, AnalysisReporter> reporters) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        var copy = new EnumMap<ReportFormat, AnalysisReporter>(ReportFormat.class);
        copy.putAll(reporters);
        this.reporters = Map.copyOf(copy);
    }

    @Override
    public Integer call() {
        try {
            Path projectRoot = project.toAbsolutePath().normalize();
            if (!Files.isDirectory(projectRoot)) {
                throw new UnsupportedProjectException("Project directory does not exist: " + projectRoot);
            }

            int workers = parallelism == 0 ? AnalysisOptions.defaults().parallelism() : parallelism;
            var loadedConfiguration = loadConfiguration(projectRoot);
            var options = new AnalysisOptions(maxDepth, workers, entrypointTypes, loadedConfiguration.policy(),
                    resolveClasspath(projectRoot), resolveSourceRoots(projectRoot));
            Path outputDirectory = resolveOutputDirectory(projectRoot);
            var rawResult = useCase.scan(new AnalysisRequest(projectRoot, options));

            var changeScope = changedSince == null
                    ? new ChangedFileScope.ScopeApplication(rawResult, 0)
                    : changedFileScope(projectRoot, rawResult);

            var baselineStore = new FindingBaseline();
            if ((!baselineMigrations.isEmpty() || pruneRemovedBaseline) && !updateBaseline) {
                throw new IllegalArgumentException(
                        "--baseline-migration and --prune-removed-baseline require --update-baseline");
            }
            Path selectedBaseline = baseline == null ? null
                    : resolveProjectFile(projectRoot, baseline, "Baseline");
            var selectedState = readBaseline(baselineStore, selectedBaseline, updateBaseline);
            var baselineApplication = baselineStore.suppress(changeScope.result(), selectedState);
            var result = baselineApplication.result();

            Path updatedBaseline = null;
            var baselineLifecycle = new FindingBaseline.Lifecycle(0, 0);
            if (updateBaseline) {
                updatedBaseline = selectedBaseline != null ? selectedBaseline
                        : projectRoot.resolve(FindingBaseline.DEFAULT_FILE_NAME);
                var previousState = selectedBaseline != null
                        ? selectedState : readBaseline(baselineStore, updatedBaseline, true);
                baselineLifecycle = baselineStore.write(updatedBaseline, rawResult, previousState,
                        parseBaselineMigrations(), pruneRemovedBaseline);
            }

            Path effectiveBaseline = selectedBaseline != null ? selectedBaseline : updatedBaseline;
            result = AnalysisResultFilter.withScanPolicy(result, new ScanPolicyMetadata(
                    displayPath(projectRoot, loadedConfiguration.source()),
                    displayPath(projectRoot, effectiveBaseline),
                    baselineApplication.suppressedFindings(),
                    baselineLifecycle.removedFindings(),
                    baselineLifecycle.migratedFindings(),
                    changedSince,
                    changeScope.excludedFindings()
            ));

            writeReports(result, outputDirectory);
            printSummary(result, outputDirectory, changedSince, changeScope.excludedFindings(),
                    baselineApplication.suppressedFindings(), updatedBaseline, baselineLifecycle);
            return exitCode(result);
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

    /** Severity gate for pipelines; NONE keeps the scan purely informational. */
    enum FailOn {
        NONE(null),
        INFO(Severity.INFO),
        WARNING(Severity.WARNING),
        ERROR(Severity.ERROR);

        private final Severity threshold;

        FailOn(Severity threshold) {
            this.threshold = threshold;
        }

        Severity threshold() {
            return threshold;
        }
    }

    private int exitCode(AnalysisResult result) {
        Severity threshold = failOn.threshold();
        if (threshold == null) {
            return 0;
        }
        long breaching = result.findings().stream()
                .filter(finding -> finding.severity().compareTo(threshold) >= 0)
                .count();
        if (breaching == 0) {
            return 0;
        }
        System.err.printf("Failing: %d finding(s) at or above %s (--fail-on %s).%n",
                breaching, threshold, failOn);
        return 1;
    }

    private Path resolveOutputDirectory(Path projectRoot) {
        if (output.isAbsolute()) {
            return output.toAbsolutePath().normalize();
        }
        Path resolved = projectRoot.resolve(output).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Relative output must stay within the analyzed project");
        }
        return resolved;
    }

    private List<Path> resolveClasspath(Path projectRoot) {
        return classpath.stream().map(path -> {
            Path resolved = resolveExistingPath(projectRoot, path, "Classpath entry");
            String name = resolved.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!Files.isDirectory(resolved) && !name.endsWith(".jar") && !name.endsWith(".zip")) {
                throw new IllegalArgumentException("Classpath entry must be a JAR/ZIP or directory: "
                        + resolved);
            }
            return resolved;
        })
                .distinct().toList();
    }

    private List<Path> resolveSourceRoots(Path projectRoot) {
        return sourceRoots.stream().map(path -> {
            Path resolved = resolveExistingPath(projectRoot, path, "Source root");
            if (!resolved.startsWith(projectRoot)) {
                throw new IllegalArgumentException("Source root must stay within the analyzed project: " + path);
            }
            if (!Files.isDirectory(resolved)) {
                throw new IllegalArgumentException("Source root is not a directory: " + resolved);
            }
            return resolved;
        }).distinct().toList();
    }

    private static Path resolveExistingPath(Path projectRoot, Path configured, String label) {
        Path resolved = configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : projectRoot.resolve(configured).toAbsolutePath().normalize();
        if (!Files.exists(resolved)) throw new IllegalArgumentException(label + " does not exist: " + resolved);
        return resolved;
    }

    private Path resolveProjectFile(Path projectRoot, Path configured, String label) {
        if (configured.isAbsolute()) {
            return configured.toAbsolutePath().normalize();
        }
        Path resolved = projectRoot.resolve(configured).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new IllegalArgumentException(label + " path must stay within the analyzed project");
        }
        return resolved;
    }

    private ProjectConfigurationLoader.LoadedConfiguration loadConfiguration(Path projectRoot) throws IOException {
        Path selected;
        if (configuration != null) {
            selected = resolveProjectFile(projectRoot, configuration, "Configuration");
            if (!Files.isRegularFile(selected)) {
                throw new IllegalArgumentException("Configuration file does not exist: " + selected);
            }
        } else {
            Path conventional = projectRoot.resolve(ProjectConfigurationLoader.DEFAULT_FILE_NAME);
            if (!Files.isRegularFile(conventional)) return ProjectConfigurationLoader.defaults();
            selected = conventional;
        }
        return new ProjectConfigurationLoader().load(selected);
    }

    private static String displayPath(Path projectRoot, Path file) {
        if (file == null) return "";
        Path normalized = file.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString().replace('\\', '/')
                : normalized.toString();
    }

    private FindingBaseline.BaselineState readBaseline(
            FindingBaseline store, Path selectedBaseline, boolean allowMissing
    ) throws IOException {
        if (selectedBaseline == null) {
            return FindingBaseline.BaselineState.empty();
        }
        if (!Files.exists(selectedBaseline)) {
            if (allowMissing) return FindingBaseline.BaselineState.empty();
            throw new IllegalArgumentException("Baseline file does not exist: " + selectedBaseline);
        }
        if (!Files.isRegularFile(selectedBaseline)) {
            throw new IllegalArgumentException("Baseline is not a regular file: " + selectedBaseline);
        }
        return store.read(selectedBaseline);
    }

    private Map<String, String> parseBaselineMigrations() {
        var migrations = new LinkedHashMap<String, String>();
        for (String configured : baselineMigrations) {
            int separator = configured.indexOf('=');
            if (separator <= 0 || separator == configured.length() - 1) {
                throw new IllegalArgumentException("Baseline migration must use OLD=NEW: " + configured);
            }
            String source = configured.substring(0, separator).trim();
            String target = configured.substring(separator + 1).trim();
            String previous = migrations.putIfAbsent(source, target);
            if (previous != null && !previous.equals(target)) {
                throw new IllegalArgumentException("Baseline migration source has multiple targets: " + source);
            }
        }
        return Map.copyOf(migrations);
    }

    private ChangedFileScope.ScopeApplication changedFileScope(Path projectRoot, AnalysisResult result)
            throws IOException, InterruptedException {
        var scope = new ChangedFileScope();
        return scope.apply(result, scope.filesChangedSince(projectRoot, changedSince));
    }

    private void writeReports(AnalysisResult result, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        for (ReportFormat format : formats) {
            AnalysisReporter reporter = reporters.get(format);
            if (reporter == null) {
                throw new IllegalStateException("No reporter configured for " + format);
            }
            writeAtomically(reporter, result, outputDirectory.resolve(format.fileName()));
        }
    }

    private static void writeAtomically(AnalysisReporter reporter, AnalysisResult result, Path destination)
            throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), "." + destination.getFileName() + '-', ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                reporter.write(result, output);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void printSummary(
            AnalysisResult result,
            Path outputDirectory,
            String changedSince,
            int changeScopeExcludedFindings,
            int suppressedFindings,
            Path updatedBaseline,
            FindingBaseline.Lifecycle baselineLifecycle
    ) {
        var statistics = result.statistics();
        long boundaries = result.flows().stream().mapToLong(flow -> flow.boundaries().size()).sum();
        System.out.printf("DiagScope %s analyzed %d files, %d methods and %d flows in %d ms.%n",
                BuildInfo.version(), statistics.sourceFiles(), statistics.parsedMethods(), statistics.flows(),
                statistics.phaseMetrics().totalMillis());
        System.out.printf("Findings: %d | Parse failures: %d | Flow boundaries: %d | Output: %s%n",
                statistics.findings(), statistics.parseFailures(), boundaries, outputDirectory);
        if (changeScopeExcludedFindings > 0) {
            System.out.printf("Change scope excluded %d finding(s) outside --changed-since %s.%n",
                    changeScopeExcludedFindings, changedSince);
        }
        if (suppressedFindings > 0) {
            System.out.printf("Baseline suppressed %d known finding(s).%n", suppressedFindings);
        }
        if (updatedBaseline != null) {
            System.out.printf("Baseline updated: %s%n", updatedBaseline);
            if (baselineLifecycle.removedFindings() > 0 || baselineLifecycle.migratedFindings() > 0) {
                System.out.printf("Baseline lifecycle: %d removed finding(s), %d fingerprint migration(s).%n",
                        baselineLifecycle.removedFindings(), baselineLifecycle.migratedFindings());
            }
        }
    }
}
