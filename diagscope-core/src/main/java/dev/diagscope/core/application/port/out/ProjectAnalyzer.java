package dev.diagscope.core.application.port.out;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.domain.AnalyzedProject;

import java.nio.file.Path;

public interface ProjectAnalyzer {
    AnalyzedProject analyze(Path projectDirectory, AnalysisOptions options);
}
