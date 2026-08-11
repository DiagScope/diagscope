package dev.diagscope.core.application;

/** Presentation metadata explaining which project and CI filters shaped the visible findings. */
public record ScanPolicyMetadata(
        String configurationFile,
        String baselineFile,
        int baselineSuppressedFindings,
        int baselineRemovedFindings,
        int baselineFingerprintMigrations,
        String changedSince,
        int changeScopeExcludedFindings,
        int waivedFindings,
        int expiredWaivers,
        int unusedWaivers
) {
    public ScanPolicyMetadata {
        configurationFile = configurationFile == null ? "" : configurationFile;
        baselineFile = baselineFile == null ? "" : baselineFile;
        changedSince = changedSince == null ? "" : changedSince;
        if (baselineSuppressedFindings < 0 || baselineRemovedFindings < 0
                || baselineFingerprintMigrations < 0 || changeScopeExcludedFindings < 0
                || waivedFindings < 0 || expiredWaivers < 0 || unusedWaivers < 0) {
            throw new IllegalArgumentException("Policy finding counts must not be negative");
        }
    }

    public static ScanPolicyMetadata none() {
        return new ScanPolicyMetadata("", "", 0, 0, 0, "", 0, 0, 0, 0);
    }
}
