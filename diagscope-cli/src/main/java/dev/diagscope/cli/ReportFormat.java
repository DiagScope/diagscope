package dev.diagscope.cli;

public enum ReportFormat {
    MARKDOWN("report.md"),
    JSON("result.json"),
    HTML("report.html"),
    SARIF("result.sarif");

    private final String fileName;

    ReportFormat(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}
