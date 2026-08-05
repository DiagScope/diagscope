package dev.diagscope.cli.report;

import dev.diagscope.core.application.port.out.SourceSnippetProvider;
import dev.diagscope.core.domain.SourceLine;
import dev.diagscope.core.domain.SourceLocation;
import dev.diagscope.core.domain.SourceSnippet;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Reads snippets from the local filesystem.
 *
 * <p>Reading is deliberately defensive: the report must still render when a file was deleted,
 * renamed, or is not valid UTF-8. Line endings are normalized (CRLF, CR, LF) and very long or very
 * large files are skipped so a report never embeds megabytes of minified or generated source.</p>
 */
public final class FileSystemSourceSnippetProvider implements SourceSnippetProvider {
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_LINE_CHARACTERS = 400;
    private static final String TRUNCATION_MARKER = " \u2026";

    private final long maxFileBytes;

    public FileSystemSourceSnippetProvider() {
        this(MAX_FILE_BYTES);
    }

    FileSystemSourceSnippetProvider(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public Optional<SourceSnippet> read(Path projectRoot, SourceLocation location, int contextLines) {
        if (projectRoot == null || location == null || contextLines < 0) {
            return Optional.empty();
        }
        Path file = resolve(projectRoot, location);
        if (file == null) {
            return Optional.empty();
        }
        String[] allLines = readLines(file);
        if (allLines == null || allLines.length == 0) {
            return Optional.empty();
        }
        int start = Math.max(1, location.startLine() - contextLines);
        int end = Math.min(allLines.length, location.endLine() + contextLines);
        if (location.startLine() > allLines.length || end < start) {
            return Optional.empty();
        }
        var lines = new ArrayList<SourceLine>(end - start + 1);
        for (int number = start; number <= end; number++) {
            lines.add(new SourceLine(number, clamp(allLines[number - 1])));
        }
        int highlightEnd = Math.min(location.endLine(), allLines.length);
        return Optional.of(new SourceSnippet(lines, location.startLine(), highlightEnd));
    }

    private static Path resolve(Path projectRoot, SourceLocation location) {
        try {
            Path file = location.file();
            Path resolved = (file.isAbsolute() ? file : projectRoot.resolve(file)).normalize();
            return Files.isRegularFile(resolved) && Files.isReadable(resolved) ? resolved : null;
        } catch (InvalidPathException | SecurityException exception) {
            return null;
        }
    }

    private String[] readLines(Path file) {
        try {
            if (Files.size(file) > maxFileBytes) {
                return null;
            }
            String text = decode(Files.readAllBytes(file));
            if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            return text.split("\n", -1);
        } catch (IOException | SecurityException | OutOfMemoryError exception) {
            return null;
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String clamp(String line) {
        String withoutTabs = line.replace("\t", "    ");
        return withoutTabs.length() <= MAX_LINE_CHARACTERS
                ? withoutTabs
                : withoutTabs.substring(0, MAX_LINE_CHARACTERS) + TRUNCATION_MARKER;
    }
}
