package dev.diagscope.cli.report;

import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;

import java.io.IOException;
import java.io.OutputStream;

/** Serializes an analysis result without exposing presentation concerns to the core. */
public interface AnalysisReporter {
    ReportFormat format();

    void write(AnalysisResult result, OutputStream output) throws IOException;
}
