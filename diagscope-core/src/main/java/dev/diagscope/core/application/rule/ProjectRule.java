package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Finding;

import java.util.List;

/**
 * A rule that evaluates the analyzed project as a whole rather than per-flow.
 *
 * <p>Use {@code ProjectRule} for patterns that require a project-wide view, such as class complexity
 * metrics, global naming consistency, or cross-cutting structural concerns that cannot be expressed
 * from within a single call flow.</p>
 *
 * <p>Findings produced by a {@code ProjectRule} carry no related-flow context — they are attached
 * to a source location (typically the first method of the affected type) and carry the evidence
 * fields set by the rule itself.</p>
 */
public interface ProjectRule {

    /** The stable rule identifier, must match the corresponding {@link RuleCatalog} entry. */
    String id();

    /**
     * Evaluates the complete analyzed project and returns zero or more findings.
     *
     * @param project the fully analyzed project, including all methods and their metadata
     * @return an immutable list of findings; never {@code null}
     */
    List<Finding> evaluate(AnalyzedProject project);
}
