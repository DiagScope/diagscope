package example.kotlin.parity

/**
 * Fixture: covers SCHEDULED_NON_VOID_RETURN, AOP_ADVICE_ON_PRIVATE_METHOD,
 * FEIGN_CLIENT_NO_FALLBACK, MULTIPLE_SCHEDULED_NO_THREAD_POOL,
 * OBJECT_MAPPER_CREATED_PER_REQUEST.
 */

// ── SCHEDULED_NON_VOID_RETURN ─────────────────────────────────────────────────

@Service
open class KotlinWave5ReportService {

    /** SCHEDULED_NON_VOID_RETURN: @Scheduled with non-void return — value is silently discarded. */
    @Scheduled(cron = "0 0 * * * *")
    open fun generateReport(): String = "report" // → SCHEDULED_NON_VOID_RETURN

    /** Safe: Unit (void) return type — Spring scheduler ignores nothing. */
    @Scheduled(cron = "0 30 * * * *")
    open fun cleanupTempFiles() {} // safe — Unit (void)
}

// ── AOP_ADVICE_ON_PRIVATE_METHOD ──────────────────────────────────────────────

@Service
open class KotlinWave5LoggingAspect {

    /** AOP_ADVICE_ON_PRIVATE_METHOD: @Around on private method — proxy cannot intercept it. */
    @Around
    private fun logExecutionTime(joinPoint: Any): Any? = null // → AOP_ADVICE_ON_PRIVATE_METHOD

    /** Safe: public (default) visibility — proxy can intercept and execute the advice. */
    @Around
    open fun logPublicMethod(joinPoint: Any): Any? = null // safe — public open
}

// ── FEIGN_CLIENT_NO_FALLBACK ──────────────────────────────────────────────────

/** FEIGN_CLIENT_NO_FALLBACK: @FeignClient without fallback or fallbackFactory. */
@FeignClient(name = "payment-service")
interface KotlinWave5PaymentClient {
    fun charge(orderId: String): String // → FEIGN_CLIENT_NO_FALLBACK (on the type)
}

/** Safe: fallback is explicitly declared (using KClass reference). */
@FeignClient(name = "inventory-service", fallback = KotlinWave5InventoryClientFallback::class)
interface KotlinWave5InventoryClient {
    fun getStock(productId: String): String // safe — fallback configured
}

/** Fallback implementation for KotlinWave5InventoryClient. */
class KotlinWave5InventoryClientFallback : KotlinWave5InventoryClient {
    override fun getStock(productId: String): String = "UNAVAILABLE"
}

// ── MULTIPLE_SCHEDULED_NO_THREAD_POOL ────────────────────────────────────────
// (The two @Scheduled methods below, combined with others in the project,
//  trigger MULTIPLE_SCHEDULED_NO_THREAD_POOL because no ThreadPoolTaskScheduler @Bean exists.)

@Service
open class KotlinWave5SchedulerService {

    /** Contributes to MULTIPLE_SCHEDULED_NO_THREAD_POOL count. */
    @Scheduled(fixedRate = 60000)
    open fun syncInventory() {} // counts toward scheduled total

    /** Contributes to MULTIPLE_SCHEDULED_NO_THREAD_POOL count. */
    @Scheduled(fixedRate = 120000)
    open fun sendDailyDigest() {} // counts toward scheduled total
}

// ── OBJECT_MAPPER_CREATED_PER_REQUEST ─────────────────────────────────────────

@Service
open class KotlinWave5JsonService {

    /** OBJECT_MAPPER_CREATED_PER_REQUEST: ObjectMapper() inside a regular method. */
    @Throws(Exception::class)
    open fun serialize(payload: Any): String =
        ObjectMapper().writeValueAsString(payload) // → OBJECT_MAPPER_CREATED_PER_REQUEST

    /** Safe: ObjectMapper injected via constructor in real code — reused on every call. */
    private val objectMapper: ObjectMapper? = null // injected in real code

    @Throws(Exception::class)
    open fun serializeSafe(payload: Any): String =
        objectMapper!!.writeValueAsString(payload) // safe — injected mapper reused
}

/** Safe: ObjectMapper constructed inside a @Bean factory method. */
@Configuration
open class KotlinWave5JacksonConfig {

    @Bean
    open fun objectMapper(): ObjectMapper = ObjectMapper() // safe — factory method
}
