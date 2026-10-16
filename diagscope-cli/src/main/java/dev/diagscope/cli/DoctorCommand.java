package dev.diagscope.cli;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.application.port.in.ScanProjectUseCase;
import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.ProjectLayout;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.jvmanalysis.ProjectLayoutDetector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Pre-flight diagnostic that answers the critical question before a full scan:
 * <em>"Did DiagScope actually understand my project?"</em>
 *
 * <p>The command inspects the project without running a full analysis unless
 * {@code --with-scan-health} is supplied. That lightweight path makes it safe to run
 * before every scan in CI without adding meaningful latency.</p>
 */
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Inspect a project and report what DiagScope understands about it."
)
public final class DoctorCommand implements Callable<Integer> {

    // ── check symbols ─────────────────────────────────────────────────────────
    private static final String OK   = "✓";
    private static final String WARN = "⚠";
    private static final String FAIL = "✗";

    // ── Spring/Micrometer dependency markers (pom.xml and Gradle) ────────────
    private static final Set<String> SPRING_REST_MARKERS = Set.of(
            "spring-webmvc", "spring-web", "spring-boot-starter-web", "spring-boot-starter-webflux",
            "spring-web-", "webmvc"
    );
    private static final Set<String> SPRING_KAFKA_MARKERS = Set.of(
            "spring-kafka", "spring-boot-starter-kafka"
    );
    private static final Set<String> SPRING_SCHEDULING_MARKERS = Set.of(
            "spring-context", "spring-boot-starter", "spring-boot-autoconfigure"
    );
    private static final Set<String> SPRING_AOP_MARKERS = Set.of(
            "spring-aop", "spring-aspects", "spring-boot-starter-aop"
    );
    private static final Set<String> SPRING_TX_MARKERS = Set.of(
            "spring-tx", "spring-boot-starter-data-jpa", "spring-boot-starter-jdbc",
            "spring-boot-starter-data-r2dbc"
    );
    private static final Set<String> MICROMETER_MARKERS = Set.of(
            "micrometer-core", "micrometer-registry", "spring-boot-starter-actuator"
    );

    // ── command options ───────────────────────────────────────────────────────
    @Option(names = {"-p", "--project"}, required = true, description = "Maven or Gradle project directory")
    private Path project;

    @Option(names = "--classpath", split = ",",
            description = "Explicit dependency JARs or class directories; improves Java resolution reporting")
    private List<Path> classpath = List.of();

    @Option(names = "--source-root", split = ",",
            description = "Additional production source roots")
    private List<Path> sourceRoots = List.of();

    @Option(names = "--config", description = "Project configuration file; defaults to diagscope.yml when present")
    private Path configuration;

    @Option(names = "--with-scan-health",
            description = "Run a lightweight scan and show parse-failure and boundary statistics")
    private boolean withScanHealth;

    // ── injected by DiagScopeMain so scan health can reuse the real engine ───
    private final ScanProjectUseCase scanUseCase;

    public DoctorCommand(ScanProjectUseCase scanUseCase) {
        this.scanUseCase = scanUseCase;
    }

    // ── entry point ───────────────────────────────────────────────────────────

    @Override
    public Integer call() {
        return call(System.out, System.err);
    }

    /** Testable variant that writes to caller-supplied streams. */
    int call(PrintStream out, PrintStream err) {
        Path projectRoot = project.toAbsolutePath().normalize();
        out.println("DiagScope Doctor");
        out.println();

        var tally = new Tally();
        printRuntime(out);
        ProjectLayout layout = detectLayout(out, projectRoot, tally);
        if (layout == null) {
            out.printf("Doctor found %d warning(s) and %d failure(s).%n", tally.warnings, tally.failures);
            return 1;
        }
        printProject(out, layout);
        long kotlinFiles = printLanguages(out, layout, tally);
        printSources(out, projectRoot, layout);
        printResolution(out, kotlinFiles, tally);
        printFrameworkCapabilities(out, projectRoot);
        printConfiguration(out, projectRoot, tally);
        if (withScanHealth) {
            printScanHealth(out, projectRoot, tally);
        }
        return printSummary(out, tally);
    }

    /** Warnings and failures found so far; doctor exits non-zero only on failures. */
    private static final class Tally {
        int warnings;
        int failures;
    }

    // ── sections ──────────────────────────────────────────────────────────────

    private static void printRuntime(PrintStream out) {
        out.println("Runtime");
        out.printf("  %s Java %s%n", OK, Runtime.version().feature());
        out.printf("  %s DiagScope %s%n", OK, BuildInfo.version());
        out.println();
    }

    /** Detects the project layout, or reports why it cannot and returns {@code null}. */
    private ProjectLayout detectLayout(PrintStream out, Path projectRoot, Tally tally) {
        out.println("Project");
        try {
            return ProjectLayoutDetector.detect(projectRoot, resolveSourceRoots(projectRoot, sourceRoots));
        } catch (UnsupportedProjectException | IllegalArgumentException exception) {
            out.printf("  %s %s%n", FAIL, exception.getMessage());
            out.println();
            tally.failures++;
            return null;
        }
    }

    private static void printProject(PrintStream out, ProjectLayout layout) {
        out.printf("  %s %s%n", OK, buildLabel(layout));
        int moduleCount = layout.modules().size();
        out.printf("  %s %d module%s discovered%n", OK, moduleCount, plural(moduleCount));
        out.println();
    }

    /** Prints the language breakdown and returns the number of Kotlin files. */
    private static long printLanguages(PrintStream out, ProjectLayout layout, Tally tally) {
        out.println("Languages");
        long javaFiles = countFiles(layout.sourceRoots(), path -> path.toString().endsWith(".java"));
        long kotlinFiles = countFiles(layout.sourceRoots(), path -> path.toString().endsWith(".kt"));
        if (javaFiles > 0) {
            out.printf("  %s Java: %d file%s%n", OK, javaFiles, plural(javaFiles));
        }
        if (kotlinFiles > 0) {
            out.printf("  %s Kotlin: %d file%s%n", OK, kotlinFiles, plural(kotlinFiles));
        }
        if (javaFiles == 0 && kotlinFiles == 0) {
            out.printf("  %s No Java or Kotlin source files found in discovered roots%n", WARN);
            tally.warnings++;
        }
        out.println();
        return kotlinFiles;
    }

    private static void printSources(PrintStream out, Path projectRoot, ProjectLayout layout) {
        out.println("Sources");
        for (Path sourceRoot : layout.sourceRoots()) {
            out.printf("  %s %s%n", OK, projectRoot.relativize(sourceRoot));
        }
        out.println();
    }

    private void printResolution(PrintStream out, long kotlinFiles, Tally tally) {
        out.println("Resolution");
        out.printf("  %s Java local resolution%n", OK);
        if (classpath.isEmpty()) {
            out.printf("  %s Java dependency classpath not configured "
                    + "(pass --classpath for external symbol resolution)%n", WARN);
            tally.warnings++;
        } else {
            out.printf("  %s Java dependency classpath: %d entr%s%n",
                    OK, classpath.size(), pluralY(classpath.size()));
        }
        if (kotlinFiles > 0) {
            out.printf("  %s Kotlin source-level hierarchy%n", OK);
            out.printf("  %s Kotlin dependency resolution is source-first "
                    + "(compiled JARs not inspected)%n", WARN);
            tally.warnings++;
        }
        out.println();
    }

    private static void printFrameworkCapabilities(PrintStream out, Path projectRoot) {
        out.println("Framework capabilities");
        String buildContent = readBuildContent(projectRoot);
        boolean springRest = containsAny(buildContent, SPRING_REST_MARKERS);
        boolean springKafka = containsAny(buildContent, SPRING_KAFKA_MARKERS);
        boolean springScheduling = containsAny(buildContent, SPRING_SCHEDULING_MARKERS);
        boolean springAop = containsAny(buildContent, SPRING_AOP_MARKERS);
        boolean springTx = containsAny(buildContent, SPRING_TX_MARKERS);
        boolean micrometer = containsAny(buildContent, MICROMETER_MARKERS);

        boolean anyFramework = springRest || springKafka || springScheduling
                || springAop || springTx || micrometer;
        if (anyFramework) {
            printCapability(out, springRest, "Spring REST");
            printCapability(out, springKafka, "Spring Kafka");
            printCapability(out, springScheduling, "Spring Scheduling");
            printCapability(out, springAop, "Spring AOP");
            printCapability(out, springTx, "Transactions");
            printCapability(out, micrometer, "Micrometer");
        } else if (buildContent.toLowerCase(java.util.Locale.ROOT).contains("spring")) {
            // Spring detected but no specific module identified: informational, not a warning
            out.printf("  %s Spring Boot detected (specific module capabilities not identified)%n", OK);
        } else {
            // No Spring at all: neutral notice, DiagScope still analyzes the project
            out.printf("  — No Spring/Micrometer dependencies detected; "
                    + "framework-specific rules will not apply%n");
        }
        out.println();
    }

    private void printConfiguration(PrintStream out, Path projectRoot, Tally tally) {
        out.println("Configuration");
        Path configFile = resolveConfiguration(projectRoot, configuration);
        if (configFile != null && Files.isRegularFile(configFile)) {
            out.printf("  %s %s (schema %s)%n",
                    OK, projectRoot.relativize(configFile), ProjectConfigurationLoader.SCHEMA_VERSION);
            try {
                printPolicySummary(out, new ProjectConfigurationLoader().load(configFile).policy(), tally);
            } catch (IOException | IllegalArgumentException exception) {
                out.printf("  %s Configuration error: %s%n", WARN, exception.getMessage());
                tally.warnings++;
            }
        } else {
            out.printf("  %s No diagscope.yml found — using defaults (%d rules enabled)%n",
                    OK, RuleCatalog.all().size());
        }
        out.println();
    }

    private static void printPolicySummary(PrintStream out, AnalysisPolicy policy, Tally tally) {
        int disabledRules = policy.disabledRules().size();
        int overridden = policy.severityOverrides().size();
        int enabled = RuleCatalog.all().size() - disabledRules;
        out.printf("  %s %d rule%s enabled%n", OK, enabled, plural(enabled));
        if (overridden > 0) {
            out.printf("  %s %d rule%s with severity override%n", OK, overridden, plural(overridden));
        }
        if (!policy.disabledRules().isEmpty()) {
            out.printf("  %s %d rule%s disabled%n", WARN, disabledRules, plural(disabledRules));
            tally.warnings++;
        }
    }

    private void printScanHealth(PrintStream out, Path projectRoot, Tally tally) {
        out.println("Scan health");
        try {
            AnalysisResult result = scanUseCase.scan(new AnalysisRequest(projectRoot, scanHealthOptions(projectRoot)));
            printScanHealthResult(out, result, tally);
        } catch (UnsupportedProjectException exception) {
            out.printf("  %s Scan failed: %s%n", FAIL, exception.getMessage());
            tally.failures++;
        } catch (Exception exception) {
            out.printf("  %s Scan health check failed: %s%n", WARN, exception.getMessage());
            tally.warnings++;
        }
        out.println();
    }

    private AnalysisOptions scanHealthOptions(Path projectRoot) throws IOException {
        Path configFile = resolveConfiguration(projectRoot, configuration);
        AnalysisPolicy policy = configFile != null && Files.isRegularFile(configFile)
                ? new ProjectConfigurationLoader().load(configFile).policy()
                : AnalysisPolicy.defaults();
        return new AnalysisOptions(
                AnalysisOptions.defaults().maxFlowDepth(),
                AnalysisOptions.defaults().parallelism(),
                EnumSet.copyOf(AnalysisOptions.DEFAULT_ENTRYPOINT_TYPES),
                policy,
                resolveClasspath(projectRoot, classpath),
                resolveSourceRoots(projectRoot, sourceRoots));
    }

    private static void printScanHealthResult(PrintStream out, AnalysisResult result, Tally tally) {
        long parseFailures = result.parseFailures().size();
        long unresolved = boundaries(result, ResolutionReason.UNRESOLVED);
        long ambiguous = boundaries(result, ResolutionReason.AMBIGUOUS);

        out.printf("  %s %d parse failure%s%n", parseFailures == 0 ? OK : WARN, parseFailures, plural(parseFailures));
        if (parseFailures > 0) tally.warnings++;
        out.printf("  %s %d unresolved boundar%s%n", unresolved == 0 ? OK : WARN, unresolved, pluralY(unresolved));
        out.printf("  %s %d ambiguous boundar%s%n", ambiguous == 0 ? OK : WARN, ambiguous, pluralY(ambiguous));
        out.printf("  %s %d entrypoint%s, %d flow%s analyzed%n",
                OK, result.statistics().entrypoints(), plural(result.statistics().entrypoints()),
                result.statistics().flows(), plural(result.statistics().flows()));
    }

    private static long boundaries(AnalysisResult result, ResolutionReason reason) {
        return result.flows().stream()
                .flatMap(flow -> flow.boundaries().stream())
                .filter(edge -> edge.resolutionReason() == reason)
                .count();
    }

    /** Prints the closing line and returns the exit code. */
    private static int printSummary(PrintStream out, Tally tally) {
        if (tally.warnings == 0 && tally.failures == 0) {
            out.printf("Doctor found no issues. DiagScope is ready to scan this project.%n");
            return 0;
        }
        String summary = tally.failures > 0
                ? String.format("Doctor found %d warning(s) and %d failure(s). "
                        + "Review the items marked %s above.", tally.warnings, tally.failures, WARN + "/" + FAIL)
                : String.format("Doctor found %d warning(s). "
                        + "Review the items marked %s above.", tally.warnings, WARN);
        out.println(summary);
        return tally.failures > 0 ? 1 : 0;
    }

    private static String plural(long count) {
        return count == 1 ? "" : "s";
    }

    private static String pluralY(long count) {
        return count == 1 ? "y" : "ies";
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String buildLabel(ProjectLayout layout) {
        BuildSystem bs = layout.buildSystem();
        boolean multi = layout.isMultiModule();
        return switch (bs) {
            case MAVEN         -> multi ? "Maven multi-module" : "Maven single-module";
            case GRADLE        -> multi ? "Gradle multi-module" : "Gradle single-module";
            case MAVEN_AND_GRADLE -> multi ? "Maven + Gradle multi-module" : "Maven + Gradle";
        };
    }

    private static long countFiles(List<Path> roots, Predicate<Path> filter) {
        long count = 0;
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                count += walk.filter(Files::isRegularFile).filter(filter).count();
            } catch (IOException ignored) {
                // diagscope:ignore SILENT_CATCH -- an unreadable root is skipped on purpose; real parse failures surface in scan health
            }
        }
        return count;
    }

    /** Reads all build descriptor content from the project root (non-recursive, fast). */
    private static String readBuildContent(Path root) {
        var builder = new StringBuilder();
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts")) {
            Path descriptor = root.resolve(name);
            if (Files.isRegularFile(descriptor)) {
                try {
                    builder.append(Files.readString(descriptor)).append('\n');
                } catch (IOException ignored) {
                    // diagscope:ignore SILENT_CATCH -- best-effort read: a missing descriptor only reduces what doctor reports
                }
            }
        }
        return builder.toString();
    }

    private static boolean containsAny(String content, Set<String> markers) {
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        return markers.stream().anyMatch(marker -> lower.contains(marker.toLowerCase(java.util.Locale.ROOT)));
    }

    private static void printCapability(PrintStream out, boolean detected, String label) {
        if (detected) {
            out.printf("  %s %s%n", OK, label);
        }
    }

    private static Path resolveConfiguration(Path projectRoot, Path configured) {
        if (configured != null) {
            return configured.isAbsolute() ? configured : projectRoot.resolve(configured);
        }
        Path defaultConfig = projectRoot.resolve(ProjectConfigurationLoader.DEFAULT_FILE_NAME);
        return Files.isRegularFile(defaultConfig) ? defaultConfig : null;
    }

    private static List<Path> resolveSourceRoots(Path projectRoot, List<Path> roots) {
        var resolved = new ArrayList<Path>(roots.size());
        for (Path root : roots) {
            resolved.add(root.isAbsolute() ? root : projectRoot.resolve(root).normalize());
        }
        return List.copyOf(resolved);
    }

    private static List<Path> resolveClasspath(Path projectRoot, List<Path> entries) {
        var resolved = new ArrayList<Path>(entries.size());
        for (Path entry : entries) {
            resolved.add(entry.isAbsolute() ? entry : projectRoot.resolve(entry).normalize());
        }
        return List.copyOf(resolved);
    }
}
