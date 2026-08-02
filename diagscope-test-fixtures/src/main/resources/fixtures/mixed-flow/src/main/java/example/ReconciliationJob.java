package example;

public class ReconciliationJob {
    @Scheduled(cron = "0 */5 * * * *")
    public void execute() { System.out.println("running"); }
}
