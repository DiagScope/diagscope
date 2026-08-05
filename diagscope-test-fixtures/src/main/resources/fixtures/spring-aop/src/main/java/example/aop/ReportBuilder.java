package example.aop;

public class ReportBuilder {

    @Timed
    public String build(String id) {
        return id;
    }
}
