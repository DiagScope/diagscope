package example.kotlin.parity

/**
 * Fixture: exercises EXCEPTION_SUPPRESSED_IN_FINALLY and MASS_ASSIGNMENT_RISK.
 */

// ── EXCEPTION_SUPPRESSED_IN_FINALLY ──────────────────────────────────────────

/**
 * Service demonstrating throw-in-finally pattern.
 */
@Service
class KotlinExceptionHandlingService(
    private val repository: KotlinOrderRepository,
    private val logger: Logger
) {

    /**
     * EXCEPTION_SUPPRESSED_IN_FINALLY: if repository.findAll() throws, the cleanup exception
     * from finally replaces it — root cause is silently discarded.
     */
    fun persistWithCleanup(id: String) {
        try {
            repository.findAll()           // may throw
        } finally {
            throw RuntimeException("cleanup failed") // → EXCEPTION_SUPPRESSED_IN_FINALLY
        }
    }

    /**
     * Safe: finally only logs — no throw statement.
     */
    fun persistWithSafeCleanup(id: String) {
        try {
            repository.findAll()
        } finally {
            logger.info("cleanup complete")  // no throw — safe
        }
    }
}

// ── MASS_ASSIGNMENT_RISK ──────────────────────────────────────────────────────

/**
 * REST controller that incorrectly accepts a JPA entity as a request body.
 */
@RestController
class KotlinOrderWriteController(
    private val repository: KotlinOrderRepository
) {

    /**
     * MASS_ASSIGNMENT_RISK: accepts KotlinOrderEntity directly — attacker can set 'id', 'status'.
     */
    @PostMapping("/kotlin/orders")
    fun createOrder(order: KotlinOrderEntity): String { // → MASS_ASSIGNMENT_RISK
        repository.findById(order.id.toString())
        return order.id.toString()
    }

    /**
     * Safe: accepts a dedicated DTO.
     */
    @PostMapping("/kotlin/orders/safe")
    fun createOrderSafe(request: CreateKotlinOrderRequest): String { // safe — DTO
        repository.findById(request.customerId)
        return request.customerId
    }

    /**
     * Safe: GET endpoint — read-only, mass assignment not applicable.
     */
    @GetMapping("/kotlin/orders/{id}")
    fun getOrder(id: String): KotlinOrderEntity = KotlinOrderEntity()
}

/**
 * Controller entrypoint connecting to both fixture services.
 */
@RestController
class KotlinSecurityController(
    private val exceptionService: KotlinExceptionHandlingService
) {
    @GetMapping("/kotlin-security-and-exception")
    fun trigger(id: String): String {
        exceptionService.persistWithCleanup(id) // → EXCEPTION_SUPPRESSED_IN_FINALLY
        return id
    }
}
