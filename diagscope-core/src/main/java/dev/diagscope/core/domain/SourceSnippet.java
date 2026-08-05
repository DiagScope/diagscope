package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * A short excerpt of a source file surrounding a finding.
 *
 * <p>The excerpt carries raw text only. Escaping is the responsibility of the reporter that
 * renders it, so the same snippet can be emitted as HTML, Markdown, or plain console output.</p>
 */
public record SourceSnippet(List<SourceLine> lines, int highlightedStartLine, int highlightedEndLine) {
    public SourceSnippet {
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A snippet must contain at least one line");
        }
        if (highlightedStartLine < 1 || highlightedEndLine < highlightedStartLine) {
            throw new IllegalArgumentException(
                    "Invalid highlight range: " + highlightedStartLine + "-" + highlightedEndLine);
        }
    }

    public int firstLineNumber() {
        return lines.getFirst().lineNumber();
    }

    public int lastLineNumber() {
        return lines.getLast().lineNumber();
    }
}
