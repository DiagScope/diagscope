package example.kotlin.parity

import java.nio.file.Files
import java.nio.file.Paths

/**
 * Fixture: covers EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE, PROPAGATION_SUPPORTS_WRITE_RISK,
 * STREAM_IO_NOT_CLOSED, LOG_MESSAGE_STRING_CONCAT, CACHE_NAME_MISMATCH,
 * SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE.
 */

// ── EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE ─────────────────────────────────────

@RestController
open class KotlinWave7ExceptionService {

    /** EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE: no-arg exception constructor — getMessage() is null. */
    @GetMapping("/wave7/orders/{orderId}")
    open fun processOrder(orderId: String): String {
        if (orderId.isBlank()) throw RuntimeException() // → EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE
        return orderId
    }

    /** Safe: exception includes a descriptive message with domain context. */
    @GetMapping("/wave7/orders/safe/{orderId}")
    open fun processOrderSafe(orderId: String): String {
        if (orderId.isBlank()) throw IllegalArgumentException("orderId must not be blank")
        return orderId
    }
}

// ── PROPAGATION_SUPPORTS_WRITE_RISK ───────────────────────────────────────────

@Service
open class KotlinWave7AuditService(private val auditRepository: Any) {

    /** PROPAGATION_SUPPORTS_WRITE_RISK: SUPPORTS propagation with a write operation. */
    @Transactional(propagation = SUPPORTS)
    open fun recordAudit(entry: Any) {
        auditRepository.save(entry) // → PROPAGATION_SUPPORTS_WRITE_RISK
    }

    /** Safe: REQUIRED (default) guarantees a transaction exists before any write. */
    @Transactional
    open fun recordAuditSafe(entry: Any) {
        auditRepository.save(entry) // safe — REQUIRED ensures transactional context
    }
}

// ── STREAM_IO_NOT_CLOSED ──────────────────────────────────────────────────────

@RestController
open class KotlinWave7FileService {

    /** STREAM_IO_NOT_CLOSED: Files.walk() without try-with-resources (use {} in Kotlin). */
    @GetMapping("/wave7/files/count")
    open fun countFiles(dir: String): Long {
        val stream = Files.walk(Paths.get(dir)) // → STREAM_IO_NOT_CLOSED — not closed
        return stream.count()
    }

    /** Safe: use {} closes the stream after the lambda completes. */
    @GetMapping("/wave7/files/count/safe")
    open fun countFilesSafe(dir: String): Long {
        return Files.walk(Paths.get(dir)).use { it.count() }
    }
}

// ── LOG_MESSAGE_STRING_CONCAT ─────────────────────────────────────────────────

@RestController
open class KotlinWave7LoggingService {

    private val logger: Logger? = null

    /** LOG_MESSAGE_STRING_CONCAT: string concatenation in logger argument. */
    @PostMapping("/wave7/payments")
    open fun handlePayment(orderId: String, amount: Double) {
        logger!!.debug("Processing payment for order " + orderId + " amount=" + amount) // → LOG_MESSAGE_STRING_CONCAT
    }

    /** Safe: SLF4J parameterised substitution — message built only when level is enabled. */
    @PostMapping("/wave7/payments/safe")
    open fun handlePaymentSafe(orderId: String, amount: Double) {
        logger!!.debug("Processing payment for order {} amount={}", orderId, amount)
    }
}

// ── CACHE_NAME_MISMATCH ───────────────────────────────────────────────────────

@Service
open class KotlinWave7ProductService(private val repo: Any) {

    /** CACHE_NAME_MISMATCH target: @Cacheable stores under "products". */
    @Cacheable(value = "products")
    open fun findProduct(id: String): Any = repo.findById(id)

    /** CACHE_NAME_MISMATCH: @CacheEvict targets "product-cache" — mismatched name. */
    @CacheEvict(value = "product-cache")
    open fun updateProduct(product: Any) { repo.save(product) } // → CACHE_NAME_MISMATCH
}

/** Safe: @Cacheable and @CacheEvict use the same cache name. */
@Service
open class KotlinWave7CategoriesService(private val repo: Any) {

    @Cacheable(value = "categories")
    open fun findCategory(id: String): Any = repo.findById(id)

    @CacheEvict(value = "categories")
    open fun updateCategory(category: Any) { repo.save(category) } // safe — names match
}

// ── SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE ───────────────────────────────────────

@Service
open class KotlinWave7PollingService(private val statusRepo: Any) {

    /** SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE: fixedRate = 100ms with non-trivial body. */
    @Scheduled(fixedRate = 100)
    open fun pollForUpdates() {
        val pending = statusRepo.findPending()  // → SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE
        statusRepo.updateAll(pending)
    }

    /** Safe: fixedRate well above threshold — a 30-second interval is reasonable. */
    @Scheduled(fixedRate = 30000)
    open fun syncInventorySafe() {
        val items = statusRepo.findOutdated()
        statusRepo.refreshAll(items)
    }
}
