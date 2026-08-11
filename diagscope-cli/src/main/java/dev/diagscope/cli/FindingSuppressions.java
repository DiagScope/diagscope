package dev.diagscope.cli;

import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.domain.Finding;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reviewed waivers declared in the project configuration file.
 *
 * <p>A baseline records the whole current state of a project so a team can adopt DiagScope without
 * fixing everything first. A waiver is the opposite: an explicit, reviewed decision about one
 * finding. It therefore requires a fingerprint, a written reason, and optionally an expiry date.
 * Expired and unused waivers are reported so the file cannot rot silently.</p>
 */
public final class FindingSuppressions {

    /** One reviewed waiver for a single finding fingerprint. */
    public record Waiver(String fingerprint, String reason, LocalDate expires) {
        public Waiver {
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(reason, "reason");
            fingerprint = fingerprint.startsWith("sha256:") ? fingerprint.substring("sha256:".length()) : fingerprint;
            if (!fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "suppression fingerprint must be a 64 character sha256 value: " + fingerprint);
            }
            if (reason.isBlank()) {
                throw new IllegalArgumentException("suppression reason must not be blank: " + fingerprint);
            }
        }

        boolean expiredOn(LocalDate today) {
            return expires != null && expires.isBefore(today);
        }
    }

    /** Outcome of applying the waivers to an analysis result. */
    public record Application(
            AnalysisResult result,
            int suppressedFindings,
            List<String> expiredFingerprints,
            List<String> unusedFingerprints
    ) {
        public Application {
            expiredFingerprints = List.copyOf(expiredFingerprints);
            unusedFingerprints = List.copyOf(unusedFingerprints);
        }
    }

    private final LocalDate today;

    public FindingSuppressions() {
        this(LocalDate.now());
    }

    public FindingSuppressions(LocalDate today) {
        this.today = Objects.requireNonNull(today, "today");
    }

    /** Removes findings covered by an active waiver and reports expired or stale entries. */
    public Application apply(AnalysisResult result, List<Waiver> waivers) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(waivers, "waivers");
        if (waivers.isEmpty()) {
            return new Application(result, 0, List.of(), List.of());
        }

        var byFingerprint = new LinkedHashMap<String, Waiver>();
        for (Waiver waiver : waivers) {
            Waiver previous = byFingerprint.putIfAbsent(waiver.fingerprint(), waiver);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate suppression fingerprint: " + waiver.fingerprint());
            }
        }

        var expired = new LinkedHashSet<String>();
        var matched = new LinkedHashSet<String>();
        var filtered = AnalysisResultFilter.retain(result, finding -> {
            Waiver waiver = byFingerprint.get(fingerprint(finding));
            if (waiver == null) {
                return true;
            }
            matched.add(waiver.fingerprint());
            if (waiver.expiredOn(today)) {
                expired.add(waiver.fingerprint());
                return true;
            }
            return false;
        });

        var unused = byFingerprint.keySet().stream().filter(fingerprint -> !matched.contains(fingerprint)).toList();
        int suppressed = result.findings().size() - filtered.findings().size();
        return new Application(filtered, suppressed, List.copyOf(expired), unused);
    }

    private static String fingerprint(Finding finding) {
        String value = finding.fingerprint();
        return value.startsWith("sha256:") ? value.substring("sha256:".length()) : value;
    }

    static Set<String> fingerprintsOf(List<Waiver> waivers) {
        var fingerprints = new LinkedHashSet<String>();
        waivers.forEach(waiver -> fingerprints.add(waiver.fingerprint()));
        return Set.copyOf(fingerprints);
    }

    static Map<String, String> reasonsOf(List<Waiver> waivers) {
        var reasons = new LinkedHashMap<String, String>();
        waivers.forEach(waiver -> reasons.put(waiver.fingerprint(), waiver.reason()));
        return Map.copyOf(reasons);
    }
}
