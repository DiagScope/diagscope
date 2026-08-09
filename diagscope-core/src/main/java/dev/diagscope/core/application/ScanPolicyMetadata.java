package dev.diagscope.core.application;

/** Presentation metadata explaining which project and CI filters shaped the visible findings. */
public record ScanPolicyMetadata(
        String configurationFile,
        String baselineFile,
        int baselineSuppressedFindings,
        String changedSince,
        int changeScopeExcludedFindings
) {
    public ScanPolicyMetadata {
        configurationFile = configurationFile == null ? "" : configurationFile;
        baselineFile = baselineFile == null ? "" : baselineFile;
        changedSince = changedSince == null ? "" : changedSince;
        if (baselineSuppressedFindings < 0 || changeScopeExcludedFindings < 0) {
            throw new IllegalArgumentException("Policy finding counts must not be negative");
        }
    }

    public static ScanPolicyMetadata none() {
        return new ScanPolicyMetadata("", "", 0, "", 0);
    }
}
