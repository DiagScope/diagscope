package example.kotlin.parity

import java.util.concurrent.CompletableFuture

/**
 * Fixture: covers MISSING_REQUEST_BODY_VALIDATION, TRANSACTIONAL_ON_REST_CONTROLLER,
 * APPLICATION_CONTEXT_LOCATOR, ASYNC_VOID_RETURN, CONFIGURATION_PROPERTIES_NOT_VALIDATED,
 * CIRCUIT_BREAKER_WITHOUT_FALLBACK, BULKHEAD_WITHOUT_FALLBACK, RATE_LIMITER_WITHOUT_FALLBACK,
 * THREAD_LOCAL_NOT_REMOVED, RUNBLOCKING_IN_SUSPEND, ENTITY_MANAGER_CLEAR_IN_TRANSACTION,
 * CACHEABLE_ON_PRIVATE_METHOD, EVENT_LISTENER_ON_PRIVATE_METHOD,
 * TIMED_ON_PRIVATE_METHOD, NATIVE_QUERY_STRING_CONCAT.
 */

// ── MISSING_REQUEST_BODY_VALIDATION ──────────────────────────────────────────

@RestController
open class KotlinWave10OrderController {

    /** MISSING_REQUEST_BODY_VALIDATION: the @RequestBody payload is not annotated with @Valid. */
    @PostMapping("/wave10/orders")
    open fun createOrder(@RequestBody order: OrderPayload): Any? = null // → MISSING_REQUEST_BODY_VALIDATION
}

open class OrderPayload

@RestController
open class KotlinWave10OrderControllerSafe {

    /** Safe: @Valid on the @RequestBody parameter activates constraint validation. */
    @PostMapping("/wave10/orders/safe")
    open fun createOrderSafe(@Valid @RequestBody order: OrderPayload): Any? = null
}

// ── TRANSACTIONAL_ON_REST_CONTROLLER ─────────────────────────────────────────

@RestController
open class KotlinWave10PaymentController {

    /** TRANSACTIONAL_ON_REST_CONTROLLER: @Transactional on a REST controller method. */
    @PostMapping("/wave10/payments")
    @Transactional
    open fun processPayment(requestBody: Any): Any? = null // → TRANSACTIONAL_ON_REST_CONTROLLER

    /** Safe: no transaction boundary on the controller — delegated to the service layer. */
    @GetMapping("/wave10/payments/{id}")
    open fun getPayment(id: String): Any? = null
}

// ── APPLICATION_CONTEXT_LOCATOR ───────────────────────────────────────────────

@Service
open class KotlinWave10LegacyOrderService {

    /** APPLICATION_CONTEXT_LOCATOR: getBean() inside a service method — hidden dependency. */
    open fun processLegacyOrder(orderId: String): Any? { // → APPLICATION_CONTEXT_LOCATOR
        return applicationContext.getBean("pricingService")
    }

    /** Safe: dependency declared as constructor parameter — Spring injects it. */
    open fun processModernOrder(orderId: String, pricingService: Any): Any? = pricingService
}

// ── ASYNC_VOID_RETURN ─────────────────────────────────────────────────────────

@Service
open class KotlinWave10NotificationService {

    /** ASYNC_VOID_RETURN: @Async with Unit return — exceptions silently discarded. */
    @Async
    open fun sendEmail(recipient: String, body: String) { // → ASYNC_VOID_RETURN
        // if this throws, the exception is swallowed by SimpleAsyncUncaughtExceptionHandler
    }

    /** Safe: returns CompletableFuture<Void> — caller can observe exceptions. */
    @Async
    open fun sendEmailSafe(recipient: String, body: String): CompletableFuture<Void> {
        return CompletableFuture.completedFuture(null)
    }
}

// ── CONFIGURATION_PROPERTIES_NOT_VALIDATED ────────────────────────────────────

@ConfigurationProperties(prefix = "app.payment")
open class KotlinWave10PaymentProperties {

    /** CONFIGURATION_PROPERTIES_NOT_VALIDATED: @ConfigurationProperties without @Validated. */
    open fun getGatewayUrl(): String? = gatewayUrl // → CONFIGURATION_PROPERTIES_NOT_VALIDATED (type-level)

    var gatewayUrl: String? = null
}

@ConfigurationProperties(prefix = "app.notification")
@Validated
open class KotlinWave10NotificationPropertiesSafe {

    /** Safe: @Validated present — constraint annotations on fields are enforced at startup. */
    var emailHost: String? = null
}

// ── CIRCUIT_BREAKER_WITHOUT_FALLBACK ─────────────────────────────────────────

@Service
open class KotlinWave10InventoryClient {

    /** CIRCUIT_BREAKER_WITHOUT_FALLBACK: no fallbackMethod declared. */
    @CircuitBreaker(name = "inventory")
    open fun getInventory(productId: String): Any? = null // → CIRCUIT_BREAKER_WITHOUT_FALLBACK

    /** Safe: fallbackMethod provides graceful degradation when circuit opens. */
    @CircuitBreaker(name = "inventory", fallbackMethod = "getInventoryFallback")
    open fun getInventorySafe(productId: String): Any? = null

    open fun getInventoryFallback(productId: String, t: Throwable): Any =
        mapOf("available" to false)
}

// ── BULKHEAD_WITHOUT_FALLBACK ─────────────────────────────────────────────────

@Service
open class KotlinWave10SearchService {

    /** BULKHEAD_WITHOUT_FALLBACK: no fallbackMethod declared. */
    @Bulkhead(name = "search")
    open fun search(query: String): Any? = null // → BULKHEAD_WITHOUT_FALLBACK

    /** Safe: fallbackMethod returns degraded result when bulkhead is full. */
    @Bulkhead(name = "search", fallbackMethod = "searchFallback")
    open fun searchSafe(query: String): Any? = null

    open fun searchFallback(query: String, t: Throwable): Any = emptyList<Any>()
}

// ── RATE_LIMITER_WITHOUT_FALLBACK ─────────────────────────────────────────────

@Service
open class KotlinWave10PricingClient {

    /** RATE_LIMITER_WITHOUT_FALLBACK: no fallbackMethod declared. */
    @RateLimiter(name = "pricing")
    open fun getPrice(productId: String): Any? = null // → RATE_LIMITER_WITHOUT_FALLBACK

    /** Safe: fallbackMethod returns cached price when rate limit is exceeded. */
    @RateLimiter(name = "pricing", fallbackMethod = "getPriceFallback")
    open fun getPriceSafe(productId: String): Any? = null

    open fun getPriceFallback(productId: String, t: Throwable): Any =
        mapOf("price" to 0.0, "cached" to true)
}

// ── THREAD_LOCAL_NOT_REMOVED ──────────────────────────────────────────────────

@Service
open class KotlinWave10RequestContextService {

    /** THREAD_LOCAL_NOT_REMOVED: set() without remove() — value leaks to next request on same thread. */
    open fun setUser(userId: String) {
        threadLocalUserId.set(userId) // → THREAD_LOCAL_NOT_REMOVED
        doWork()
    }

    /** Safe: remove() in finally ensures no value leaks across requests. */
    open fun setUserSafe(userId: String) {
        try {
            threadLocalUserId.set(userId)
            doWork()
        } finally {
            threadLocalUserId.remove()
        }
    }

    private fun doWork() {}

    companion object {
        private val threadLocalUserId = ThreadLocal<String>()
    }
}

// ── RUNBLOCKING_IN_SUSPEND ────────────────────────────────────────────────────

@Service
open class KotlinWave10OrderFetchService {

    /** RUNBLOCKING_IN_SUSPEND: runBlocking inside a suspend function — blocks dispatcher thread. */
    open suspend fun fetchOrder(orderId: String): Any? { // → RUNBLOCKING_IN_SUSPEND
        return runBlocking { // blocks the coroutine dispatcher thread — potential deadlock
            legacyFetch(orderId)
        }
    }

    /** Safe: withContext(Dispatchers.IO) suspends the coroutine without blocking the thread. */
    open suspend fun fetchOrderSafe(orderId: String): Any? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            legacyFetch(orderId)
        }

    private fun legacyFetch(orderId: String): Any? = null
}

// ── ENTITY_MANAGER_CLEAR_IN_TRANSACTION ──────────────────────────────────────

@Service
open class KotlinWave10OrderBatchService {

    /** ENTITY_MANAGER_CLEAR_IN_TRANSACTION: clear() outside a loop detaches managed entities. */
    @Transactional
    open fun reprocessOrder(em: Any, order: Any) {
        @Suppress("UNCHECKED_CAST")
        (em as javax.persistence.EntityManager).clear() // → ENTITY_MANAGER_CLEAR_IN_TRANSACTION
    }

    /** Safe: clear() inside a batch loop with flush() — intentional batch pattern. */
    @Transactional
    open fun processBatch(em: javax.persistence.EntityManager, items: List<Any>) {
        items.forEachIndexed { i, item ->
            em.persist(item)
            if ((i + 1) % 50 == 0) {
                em.flush()
                em.clear() // inside a loop — intentional batch pattern
            }
        }
    }
}

// ── CACHEABLE_ON_PRIVATE_METHOD ───────────────────────────────────────────────

@Service
open class KotlinWave10ProductService {

    /** CACHEABLE_ON_PRIVATE_METHOD: @Cacheable on a private method — proxy cannot intercept. */
    @Cacheable("products")
    private fun loadProduct(id: String): Any? = null // → CACHEABLE_ON_PRIVATE_METHOD

    /** Safe: @Cacheable on an open method — proxy intercepts and populates the cache. */
    @Cacheable("products")
    open fun loadProductSafe(id: String): Any? = null
}

// ── EVENT_LISTENER_ON_PRIVATE_METHOD ─────────────────────────────────────────

@Service
open class KotlinWave10AuditService {

    /** EVENT_LISTENER_ON_PRIVATE_METHOD: @EventListener on a private method — event never dispatched. */
    @EventListener
    private fun onOrderPlaced(event: Any) { // → EVENT_LISTENER_ON_PRIVATE_METHOD
        // silently never called
    }

    /** Safe: @EventListener on an open method — proxy dispatches the event correctly. */
    @EventListener
    open fun onOrderPlacedSafe(event: Any) {}
}

// ── TIMED_ON_PRIVATE_METHOD ───────────────────────────────────────────────────

@Service
open class KotlinWave10CheckoutService {

    /** TIMED_ON_PRIVATE_METHOD: @Timed on a private method — no timing recorded. */
    @Timed(value = "checkout.calculate")
    private fun calculateTotal(items: List<Any>): Double = 0.0 // → TIMED_ON_PRIVATE_METHOD

    /** Safe: @Timed on an open method — proxy intercepts and records the timer. */
    @Timed(value = "checkout.total")
    open fun calculateTotalSafe(items: List<Any>): Double = 0.0
}

// ── NATIVE_QUERY_STRING_CONCAT ────────────────────────────────────────────────

@Repository
open class KotlinWave10OrderRepository {

    /** NATIVE_QUERY_STRING_CONCAT: query built with string concatenation — SQL injection risk. */
    open fun findByStatus(em: javax.persistence.EntityManager, status: String): Any {
        return em.createNativeQuery("SELECT * FROM orders WHERE status = '" + status + "'") // → NATIVE_QUERY_STRING_CONCAT
    }

    /** Safe: parameterized query — status value cannot alter query structure. */
    open fun findByStatusSafe(em: javax.persistence.EntityManager, status: String): Any {
        return em.createNativeQuery("SELECT * FROM orders WHERE status = :status")
            .setParameter("status", status)
    }
}
