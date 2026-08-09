package dev.diagscope.core.application.rule;

import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;

import java.util.List;

public interface DiagnosticRule {
    String id();
    List<Finding> evaluate(Flow flow);

    /** Policy-aware hook; rules without configurable evidence keep their original implementation. */
    default List<Finding> evaluate(Flow flow, AnalysisPolicy policy) {
        return evaluate(flow);
    }
}
