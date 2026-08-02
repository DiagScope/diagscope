package dev.diagscope.cli;

import dev.diagscope.cli.report.AnalysisReporter;
import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.application.port.in.ScanProjectUseCase;
import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.domain.EntrypointType;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(
        name = "scan",
        mixinStandardHelpOptions = true,
        description = "Analyze one Maven module for diagnostic coverage gaps."
)
public final class ScanCommand implements Callable<Integer> {
    private final ScanProjectUseCase useCase;
    private final Map<ReportFormat, AnalysisReporter> reporters;

    @Option(names = {"-p", "--project"}, required = true, description = "Maven module directory")
    private Path project;

    @Option(names = {"-o", "--output"}, description = "Output directory, relative to the project by default", defaultValue = "target/diagscope")
    private Path output;

    @Option(names = "--max-depth", defaultValue = "3", description = "Maximum local call depth, from 0 to 32")
    private int maxDepth;

    @Option(names = "--parallelism", defaultValue = "${sys:diagscope.parallelism:-0}", description = "Parser workers; 0 selects an automatic bounded value")
    private int parallelism;

    @Option(names = "--format", split = ",", defaultValue = "MARKDOWN,JSON", description = "MARKDOWN, JSON, or both")
    private EnumSet<ReportFormat> formats;

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
            var options = new AnalysisOptions(maxDepth, workers, entrypointTypes);
            Path outputDirectory = resolveOutputDirectory(projectRoot);
            var result = useCase.scan(new AnalysisRequest(projectRoot, options));

            writeReports(result, outputDirectory);
            printSummary(result, outputDirectory);
            return 0;
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

    private static void printSummary(AnalysisResult result, Path outputDirectory) {
        var statistics = result.statistics();
        long boundaries = result.flows().stream().mapToLong(flow -> flow.boundaries().size()).sum();
        System.out.printf("DiagScope %s analyzed %d files, %d methods and %d flows in %d ms.%n",
                BuildInfo.version(), statistics.sourceFiles(), statistics.parsedMethods(), statistics.flows(),
                statistics.phaseMetrics().totalMillis());
        System.out.printf("Findings: %d | Parse failures: %d | Flow boundaries: %d | Output: %s%n",
                statistics.findings(), statistics.parseFailures(), boundaries, outputDirectory);
    }
}
