package dev.diagscope.core.application.port.out;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.Flow;

public interface FlowBuilder {
    Flow build(AnalyzedProject project, Entrypoint entrypoint, int maxDepth);
}
