package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;

import java.util.List;

public interface DiagnosticRule {
    String id();
    List<Finding> evaluate(Flow flow);
}
