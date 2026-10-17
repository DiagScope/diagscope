package dev.diagscope.core.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Call resolution inside a module when a class name is declared in several (ADR-0006). */
public final class ModuleScope {
    private ModuleScope() {
    }

    /**
     * The candidates a caller in {@code module} should consider. Where a key has methods from several modules and one
     * of them is the caller's own, only the caller's module is kept; otherwise the candidates stay as they are, so a
     * call from a third module remains ambiguous instead of resolving to whichever file came first.
     */
    public static <M> Map<String, List<M>> restrictedTo(
            Map<String, List<M>> candidatesByKey, String module, Function<M, String> moduleOf) {
        var restricted = new LinkedHashMap<String, List<M>>(candidatesByKey.size());
        candidatesByKey.forEach((key, candidates) -> restricted.put(key, restrictedTo(candidates, module, moduleOf)));
        return restricted;
    }

    private static <M> List<M> restrictedTo(List<M> candidates, String module, Function<M, String> moduleOf) {
        if (candidates.size() < 2) {
            return candidates;
        }
        var modules = new LinkedHashSet<String>();
        candidates.forEach(candidate -> modules.add(moduleOf.apply(candidate)));
        if (modules.size() < 2 || !modules.contains(module)) {
            return candidates;
        }
        var local = new ArrayList<M>();
        for (M candidate : candidates) {
            if (module.equals(moduleOf.apply(candidate))) {
                local.add(candidate);
            }
        }
        return List.copyOf(local);
    }
}
