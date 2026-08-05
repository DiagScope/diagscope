package dev.diagscope.core.domain;

import java.util.Objects;

/** A single physical source line, without its line terminator. */
public record SourceLine(int lineNumber, String content) {
    public SourceLine {
        Objects.requireNonNull(content, "content");
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be positive: " + lineNumber);
        }
    }

    public boolean highlighted(SourceSnippet snippet) {
        Objects.requireNonNull(snippet, "snippet");
        return lineNumber >= snippet.highlightedStartLine() && lineNumber <= snippet.highlightedEndLine();
    }
}
