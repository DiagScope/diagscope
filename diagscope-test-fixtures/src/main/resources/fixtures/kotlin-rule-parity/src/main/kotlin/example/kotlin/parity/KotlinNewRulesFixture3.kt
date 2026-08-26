package example.kotlin.parity

/**
 * Fixture: covers SCHEDULED_NO_INITIAL_DELAY, SYNCHRONIZED_ON_SPRING_BEAN, FIELD_INJECTION_USED.
 */

// ── SCHEDULED_NO_INITIAL_DELAY ─────────────────────────────────────────────────

/**
 * Scheduler demonstrating fixedRate/fixedDelay with and without initialDelay.
 */
@Service
class KotlinReportScheduler {

    /**
     * SCHEDULED_NO_INITIAL_DELAY: fixedRate with no initialDelay fires immediately at startup.
     */
    @Scheduled(fixedRate = 60_000)
    fun generateDailyReport() {} // → SCHEDULED_NO_INITIAL_DELAY

    /**
     * Safe: initialDelay gives the application time to fully start before first execution.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    fun generateDailyReportSafe() {} // safe — has initialDelay

    /**
     * Safe: cron expressions fire at the next scheduled time, not at startup.
     */
    @Scheduled(cron = "0 0 2 * * *")
    fun generateNightlyReport() {} // safe — cron, not rate/delay based
}

// ── SYNCHRONIZED_ON_SPRING_BEAN ────────────────────────────────────────────────

/**
 * Service demonstrating @Synchronized on a Spring bean.
 */
@Service
class KotlinCounterService {

    private var counter = 0

    /**
     * SYNCHRONIZED_ON_SPRING_BEAN: @Synchronized on a Spring bean acquires the lock on the
     * CGLIB proxy, not the bean — two threads can run this simultaneously.
     */
    @Synchronized
    fun increment() { // → SYNCHRONIZED_ON_SPRING_BEAN
        counter++
    }

    /**
     * Safe: regular function without synchronization concern.
     */
    fun incrementUnsafe() {
        counter++
    }
}

/**
 * A plain Kotlin class — not a Spring bean — where @Synchronized is valid.
 */
class KotlinPlainCounter {

    private var counter = 0

    /**
     * Safe: not a Spring bean, so @Synchronized works as expected.
     */
    @Synchronized
    fun increment() { // safe — not a Spring bean
        counter++
    }
}

// ── FIELD_INJECTION_USED ──────────────────────────────────────────────────────

/**
 * Service demonstrating field injection via @Autowired on a property.
 */
@Service
class KotlinOrderServiceWithFieldInjection {

    @Autowired  // → FIELD_INJECTION_USED (field injection)
    lateinit var repository: KotlinOrderRepository

    fun process() {
        repository.findAll()
    }
}

/**
 * Safe: constructor injection using Kotlin primary constructor.
 */
@Service
class KotlinOrderServiceWithConstructorInjection(
    private val repository: KotlinOrderRepository
) {
    fun process() {
        repository.findAll()
    }
}
