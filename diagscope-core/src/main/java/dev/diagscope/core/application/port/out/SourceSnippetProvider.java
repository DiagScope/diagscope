package dev.diagscope.core.application.port.out;

import dev.diagscope.core.domain.SourceLocation;
import dev.diagscope.core.domain.SourceSnippet;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads a small excerpt of source code around a finding.
 *
 * <p>Implementations must never fail an analysis: unreadable, missing, binary, or truncated files
 * yield {@link Optional#empty()} instead of an exception.</p>
 */
public interface SourceSnippetProvider {
    SourceSnippetProvider NONE = (projectRoot, location, contextLines) -> Optional.empty();

    Optional<SourceSnippet> read(Path projectRoot, SourceLocation location, int contextLines);
}
