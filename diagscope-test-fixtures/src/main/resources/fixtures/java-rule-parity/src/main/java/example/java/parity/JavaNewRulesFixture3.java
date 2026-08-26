package example.java.parity;

/**
 * Fixture: covers SCHEDULED_NO_INITIAL_DELAY, SYNCHRONIZED_ON_SPRING_BEAN, FIELD_INJECTION_USED.
 */

// ── SCHEDULED_NO_INITIAL_DELAY ─────────────────────────────────────────────────

/**
 * Scheduler demonstrating fixedRate/fixedDelay with and without initialDelay.
 */
@Service
class JavaReportScheduler {

    /**
     * SCHEDULED_NO_INITIAL_DELAY: fixedRate with no initialDelay fires immediately at startup.
     */
    @Scheduled(fixedRate = 60_000)
    void generateDailyReport() {} // → SCHEDULED_NO_INITIAL_DELAY

    /**
     * Safe: initialDelay gives the application time to fully start before first execution.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    void generateDailyReportSafe() {} // safe — has initialDelay

    /**
     * Safe: cron expressions fire at the next scheduled time, not at startup.
     */
    @Scheduled(cron = "0 0 2 * * *")
    void generateNightlyReport() {} // safe — cron, not rate/delay based
}

// ── SYNCHRONIZED_ON_SPRING_BEAN ────────────────────────────────────────────────

/**
 * Service demonstrating synchronized methods on a Spring bean.
 */
@Service
class JavaCounterService {

    private int counter = 0;

    /**
     * SYNCHRONIZED_ON_SPRING_BEAN: synchronized on a Spring bean acquires the lock on the
     * CGLIB proxy, not the bean — two threads can run this simultaneously.
     */
    synchronized void increment() { // → SYNCHRONIZED_ON_SPRING_BEAN
        counter++;
    }

    /**
     * Safe: not annotated with a Spring stereotype, so the class is not proxy-managed.
     */
    void incrementUnsafe() {
        counter++;
    }
}

/**
 * A plain Java class — not a Spring bean — where synchronized is valid.
 */
class JavaPlainCounter {

    private int counter = 0;

    /**
     * Safe: not a Spring bean, so synchronized keyword works as expected.
     */
    synchronized void increment() { // safe — not a Spring bean
        counter++;
    }
}

// ── FIELD_INJECTION_USED ──────────────────────────────────────────────────────

/**
 * Service demonstrating field injection vs constructor injection.
 */
@Service
class JavaOrderServiceWithFieldInjection {

    @Autowired  // → FIELD_INJECTION_USED (field injection)
    private JavaOrderRepository repository;

    void process() {
        repository.findAll();
    }
}

/**
 * Safe: constructor injection is the Spring-recommended approach.
 */
@Service
class JavaOrderServiceWithConstructorInjection {

    private final JavaOrderRepository repository;

    JavaOrderServiceWithConstructorInjection(JavaOrderRepository repository) {
        this.repository = repository;
    }

    void process() {
        repository.findAll();
    }
}

/**
 * Supporting repository stub for the fixture classes above.
 */
interface JavaOrderRepository {
    java.util.List<String> findAll();
}
