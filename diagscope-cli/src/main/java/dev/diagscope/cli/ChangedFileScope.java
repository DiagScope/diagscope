package dev.diagscope.cli;

import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.domain.Finding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Resolves a Git revision to the project-relative files changed since that revision. */
final class ChangedFileScope {
    private static final Pattern SAFE_REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/@{}^~:+-]*");

    Set<String> filesChangedSince(Path projectRoot, String revision) throws IOException, InterruptedException {
        if (revision == null || !SAFE_REVISION.matcher(revision).matches()) {
            throw new IllegalArgumentException("Git revision contains unsupported characters: " + revision);
        }

        var process = new ProcessBuilder(
                "git", "-C", projectRoot.toString(), "diff", "--relative", "--name-only",
                "--diff-filter=ACMR", "-z", revision, "--"
        ).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            String detail = new String(output, StandardCharsets.UTF_8).trim();
            throw new IllegalArgumentException("Cannot resolve --changed-since " + revision
                    + (detail.isEmpty() ? "" : ": " + detail));
        }

        var files = new TreeSet<String>();
        Arrays.stream(new String(output, StandardCharsets.UTF_8).split("\u0000"))
                .filter(value -> !value.isBlank())
                .map(ChangedFileScope::normalize)
                .forEach(files::add);
        return Set.copyOf(files);
    }

    ScopeApplication apply(AnalysisResult result, Set<String> changedFiles) {
        AnalysisResult filtered = AnalysisResultFilter.retain(result,
                finding -> changedFiles.contains(relativePath(result.projectRoot(), finding)));
        return new ScopeApplication(filtered, result.findings().size() - filtered.findings().size());
    }

    private static String relativePath(Path projectRoot, Finding finding) {
        Path file = finding.location().file().normalize();
        if (file.isAbsolute()) {
            Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
            if (!file.startsWith(normalizedRoot)) {
                return normalize(file.toString());
            }
            file = normalizedRoot.relativize(file);
        }
        return normalize(file.toString());
    }

    private static String normalize(String value) {
        return Path.of(value).normalize().toString().replace('\\', '/');
    }

    record ScopeApplication(AnalysisResult result, int excludedFindings) {
    }
}
