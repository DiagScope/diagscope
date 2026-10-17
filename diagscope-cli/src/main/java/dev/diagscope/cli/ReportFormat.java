package dev.diagscope.cli;

public enum ReportFormat {
    MARKDOWN("report.md"),
    JSON("result.json"),
    HTML("report.html"),
    SARIF("result.sarif"),
    /** Compact plain text for coding assistants; printed to standard output when it is the only format. */
    AGENT("result.agent.txt");

    private final String fileName;

    ReportFormat(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}
