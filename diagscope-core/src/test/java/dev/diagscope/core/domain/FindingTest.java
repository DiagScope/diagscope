package dev.diagscope.core.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FindingTest {
    @Test
    void fingerprint_is_sha256_of_normalized_location_and_sorted_evidence() {
        var evidenceInReverseOrder = new LinkedHashMap<String, String>();
        evidenceInReverseOrder.put("z", "last");
        evidenceInReverseOrder.put("a", "first");
        var relatedFlows = new ArrayList<>(List.of(
                new RelatedFlow("REST:z", "Z", Confidence.HIGH)));

        var first = finding(
                new SourceLocation(Path.of("src/main/../main/Example.java"), 10, 12),
                relatedFlows,
                evidenceInReverseOrder);
        var second = new Finding(
                "TEST_RULE",
                Severity.ERROR,
                Confidence.LOW,
                new SourceLocation(Path.of("src/main/Example.java"), 10, 12),
                "Different presentation text",
                "Different recommendation",
                List.of(new RelatedFlow("SCHEDULED:a", "A", Confidence.LOW)),
                java.util.Map.of("a", "first", "z", "last"));

        evidenceInReverseOrder.put("later", "mutation");
        relatedFlows.clear();

        assertThat(first.evidence()).containsExactly(
                java.util.Map.entry("a", "first"),
                java.util.Map.entry("z", "last"));
        assertThat(first.relatedFlows()).hasSize(1);
        assertThat(first.fingerprint())
                .matches("[0-9a-f]{64}")
                .isEqualTo(second.fingerprint());
        assertThat(finding(
                new SourceLocation(Path.of("src/main/Example.java"), 10, 13),
                List.of(),
                java.util.Map.of("a", "first", "z", "last")).fingerprint())
                .isNotEqualTo(first.fingerprint());
    }

    private static Finding finding(
            SourceLocation location,
            List<RelatedFlow> relatedFlows,
            java.util.Map<String, String> evidence
    ) {
        return new Finding(
                "TEST_RULE",
                Severity.WARNING,
                Confidence.HIGH,
                location,
                "Test finding",
                "Test recommendation",
                relatedFlows,
                evidence);
    }
}
