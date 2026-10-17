package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.application.ScanPolicyMetadata;
import dev.diagscope.core.application.port.in.ScanProjectUseCase;
import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.jvmanalysis.ProjectLayoutDetector;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Scan engine shared by every distribution channel: the CLI, build-tool plugins and CI wrappers.
 * It owns configuration loading, baselines, waivers, report writing and the severity gate so that
 * embedding DiagScope never means re-implementing the pipeline.
 */
public final class ScanWorkflow {
    private final ScanProjectUseCase useCase;
    private final Map<ReportFormat, AnalysisReporter> reporters;

    public ScanWorkflow(ScanProjectUseCase useCase, Map<ReportFormat, AnalysisReporter> reporters) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        var copy = new EnumMap<ReportFormat, AnalysisReporter>(ReportFormat.class);
        copy.putAll(reporters);
        this.reporters = Map.copyOf(copy);
    }

    /** Everything a caller can configure; mirrors the {@code scan} command options one to one. */
    public record Request(
            Path project,
            Path output,
            int maxDepth,
            int parallelism,
            EnumSet<ReportFormat> formats,
            FailOn failOn,
            Path baseline,
            boolean updateBaseline,
            List<String> baselineMigrations,
            boolean pruneRemovedBaseline,
            String changedSince,
            Path configuration,
            List<Path> classpath,
            List<Path> sourceRoots,
            EnumSet<EntrypointType> entrypointTypes,
            boolean includeTests,
            String gateProfile,
            List<String> files,
            Confidence failOnConfidence
    ) {
        /** Request that analyzes production sources only. */
        public Request(
                Path project, Path output, int maxDepth, int parallelism, EnumSet<ReportFormat> formats,
                FailOn failOn, Path baseline, boolean updateBaseline, List<String> baselineMigrations,
                boolean pruneRemovedBaseline, String changedSince, Path configuration, List<Path> classpath,
                List<Path> sourceRoots, EnumSet<EntrypointType> entrypointTypes
        ) {
            this(project, output, maxDepth, parallelism, formats, failOn, baseline, updateBaseline,
                    baselineMigrations, pruneRemovedBaseline, changedSince, configuration, classpath, sourceRoots,
                    entrypointTypes, false, null);
        }

        /** Request without a gate profile. */
        public Request(
                Path project, Path output, int maxDepth, int parallelism, EnumSet<ReportFormat> formats,
                FailOn failOn, Path baseline, boolean updateBaseline, List<String> baselineMigrations,
                boolean pruneRemovedBaseline, String changedSince, Path configuration, List<Path> classpath,
                List<Path> sourceRoots, EnumSet<EntrypointType> entrypointTypes, boolean includeTests
        ) {
            this(project, output, maxDepth, parallelism, formats, failOn, baseline, updateBaseline,
                    baselineMigrations, pruneRemovedBaseline, changedSince, configuration, classpath, sourceRoots,
                    entrypointTypes, includeTests, null);
        }

        /** Request that is not limited to specific files. */
        public Request(
                Path project, Path output, int maxDepth, int parallelism, EnumSet<ReportFormat> formats,
                FailOn failOn, Path baseline, boolean updateBaseline, List<String> baselineMigrations,
                boolean pruneRemovedBaseline, String changedSince, Path configuration, List<Path> classpath,
                List<Path> sourceRoots, EnumSet<EntrypointType> entrypointTypes, boolean includeTests,
                String gateProfile
        ) {
            this(project, output, maxDepth, parallelism, formats, failOn, baseline, updateBaseline,
                    baselineMigrations, pruneRemovedBaseline, changedSince, configuration, classpath, sourceRoots,
                    entrypointTypes, includeTests, gateProfile, null, null);
        }

        /** Request without a minimum confidence for {@code --fail-on}. */
        public Request(
                Path project, Path output, int maxDepth, int parallelism, EnumSet<ReportFormat> formats,
                FailOn failOn, Path baseline, boolean updateBaseline, List<String> baselineMigrations,
                boolean pruneRemovedBaseline, String changedSince, Path configuration, List<Path> classpath,
                List<Path> sourceRoots, EnumSet<EntrypointType> entrypointTypes, boolean includeTests,
                String gateProfile, List<String> files
        ) {
            this(project, output, maxDepth, parallelism, formats, failOn, baseline, updateBaseline,
                    baselineMigrations, pruneRemovedBaseline, changedSince, configuration, classpath, sourceRoots,
                    entrypointTypes, includeTests, gateProfile, files, null);
        }

        public Request {
            Objects.requireNonNull(project, "project");
            formats = formats == null || formats.isEmpty()
                    ? EnumSet.of(ReportFormat.MARKDOWN, ReportFormat.JSON, ReportFormat.HTML)
                    : EnumSet.copyOf(formats);
            files = files == null ? List.of() : List.copyOf(files);
            baselineMigrations = baselineMigrations == null ? List.of() : List.copyOf(baselineMigrations);
            classpath = classpath == null ? List.of() : List.copyOf(classpath);
            sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
            entrypointTypes = entrypointTypes == null || entrypointTypes.isEmpty()
                    ? EnumSet.copyOf(AnalysisOptions.DEFAULT_ENTRYPOINT_TYPES)
                    : EnumSet.copyOf(entrypointTypes);
        }
    }

    /** Result of a completed scan, including the process exit code implied by {@code --fail-on}. */
    public record Outcome(AnalysisResult result, Path outputDirectory, int exitCode) {
        public boolean gateBreached() {
            return exitCode != 0;
        }
    }

    public Outcome run(Request request) throws IOException, InterruptedException {
        return run(request, System.out, System.err);
    }

    public Outcome run(Request request, PrintStream out, PrintStream err)
            throws IOException, InterruptedException {
        Path projectRoot = request.project().toAbsolutePath().normalize();
        if (!Files.isDirectory(projectRoot)) {
            throw new UnsupportedProjectException("Project directory does not exist: " + projectRoot);
        }

        int workers = request.parallelism() == 0
                ? AnalysisOptions.defaults().parallelism() : request.parallelism();
        int depth = request.maxDepth() < 0 ? AnalysisOptions.defaults().maxFlowDepth() : request.maxDepth();
        var loadedConfiguration = loadConfiguration(projectRoot, request.configuration());
        var options = new AnalysisOptions(depth, workers, request.entrypointTypes(),
                loadedConfiguration.policy(), resolveClasspath(projectRoot, request.classpath()),
                withTestRoots(projectRoot, resolveSourceRoots(projectRoot, request.sourceRoots()),
                        request.includeTests()));
        Path outputDirectory = resolveOutputDirectory(projectRoot,
                request.output() != null ? request.output() : defaultOutputFor(projectRoot));
        var rawResult = useCase.scan(new AnalysisRequest(projectRoot, options));

        var changeScope = request.changedSince() == null
                ? new ChangedFileScope.ScopeApplication(rawResult, 0)
                : changedFileScope(projectRoot, rawResult, request.changedSince());
        if (!request.files().isEmpty()) {
            var scope = new ChangedFileScope();
            var listed = scope.apply(changeScope.result(), explicitFiles(projectRoot, request.files(), err));
            changeScope = new ChangedFileScope.ScopeApplication(listed.result(),
                    changeScope.excludedFindings() + listed.excludedFindings());
        }

        var baselineStore = new FindingBaseline();
        if ((!request.baselineMigrations().isEmpty() || request.pruneRemovedBaseline())
                && !request.updateBaseline()) {
            throw new IllegalArgumentException(
                    "--baseline-migration and --prune-removed-baseline require --update-baseline");
        }
        Path selectedBaseline = request.baseline() == null ? null
                : resolveProjectFile(projectRoot, request.baseline(), "Baseline");
        var selectedState = readBaseline(baselineStore, selectedBaseline, request.updateBaseline());
        var baselineApplication = baselineStore.suppress(changeScope.result(), selectedState);
        var result = baselineApplication.result();

        Path updatedBaseline = null;
        var baselineLifecycle = new FindingBaseline.Lifecycle(0, 0);
        if (request.updateBaseline()) {
            updatedBaseline = selectedBaseline != null ? selectedBaseline
                    : projectRoot.resolve(FindingBaseline.DEFAULT_FILE_NAME);
            var previousState = selectedBaseline != null
                    ? selectedState : readBaseline(baselineStore, updatedBaseline, true);
            baselineLifecycle = baselineStore.write(updatedBaseline, rawResult, previousState,
                    parseBaselineMigrations(request.baselineMigrations()), request.pruneRemovedBaseline());
        }

        var suppressionApplication = new FindingSuppressions()
                .apply(result, loadedConfiguration.waivers());
        result = suppressionApplication.result();

        Path effectiveBaseline = selectedBaseline != null ? selectedBaseline : updatedBaseline;
        result = AnalysisResultFilter.withScanPolicy(result, new ScanPolicyMetadata(
                displayPath(projectRoot, loadedConfiguration.source()),
                displayPath(projectRoot, effectiveBaseline),
                baselineApplication.suppressedFindings(),
                baselineLifecycle.removedFindings(),
                baselineLifecycle.migratedFindings(),
                request.changedSince(),
                changeScope.excludedFindings(),
                suppressionApplication.suppressedFindings(),
                suppressionApplication.expiredFingerprints().size(),
                suppressionApplication.unusedFingerprints().size()
        ));

        writeReports(result, outputDirectory, request.formats());
        if (request.formats().equals(EnumSet.of(ReportFormat.AGENT))) {
            reporters.get(ReportFormat.AGENT).write(result, out);
        } else printSummary(out, new RunSummary(result, outputDirectory, request.changedSince(),
                changeScope.excludedFindings(), baselineApplication.suppressedFindings(), updatedBaseline,
                baselineLifecycle, suppressionApplication));
        var gate = selectGate(request, loadedConfiguration.gate());
        return new Outcome(result, outputDirectory, exitCode(result, gate, err));
    }

    /** An explicit {@code --fail-on} wins over the project gate, like every other scan option. */
    private static GatePolicy selectGate(Request request, ProjectConfigurationLoader.GateConfiguration configured) {
        if (request.failOn() != null) {
            if (request.gateProfile() != null) {
                throw new IllegalArgumentException("--fail-on and --gate-profile are mutually exclusive");
            }
            return GatePolicy.ofSeverity(request.failOn(),
                    request.failOnConfidence() == null ? Confidence.LOW : request.failOnConfidence());
        }
        if (request.failOnConfidence() != null) {
            throw new IllegalArgumentException("--fail-on-confidence requires --fail-on");
        }
        return configured.select(request.gateProfile());
    }

    private static int exitCode(AnalysisResult result, GatePolicy gate, PrintStream err) {
        var breaches = gate.evaluate(result.findings());
        if (breaches.isEmpty()) {
            return 0;
        }
        err.println("Gate failed:");
        breaches.forEach(breach -> err.printf("  - %d finding(s): %s%n", breach.findings(), breach.description()));
        return 1;
    }

    /**
     * Returns the conventional output directory for the build system detected at {@code projectRoot}.
     * Gradle projects use {@code build/diagscope}; Maven (or unknown) use {@code target/diagscope}.
     */
    private static Path defaultOutputFor(Path projectRoot) {
        BuildSystem buildSystem = ProjectLayoutDetector.buildSystemAt(projectRoot).orElse(BuildSystem.MAVEN);
        return buildSystem == BuildSystem.GRADLE || buildSystem == BuildSystem.MAVEN_AND_GRADLE
                ? Path.of("build", "diagscope")
                : Path.of("target", "diagscope");
    }

    private static Path resolveOutputDirectory(Path projectRoot, Path output) {
        if (output.isAbsolute()) {
            return output.toAbsolutePath().normalize();
        }
        Path resolved = projectRoot.resolve(output).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Relative output must stay within the analyzed project");
        }
        return resolved;
    }

    private static List<Path> resolveClasspath(Path projectRoot, List<Path> classpath) {
        return classpath.stream().map(path -> {
            Path resolved = resolveExistingPath(projectRoot, path, "Classpath entry");
            String name = resolved.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!Files.isDirectory(resolved) && !name.endsWith(".jar") && !name.endsWith(".zip")) {
                throw new IllegalArgumentException("Classpath entry must be a JAR/ZIP or directory: " + resolved);
            }
            return resolved;
        }).distinct().toList();
    }

    /** Adds every {@code src/test/java} and {@code src/test/kotlin} directory of the project when asked to. */
    private static List<Path> withTestRoots(Path projectRoot, List<Path> sourceRoots, boolean includeTests) {
        if (!includeTests) return sourceRoots;
        var roots = new java.util.LinkedHashSet<>(sourceRoots);
        try (var paths = Files.walk(projectRoot, 8)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> !projectRoot.relativize(path).toString().matches(
                            ".*(^|/)(target|build|node_modules|\\.git|\\.gradle|out)(/.*)?$"))
                    .filter(path -> {
                        String relative = projectRoot.relativize(path).toString().replace('\\', '/');
                        return relative.endsWith("src/test/java") || relative.endsWith("src/test/kotlin");
                    })
                    .sorted().forEach(roots::add);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to look for test sources: " + exception.getMessage());
        }
        return List.copyOf(roots);
    }

    private static List<Path> resolveSourceRoots(Path projectRoot, List<Path> sourceRoots) {
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
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException(label + " does not exist: " + resolved);
        }
        return resolved;
    }

    private static Path resolveProjectFile(Path projectRoot, Path configured, String label) {
        if (configured.isAbsolute()) {
            return configured.toAbsolutePath().normalize();
        }
        Path resolved = projectRoot.resolve(configured).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new IllegalArgumentException(label + " path must stay within the analyzed project");
        }
        return resolved;
    }

    private static ProjectConfigurationLoader.LoadedConfiguration loadConfiguration(
            Path projectRoot, Path configuration) throws IOException {
        Path selected;
        if (configuration != null) {
            selected = resolveProjectFile(projectRoot, configuration, "Configuration");
            if (!Files.isRegularFile(selected)) {
                throw new IllegalArgumentException("Configuration file does not exist: " + selected);
            }
        } else {
            Path conventional = projectRoot.resolve(ProjectConfigurationLoader.DEFAULT_FILE_NAME);
            if (!Files.isRegularFile(conventional)) {
                return ProjectConfigurationLoader.defaults();
            }
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

    private static FindingBaseline.BaselineState readBaseline(
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

    private static Map<String, String> parseBaselineMigrations(List<String> baselineMigrations) {
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

    /** Normalizes {@code --files} to project-relative paths; a path outside the project is a usage error. */
    private static Set<String> explicitFiles(Path projectRoot, List<String> files, PrintStream err) {
        var normalized = new TreeSet<String>();
        for (String value : files) {
            if (value == null || value.isBlank()) continue;
            Path path = Path.of(value.trim());
            Path relative = path.isAbsolute() ? projectRoot.relativize(path.normalize()) : path.normalize();
            if (relative.startsWith("..") || (path.isAbsolute() && !path.normalize().startsWith(projectRoot))) {
                throw new IllegalArgumentException("--files entry is outside the project: " + value);
            }
            if (!Files.isRegularFile(projectRoot.resolve(relative))) {
                err.println("Warning: --files entry does not exist: " + value);
            }
            normalized.add(relative.toString().replace('\\', '/'));
        }
        return Set.copyOf(normalized);
    }

    private static ChangedFileScope.ScopeApplication changedFileScope(
            Path projectRoot, AnalysisResult result, String changedSince)
            throws IOException, InterruptedException {
        var scope = new ChangedFileScope();
        return scope.apply(result, scope.filesChangedSince(projectRoot, changedSince));
    }

    private void writeReports(AnalysisResult result, Path outputDirectory, EnumSet<ReportFormat> formats)
            throws IOException {
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

    private static String identityConflictSummary(dev.diagscope.core.application.AnalysisStatistics statistics) {
        return statistics.identityConflicts() == 0 ? ""
                : "Identity conflicts: " + statistics.identityConflicts() + " (" + statistics.droppedMethods()
                        + " methods not analyzed) | ";
    }

    /** Everything the closing summary of a run reports. */
    private record RunSummary(
            AnalysisResult result,
            Path outputDirectory,
            String changedSince,
            int changeScopeExcludedFindings,
            int suppressedFindings,
            Path updatedBaseline,
            FindingBaseline.Lifecycle baselineLifecycle,
            FindingSuppressions.Application suppressions
    ) {}

    private static void printSummary(PrintStream out, RunSummary summary) {
        AnalysisResult result = summary.result();
        Path outputDirectory = summary.outputDirectory();
        String changedSince = summary.changedSince();
        int changeScopeExcludedFindings = summary.changeScopeExcludedFindings();
        int suppressedFindings = summary.suppressedFindings();
        Path updatedBaseline = summary.updatedBaseline();
        FindingBaseline.Lifecycle baselineLifecycle = summary.baselineLifecycle();
        FindingSuppressions.Application suppressions = summary.suppressions();
        var statistics = result.statistics();
        long boundaries = result.flows().stream().mapToLong(flow -> flow.boundaries().size()).sum();
        out.printf("DiagScope %s analyzed %d files, %d methods and %d flows in %d ms.%n",
                BuildInfo.version(), statistics.sourceFiles(), statistics.parsedMethods(), statistics.flows(),
                statistics.phaseMetrics().totalMillis());
        out.printf("Findings: %d | Parse failures: %d | %sFlow boundaries: %d | Output: %s%n",
                statistics.findings(), statistics.parseFailures(), identityConflictSummary(statistics), boundaries,
                outputDirectory);
        if (changeScopeExcludedFindings > 0) {
            out.printf("Change scope excluded %d finding(s) outside --changed-since %s.%n",
                    changeScopeExcludedFindings, changedSince);
        }
        if (suppressedFindings > 0) {
            out.printf("Baseline suppressed %d known finding(s).%n", suppressedFindings);
        }
        if (suppressions.suppressedFindings() > 0) {
            out.printf("Reviewed waivers hid %d finding(s) from diagscope.yml.%n",
                    suppressions.suppressedFindings());
        }
        if (!suppressions.expiredFingerprints().isEmpty()) {
            out.printf("Expired waiver(s) no longer hiding findings: %s%n",
                    String.join(", ", suppressions.expiredFingerprints()));
        }
        if (!suppressions.unusedFingerprints().isEmpty()) {
            out.printf("Unused waiver(s), the matching finding is gone: %s%n",
                    String.join(", ", suppressions.unusedFingerprints()));
        }
        if (updatedBaseline != null) {
            out.printf("Baseline updated: %s%n", updatedBaseline);
            if (baselineLifecycle.removedFindings() > 0 || baselineLifecycle.migratedFindings() > 0) {
                out.printf("Baseline lifecycle: %d removed finding(s), %d fingerprint migration(s).%n",
                        baselineLifecycle.removedFindings(), baselineLifecycle.migratedFindings());
            }
        }
    }
}
