package dev.diagscope.cli.report;

import dev.diagscope.core.domain.SourceLine;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemSourceSnippetProviderTest {
    private final FileSystemSourceSnippetProvider provider = new FileSystemSourceSnippetProvider();

    @TempDir
    Path temp;

    @Test
    void reads_context_lines_around_the_finding_and_normalizes_windows_line_endings() throws Exception {
        Path file = write("Example.java", "one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\r\n");

        var snippet = provider.read(temp, new SourceLocation(temp.relativize(file), 3, 4), 1).orElseThrow();

        assertThat(snippet.lines()).extracting(SourceLine::content)
                .containsExactly("two", "three", "four", "five");
        assertThat(snippet.firstLineNumber()).isEqualTo(2);
        assertThat(snippet.lastLineNumber()).isEqualTo(5);
        assertThat(snippet.highlightedStartLine()).isEqualTo(3);
        assertThat(snippet.highlightedEndLine()).isEqualTo(4);
    }

    @Test
    void clamps_the_range_to_the_file_and_preserves_unicode() throws Exception {
        Path file = write("Unicode.java", "// caf\u00e9 \u2713\nsecond\n");

        var snippet = provider.read(temp, new SourceLocation(temp.relativize(file), 1, 1), 10).orElseThrow();

        assertThat(snippet.lines()).extracting(SourceLine::content).containsExactly("// caf\u00e9 \u2713", "second");
    }

    @Test
    void returns_empty_for_missing_files_and_out_of_range_locations() throws Exception {
        Path file = write("Short.java", "only one line\n");

        assertThat(provider.read(temp, new SourceLocation(Path.of("Missing.java"), 1, 1), 2)).isEmpty();
        assertThat(provider.read(temp, new SourceLocation(temp.relativize(file), 40, 42), 2)).isEmpty();
        assertThat(provider.read(null, new SourceLocation(temp.relativize(file), 1, 1), 2)).isEmpty();
    }

    @Test
    void skips_files_larger_than_the_configured_limit() throws Exception {
        Path file = write("Big.java", "a".repeat(64) + "\n");

        assertThat(new FileSystemSourceSnippetProvider(8)
                .read(temp, new SourceLocation(temp.relativize(file), 1, 1), 0)).isEmpty();
    }

    private Path write(String name, String content) throws Exception {
        Path file = temp.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
