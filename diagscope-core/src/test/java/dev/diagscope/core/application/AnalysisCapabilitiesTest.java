package dev.diagscope.core.application;

import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.ProjectLayout;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisCapabilitiesTest {

    // ── Java-only project, no classpath ───────────────────────────────────────

    @Test
    void java_source_root_produces_supported_java_and_not_applicable_kotlin() {
        var result = minimalResult(
                List.of(javaRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        var caps = AnalysisCapabilities.fromResult(result, false);

        assertThat(caps.languages().get("java")).isEqualTo(CapabilityLevel.SUPPORTED);
        assertThat(caps.languages().get("kotlin")).isEqualTo(CapabilityLevel.NOT_APPLICABLE);
    }

    @Test
    void kotlin_source_root_produces_source_first_kotlin() {
        var result = minimalResult(
                List.of(kotlinRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        var caps = AnalysisCapabilities.fromResult(result, true);

        assertThat(caps.languages().get("kotlin")).isEqualTo(CapabilityLevel.SOURCE_FIRST);
    }

    @Test
    void mixed_project_supports_java_and_is_source_first_for_kotlin() {
        var result = minimalResult(
                List.of(javaRoot(), kotlinRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        var caps = AnalysisCapabilities.fromResult(result, true);

        assertThat(caps.languages().get("java")).isEqualTo(CapabilityLevel.SUPPORTED);
        assertThat(caps.languages().get("kotlin")).isEqualTo(CapabilityLevel.SOURCE_FIRST);
    }

    // ── Spring framework ───────────────────────────────────────────────────────

    @Test
    void spring_framework_is_always_supported_in_1_0() {
        var result = minimalResult(List.of(javaRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        var caps = AnalysisCapabilities.fromResult(result, false);

        assertThat(caps.frameworks().get("spring")).isEqualTo(CapabilityLevel.SUPPORTED);
    }

    // ── Entrypoint features ────────────────────────────────────────────────────

    @Test
    void all_entrypoint_features_are_supported_when_all_types_enabled() {
        var result = minimalResult(List.of(javaRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        var caps = AnalysisCapabilities.fromResult(result, false);

        assertThat(caps.features().get("restEntrypoints")).isEqualTo(CapabilityLevel.SUPPORTED);
        assertThat(caps.features().get("kafkaListeners")).isEqualTo(CapabilityLevel.SUPPORTED);
        assertThat(caps.features().get("reactiveMessages")).isEqualTo(CapabilityLevel.SUPPORTED);
        assertThat(caps.features().get("scheduledJobs")).isEqualTo(CapabilityLevel.SUPPORTED);
    }

    @Test
    void disabled_entrypoint_type_appears_as_not_applicable() {
        var result = minimalResult(List.of(javaRoot()),
                EnumSet.of(EntrypointType.REST), // only REST
                List.of());

        var caps = AnalysisCapabilities.fromResult(result, false);

        assertThat(caps.features().get("restEntrypoints")).isEqualTo(CapabilityLevel.SUPPORTED);
        assertThat(caps.features().get("kafkaListeners")).isEqualTo(CapabilityLevel.NOT_APPLICABLE);
        assertThat(caps.features().get("scheduledJobs")).isEqualTo(CapabilityLevel.NOT_APPLICABLE);
    }

    // ── Static feature levels ──────────────────────────────────────────────────

    @Test
    void spring_transactions_are_always_supported() {
        var result = minimalResult(List.of(javaRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        assertThat(AnalysisCapabilities.fromResult(result, false)
                .features().get("springTransactions")).isEqualTo(CapabilityLevel.SUPPORTED);
    }

    @Test
    void spring_aop_is_always_partial() {
        var result = minimalResult(List.of(javaRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        assertThat(AnalysisCapabilities.fromResult(result, false)
                .features().get("springAop")).isEqualTo(CapabilityLevel.PARTIAL);
    }

    // ── Dependency resolution ──────────────────────────────────────────────────

    @Test
    void java_dependency_resolution_is_not_configured_without_explicit_classpath() {
        var result = minimalResult(List.of(javaRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        assertThat(AnalysisCapabilities.fromResult(result, false)
                .features().get("javaDependencyResolution")).isEqualTo(CapabilityLevel.NOT_CONFIGURED);
    }

    @Test
    void java_dependency_resolution_is_supported_with_explicit_classpath() {
        var result = minimalResult(List.of(javaRoot()), EnumSet.allOf(EntrypointType.class),
                List.of(Path.of("/deps/lib.jar")));

        assertThat(AnalysisCapabilities.fromResult(result, false)
                .features().get("javaDependencyResolution")).isEqualTo(CapabilityLevel.SUPPORTED);
    }

    @Test
    void kotlin_dependency_resolution_is_source_first_when_kotlin_present() {
        var result = minimalResult(List.of(kotlinRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        assertThat(AnalysisCapabilities.fromResult(result, true)
                .features().get("kotlinDependencyResolution")).isEqualTo(CapabilityLevel.SOURCE_FIRST);
    }

    @Test
    void kotlin_dependency_resolution_is_not_applicable_without_kotlin() {
        var result = minimalResult(List.of(javaRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        assertThat(AnalysisCapabilities.fromResult(result, false)
                .features().get("kotlinDependencyResolution")).isEqualTo(CapabilityLevel.NOT_APPLICABLE);
    }

    // ── Structural guarantees ─────────────────────────────────────────────────

    @Test
    void capabilities_contain_all_required_keys() {
        var result = minimalResult(List.of(javaRoot()), EnumSet.allOf(EntrypointType.class), List.of());
        var caps = AnalysisCapabilities.fromResult(result, false);

        assertThat(caps.languages()).containsKeys("java", "kotlin");
        assertThat(caps.frameworks()).containsKey("spring");
        assertThat(caps.features()).containsKeys(
                "restEntrypoints", "kafkaListeners", "reactiveMessages", "scheduledJobs",
                "springTransactions", "springAop", "javaDependencyResolution", "kotlinDependencyResolution");
    }

    @Test
    void feature_order_is_deterministic_across_calls() {
        var result = minimalResult(List.of(javaRoot()), EnumSet.allOf(EntrypointType.class), List.of());

        var first  = AnalysisCapabilities.fromResult(result, false).features().keySet().stream().toList();
        var second = AnalysisCapabilities.fromResult(result, false).features().keySet().stream().toList();

        assertThat(first).isEqualTo(second);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Path javaRoot() {
        return Path.of("/project/src/main/java").toAbsolutePath();
    }

    private static Path kotlinRoot() {
        return Path.of("/project/src/main/kotlin").toAbsolutePath();
    }

    private static AnalysisResult minimalResult(
            List<Path> sourceRoots,
            java.util.Set<EntrypointType> entrypointTypes,
            List<Path> classpath
    ) {
        var layout = new ProjectLayout(BuildSystem.MAVEN, Path.of("/project").toAbsolutePath(),
                List.of(Path.of(".")), sourceRoots);
        var options = new AnalysisOptions(3, 1, entrypointTypes, AnalysisPolicy.defaults(),
                classpath, List.of());
        return new AnalysisResult("test", Path.of("/project").toAbsolutePath(), layout, options,
                List.of(), List.of(), List.of(), new AnalysisStatistics(0, 0, 0, 0, 0, 0,
                new PhaseMetrics(0, 0, 0, 0)));
    }
}
