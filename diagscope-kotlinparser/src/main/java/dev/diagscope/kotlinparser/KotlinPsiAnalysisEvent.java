package dev.diagscope.kotlinparser;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** JFR measurement point used by the validation gate before changing Kotlin PSI concurrency. */
@Name("dev.diagscope.KotlinPsiAnalysis")
@Label("DiagScope Kotlin PSI analysis")
@Category({"DiagScope", "Kotlin"})
@Description("Kotlin PSI parsing and syntax-to-domain mapping in one shared compiler environment")
@StackTrace(false)
final class KotlinPsiAnalysisEvent extends Event {
    @Label("Source files")
    int sourceFiles;

    @Label("Explicit classpath entries")
    int explicitClasspathEntries;
}
