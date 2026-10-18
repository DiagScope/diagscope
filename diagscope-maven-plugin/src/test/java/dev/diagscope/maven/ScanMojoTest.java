package dev.diagscope.maven;

import dev.diagscope.testfixtures.FixtureCatalog;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanMojoTest {
    @TempDir
    Path temp;

    @Test
    void without_a_gate_the_scan_only_reports() throws Exception {
        var mojo = mojoFor(FixtureCatalog.copyTo(temp, "mixed-flow"));

        mojo.execute();

        assertThat(temp.resolve("reports/result.json")).isRegularFile();
        assertThat(temp.resolve("reports/report.html")).isRegularFile();
    }

    @Test
    void failOn_breaks_the_build_and_still_writes_the_reports() throws Exception {
        var mojo = mojoFor(FixtureCatalog.copyTo(temp, "mixed-flow"));
        mojo.failOn = "ERROR";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class).hasMessageContaining("gate failed");
        assertThat(temp.resolve("reports/result.json")).isRegularFile();
    }

    @Test
    void failOnConfidence_is_applied() throws Exception {
        var mojo = mojoFor(FixtureCatalog.copyTo(temp, "mixed-flow"));
        mojo.failOn = "ERROR";
        mojo.failOnConfidence = "HIGH";

        // mixed-flow has an ERROR/HIGH SILENT_CATCH, so a HIGH gate still fails; a missing failOn is a usage error.
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
        mojo.failOn = null;
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("--fail-on-confidence requires --fail-on");
    }

    @Test
    void the_gate_of_the_project_policy_applies_and_a_profile_replaces_it() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Files.writeString(project.resolve("diagscope.yml"), """
                schemaVersion: "1.0"
                gate:
                  failOn:
                    - severity: INFO
                  profiles:
                    lenient:
                      maxFindings:
                        ERROR: 1000
                """);
        var mojo = mojoFor(project);

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
        mojo.gateProfile = "lenient";
        mojo.execute();
        mojo.failOn = "NONE";
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void a_module_uses_the_policy_of_the_multi_module_root() throws Exception {
        Path root = Files.createDirectories(temp.resolve("root"));
        Files.writeString(root.resolve("diagscope.yml"), "schemaVersion: \"1.0\"\ngate:\n  failOn:\n    - severity: INFO\n");
        Path module = FixtureCatalog.copyTo(root, "mixed-flow");
        var mojo = mojoFor(module);
        var parent = new MavenProject();
        parent.setFile(root.resolve("pom.xml").toFile());
        mojo.project.setParent(parent);

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    }

    @Test
    void skip_and_pom_packaging_do_nothing() throws Exception {
        var mojo = mojoFor(FixtureCatalog.copyTo(temp, "mixed-flow"));
        mojo.failOn = "INFO";
        mojo.skip = true;
        mojo.execute();

        mojo.skip = false;
        mojo.project.setPackaging("pom");
        mojo.execute();

        assertThat(temp.resolve("reports")).doesNotExist();
    }

    @Test
    void a_bad_enum_value_names_the_options() throws Exception {
        var mojo = mojoFor(FixtureCatalog.copyTo(temp, "mixed-flow"));
        mojo.formats = List.of("PDF");

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Unknown format 'PDF'").hasMessageContaining("MARKDOWN");
    }

    private ScanMojo mojoFor(Path basedir) throws IOException {
        var project = new MavenProject();
        project.setFile(new File(basedir.toFile(), "pom.xml"));
        var mojo = new ScanMojo();
        mojo.project = project;
        mojo.outputDirectory = temp.resolve("reports").toFile();
        mojo.formats = List.of("JSON", "HTML");
        mojo.maxDepth = 3;
        return mojo;
    }
}
