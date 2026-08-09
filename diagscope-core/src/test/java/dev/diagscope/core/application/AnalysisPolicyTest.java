package dev.diagscope.core.application;

import dev.diagscope.core.domain.EntrypointType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisPolicyTest {

    @Test
    void portable_globs_match_nested_and_root_level_paths() {
        var policy = new AnalysisPolicy(Set.of("**/generated/**", "fixtures/*.java"),
                Set.of(), Map.of(), Set.of(), Set.of(), Map.of());

        assertThat(policy.ignores(Path.of("src/main/java/generated/Model.java"))).isTrue();
        assertThat(policy.ignores(Path.of("generated/Model.java"))).isTrue();
        assertThat(policy.ignores(Path.of("fixtures/Example.java"))).isTrue();
        assertThat(policy.ignores(Path.of("src/main/java/example/Model.java"))).isFalse();
    }

    @Test
    void policy_normalizes_custom_types_and_annotations() {
        var policy = new AnalysisPolicy(Set.of(), Set.of("example.AuditSink"),
                Map.of(EntrypointType.REST, Set.of("BusinessEndpoint")),
                Set.of("accountNumber"), Set.of(), Map.of());

        assertThat(policy.isCustomLogger("audit", "AuditSink")).isTrue();
        assertThat(policy.customEntrypointAnnotations(EntrypointType.REST))
                .containsExactly("BusinessEndpoint");
    }

    @Test
    void ignored_paths_cannot_escape_the_project() {
        assertThatThrownBy(() -> new AnalysisPolicy(Set.of("../outside/**"), Set.of(), Map.of(),
                Set.of(), Set.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project-relative");
    }
}
