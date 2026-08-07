package example.observability;

import java.util.List;

public class ReportClient {
    public void refresh() {}
    public void refreshAll(List<String> ids) {}
    public String describe() { return "report"; }
    public String describeFailure(Throwable failure) { return "failed"; }
    public ReportCall remote() { return new ReportCall(); }
}
