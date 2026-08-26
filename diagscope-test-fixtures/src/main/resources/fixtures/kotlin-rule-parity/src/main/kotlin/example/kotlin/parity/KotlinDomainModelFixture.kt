package example.kotlin.parity

/**
 * Fixture: exercises TRANSACTIONAL_ON_INTERFACE, MISSING_PAGINATION,
 * and KAFKA_RETRY_WITHOUT_BACKOFF.
 */

// ── TRANSACTIONAL_ON_INTERFACE ────────────────────────────────────────────────

/**
 * @Transactional on an interface method — CGLIB will ignore this annotation.
 * triggers TRANSACTIONAL_ON_INTERFACE.
 */
interface KotlinTxOrderService {
    @Transactional
    fun processOrder(orderId: String) // → TRANSACTIONAL_ON_INTERFACE

    fun readOrder(orderId: String)    // no annotation — safe
}

/**
 * Implementation — @Transactional here is correct (CGLIB reads the class, not the interface).
 */
@Service
class KotlinTxOrderServiceImpl(private val repository: KotlinOrderRepository) : KotlinTxOrderService {

    @Transactional
    override fun processOrder(orderId: String) {
        repository.findById(orderId)
    }

    override fun readOrder(orderId: String) {
        repository.findById(orderId)
    }
}

// ── MISSING_PAGINATION ────────────────────────────────────────────────────────

/**
 * Service that calls unbounded repository methods.
 * triggers MISSING_PAGINATION on findAll() and findByStatus().
 */
@Service
class KotlinOrderQueryService(private val repository: KotlinOrderRepository) {

    fun getAllOrders(): List<String> =
        repository.findAll() // → the repository method triggers MISSING_PAGINATION

    fun getOrdersByStatus(status: String): List<String> =
        repository.findByStatus(status) // → triggers MISSING_PAGINATION

    fun getPagedOrders(pageable: Pageable): List<String> =
        repository.findAll(pageable) // safe — Pageable present
}

// ── KAFKA_RETRY_WITHOUT_BACKOFF ───────────────────────────────────────────────

/**
 * Kafka error handler configuration with zero-delay fixed backoff.
 * triggers KAFKA_RETRY_WITHOUT_BACKOFF.
 */
@Service
class KotlinKafkaConfigService {

    /** triggers KAFKA_RETRY_WITHOUT_BACKOFF: interval=0ms, maxAttempts=3. */
    fun createZeroBackOff(): FixedBackOff =
        FixedBackOff(0, 3) // interval=0ms — retry storm risk

    /** Safe: interval of 1000ms (1 second). */
    fun createSafeBackOff(): FixedBackOff =
        FixedBackOff(1000, 3) // interval=1s — safe

    /** Safe: use exponential backoff. */
    fun createExponentialBackOff(): ExponentialBackOff {
        val backOff = ExponentialBackOff(1000, 2.0)
        backOff.maxInterval = 30_000L
        return backOff
    }
}

/**
 * Controller that triggers every fixture method from a flow entrypoint.
 */
@RestController
class KotlinDomainModelController(
    private val service: KotlinTxOrderService,
    private val queryService: KotlinOrderQueryService,
    private val kafkaConfigService: KotlinKafkaConfigService
) {
    @GetMapping("/kotlin-domain-model")
    fun trigger(id: String, status: String): String {
        service.processOrder(id)              // triggers TRANSACTIONAL_ON_INTERFACE
        queryService.getAllOrders()            // triggers MISSING_PAGINATION
        queryService.getOrdersByStatus(status)
        kafkaConfigService.createZeroBackOff() // triggers KAFKA_RETRY_WITHOUT_BACKOFF
        return id
    }
}
