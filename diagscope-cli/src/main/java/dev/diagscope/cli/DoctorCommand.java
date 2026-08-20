package dev.diagscope.cli;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.application.port.in.ScanProjectUseCase;
import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.EntrypointType;
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

        int warnings = 0;
        int failures  = 0;

        // ── Runtime ──────────────────────────────────────────────────────────
        out.println("Runtime");
        out.printf("  %s Java %s%n", OK, Runtime.version().feature());
        out.printf("  %s DiagScope %s%n", OK, BuildInfo.version());
        out.println();

        // ── Project ──────────────────────────────────────────────────────────
        out.println("Project");
        ProjectLayout layout;
        try {
            layout = ProjectLayoutDetector.detect(projectRoot,
                    resolveSourceRoots(projectRoot, sourceRoots));
        } catch (UnsupportedProjectException exception) {
            out.printf("  %s %s%n", FAIL, exception.getMessage());
            out.println();
            out.printf("Doctor found %d warning(s) and %d failure(s).%n", warnings, failures + 1);
            return 1;
        } catch (IllegalArgumentException exception) {
            out.printf("  %s %s%n", FAIL, exception.getMessage());
            out.println();
            out.printf("Doctor found %d warning(s) and %d failure(s).%n", warnings, failures + 1);
            return 1;
        }
        String buildLabel = buildLabel(layout);
        out.printf("  %s %s%n", OK, buildLabel);
        int moduleCount = layout.modules().size();
        out.printf("  %s %d module%s discovered%n", OK, moduleCount, moduleCount == 1 ? "" : "s");
        out.println();

        // ── Languages ────────────────────────────────────────────────────────
        out.println("Languages");
        long javaFiles  = countFiles(layout.sourceRoots(), path -> path.toString().endsWith(".java"));
        long kotlinFiles = countFiles(layout.sourceRoots(), path -> path.toString().endsWith(".kt"));
        if (javaFiles > 0) {
            out.printf("  %s Java: %d file%s%n", OK, javaFiles, javaFiles == 1 ? "" : "s");
        }
        if (kotlinFiles > 0) {
            out.printf("  %s Kotlin: %d file%s%n", OK, kotlinFiles, kotlinFiles == 1 ? "" : "s");
        }
        if (javaFiles == 0 && kotlinFiles == 0) {
            out.printf("  %s No Java or Kotlin source files found in discovered roots%n", WARN);
            warnings++;
        }
        out.println();

        // ── Sources ──────────────────────────────────────────────────────────
        out.println("Sources");
        for (Path sourceRoot : layout.sourceRoots()) {
            Path relative = projectRoot.relativize(sourceRoot);
            out.printf("  %s %s%n", OK, relative);
        }
        out.println();

        // ── Resolution ───────────────────────────────────────────────────────
        out.println("Resolution");
        boolean classpathConfigured = !classpath.isEmpty();
        out.printf("  %s Java local resolution%n", OK);
        if (classpathConfigured) {
            out.printf("  %s Java dependency classpath: %d entr%s%n",
                    OK, classpath.size(), classpath.size() == 1 ? "y" : "ies");
        } else {
            out.printf("  %s Java dependency classpath not configured "
                    + "(pass --classpath for external symbol resolution)%n", WARN);
            warnings++;
        }
        if (kotlinFiles > 0) {
            out.printf("  %s Kotlin source-level hierarchy%n", OK);
            out.printf("  %s Kotlin dependency resolution is source-first "
                    + "(compiled JARs not inspected)%n", WARN);
            warnings++;
        }
        out.println();

        // ── Framework capabilities ────────────────────────────────────────────
        out.println("Framework capabilities");
        String buildContent = readBuildContent(projectRoot);
        boolean springRest      = containsAny(buildContent, SPRING_REST_MARKERS);
        boolean springKafka     = containsAny(buildContent, SPRING_KAFKA_MARKERS);
        boolean springScheduling = containsAny(buildContent, SPRING_SCHEDULING_MARKERS);
        boolean springAop       = containsAny(buildContent, SPRING_AOP_MARKERS);
        boolean springTx        = containsAny(buildContent, SPRING_TX_MARKERS);
        boolean micrometer      = containsAny(buildContent, MICROMETER_MARKERS);

        boolean anyFramework = springRest || springKafka || springScheduling
                || springAop || springTx || micrometer;

        boolean anySpring = buildContent.toLowerCase(java.util.Locale.ROOT).contains("spring");
        if (!anyFramework) {
            if (anySpring) {
                // Spring detected but no specific module identified — informational, not a warning
                out.printf("  %s Spring Boot detected (specific module capabilities not identified)%n", OK);
            } else {
                // No Spring at all — neutral notice, DiagScope still analyzes the project
                out.printf("  — No Spring/Micrometer dependencies detected; "
                        + "framework-specific rules will not apply%n");
            }
        } else {
            printCapability(out, springRest,       "Spring REST");
            printCapability(out, springKafka,      "Spring Kafka");
            printCapability(out, springScheduling, "Spring Scheduling");
            printCapability(out, springAop,        "Spring AOP");
            printCapability(out, springTx,         "Transactions");
            printCapability(out, micrometer,       "Micrometer");
        }
        out.println();

        // ── Configuration ─────────────────────────────────────────────────────
        out.println("Configuration");
        Path configFile = resolveConfiguration(projectRoot, configuration);
        if (configFile != null && Files.isRegularFile(configFile)) {
            out.printf("  %s %s (schema %s)%n",
                    OK, projectRoot.relativize(configFile), ProjectConfigurationLoader.SCHEMA_VERSION);
            try {
                var loader = new ProjectConfigurationLoader();
                var loaded = loader.load(configFile);
                int totalRules   = RuleCatalog.all().size();
                int disabledRules = loaded.policy().disabledRules().size();
                int overridden    = loaded.policy().severityOverrides().size();
                int enabled = totalRules - disabledRules;
                out.printf("  %s %d rule%s enabled%n", OK, enabled, enabled == 1 ? "" : "s");
                if (overridden > 0) {
                    out.printf("  %s %d rule%s with severity override%n",
                            OK, overridden, overridden == 1 ? "" : "s");
                }
                if (!loaded.policy().disabledRules().isEmpty()) {
                    out.printf("  %s %d rule%s disabled%n",
                            WARN, disabledRules, disabledRules == 1 ? "" : "s");
                    warnings++;
                }
            } catch (IOException | IllegalArgumentException exception) {
                out.printf("  %s Configuration error: %s%n", WARN, exception.getMessage());
                warnings++;
            }
        } else {
            int totalRules = RuleCatalog.all().size();
            out.printf("  %s No diagscope.yml found — using defaults (%d rules enabled)%n",
                    OK, totalRules);
        }
        out.println();

        // ── Scan health (optional) ────────────────────────────────────────────
        if (withScanHealth) {
            out.println("Scan health");
            try {
                var configFile2 = resolveConfiguration(projectRoot, configuration);
                AnalysisPolicy policy;
                if (configFile2 != null && Files.isRegularFile(configFile2)) {
                    policy = new ProjectConfigurationLoader().load(configFile2).policy();
                } else {
                    policy = AnalysisPolicy.defaults();
                }
                var options = new AnalysisOptions(
                        AnalysisOptions.defaults().maxFlowDepth(),
                        AnalysisOptions.defaults().parallelism(),
                        EnumSet.allOf(EntrypointType.class),
                        policy,
                        resolveClasspath(projectRoot, classpath),
                        resolveSourceRoots(projectRoot, sourceRoots)
                );
                AnalysisResult result = scanUseCase.scan(new AnalysisRequest(projectRoot, options));
                long parseFailures = result.parseFailures().size();
                long unresolvedBoundaries = result.flows().stream()
                        .flatMap(f -> f.boundaries().stream())
                        .filter(edge -> edge.resolutionReason() == ResolutionReason.UNRESOLVED)
                        .count();
                long ambiguousBoundaries = result.flows().stream()
                        .flatMap(f -> f.boundaries().stream())
                        .filter(edge -> edge.resolutionReason() == ResolutionReason.AMBIGUOUS)
                        .count();

                String failureIcon = parseFailures == 0 ? OK : WARN;
                out.printf("  %s %d parse failure%s%n",
                        failureIcon, parseFailures, parseFailures == 1 ? "" : "s");
                if (parseFailures > 0) warnings++;

                String unresolvedIcon = unresolvedBoundaries == 0 ? OK : WARN;
                out.printf("  %s %d unresolved boundar%s%n",
                        unresolvedIcon, unresolvedBoundaries,
                        unresolvedBoundaries == 1 ? "y" : "ies");

                String ambiguousIcon = ambiguousBoundaries == 0 ? OK : WARN;
                out.printf("  %s %d ambiguous boundar%s%n",
                        ambiguousIcon, ambiguousBoundaries,
                        ambiguousBoundaries == 1 ? "y" : "ies");

                out.printf("  %s %d entrypoint%s, %d flow%s analyzed%n",
                        OK, result.statistics().entrypoints(),
                        result.statistics().entrypoints() == 1 ? "" : "s",
                        result.statistics().flows(),
                        result.statistics().flows() == 1 ? "" : "s");
            } catch (UnsupportedProjectException exception) {
                out.printf("  %s Scan failed: %s%n", FAIL, exception.getMessage());
                failures++;
            } catch (Exception exception) {
                out.printf("  %s Scan health check failed: %s%n", WARN, exception.getMessage());
                warnings++;
            }
            out.println();
        }

        // ── Summary ───────────────────────────────────────────────────────────
        if (warnings == 0 && failures == 0) {
            out.printf("Doctor found no issues. DiagScope is ready to scan this project.%n");
            return 0;
        }
        String summary = failures > 0
                ? String.format("Doctor found %d warning(s) and %d failure(s). "
                        + "Review the items marked %s above.", warnings, failures, WARN + "/" + FAIL)
                : String.format("Doctor found %d warning(s). "
                        + "Review the items marked %s above.", warnings, WARN);
        out.println(summary);
        return failures > 0 ? 1 : 0;
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
                // unreadable root — skip silently, parse failure will surface in scan health
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
                    // best-effort
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
