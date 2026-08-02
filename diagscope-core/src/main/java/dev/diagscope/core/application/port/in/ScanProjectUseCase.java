package dev.diagscope.core.application.port.in;

import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.AnalysisResult;

public interface ScanProjectUseCase {
    AnalysisResult scan(AnalysisRequest request);
}
