package example.edge;

public class NestedJob {
    private final Inner inner = new Inner();

    @Scheduled(cron = "0 0 * * * *")
    public void run() {
        inner.reconcile();
    }

    public static class Inner {
        public void reconcile() {
            try {
                perform();
            } catch (RuntimeException exception) {
                System.err.println("reconciliation failed");
            }
        }

        private void perform() {
            throw new IllegalStateException("boom");
        }
    }

    public record Reference(String id) {
        public String describe() {
            return "reference " + id;
        }
    }

    public enum Mode {
        FAST,
        SAFE;

        public boolean fast() {
            return this == FAST;
        }
    }
}
