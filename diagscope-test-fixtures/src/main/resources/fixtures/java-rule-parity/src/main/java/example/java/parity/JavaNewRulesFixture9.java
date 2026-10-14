package example.java.parity;

import java.util.concurrent.CompletableFuture;
import javax.persistence.EntityManager;

/**
 * Fixture: covers MISSING_REQUEST_BODY_VALIDATION, TRANSACTIONAL_ON_REST_CONTROLLER,
 * APPLICATION_CONTEXT_LOCATOR, ASYNC_VOID_RETURN, CONFIGURATION_PROPERTIES_NOT_VALIDATED,
 * CIRCUIT_BREAKER_WITHOUT_FALLBACK, BULKHEAD_WITHOUT_FALLBACK, RATE_LIMITER_WITHOUT_FALLBACK,
 * THREAD_LOCAL_NOT_REMOVED, ENTITY_MANAGER_CLEAR_IN_TRANSACTION,
 * CACHEABLE_ON_PRIVATE_METHOD, EVENT_LISTENER_ON_PRIVATE_METHOD,
 * TIMED_ON_PRIVATE_METHOD, NATIVE_QUERY_STRING_CONCAT.
 */

// ── MISSING_REQUEST_BODY_VALIDATION ──────────────────────────────────────────

@RestController
class JavaWave10OrderController {

    /** MISSING_REQUEST_BODY_VALIDATION: no @Validated on the controller class, write endpoint with params. */
    @PostMapping("/wave10/orders")
    public Object createOrder(Object requestBody) { // → MISSING_REQUEST_BODY_VALIDATION
        return null;
    }

    /** Safe: @Validated on the controller activates constraint validation. */
    @GetMapping("/wave10/orders/{id}")
    public Object getOrder(String id) {
        return null;
    }
}

@RestController
@Validated
class JavaWave10OrderControllerSafe {

    /** Safe: controller has @Validated — @Valid on @RequestBody parameters is active. */
    @PostMapping("/wave10/orders/safe")
    public Object createOrderSafe(Object requestBody) {
        return null;
    }
}

// ── TRANSACTIONAL_ON_REST_CONTROLLER ─────────────────────────────────────────

@RestController
class JavaWave10PaymentController {

    /** TRANSACTIONAL_ON_REST_CONTROLLER: @Transactional on a REST controller method. */
    @PostMapping("/wave10/payments")
    @Transactional
    public Object processPayment(Object requestBody) { // → TRANSACTIONAL_ON_REST_CONTROLLER
        return null;
    }

    /** Safe: no transaction boundary on the controller — delegated to the service layer. */
    @GetMapping("/wave10/payments/{id}")
    public Object getPayment(String id) {
        return null;
    }
}

// ── APPLICATION_CONTEXT_LOCATOR ───────────────────────────────────────────────

@Service
class JavaWave10LegacyOrderService {

    /** APPLICATION_CONTEXT_LOCATOR: getBean() inside a service method — hidden dependency. */
    public Object processLegacyOrder(String orderId) { // → APPLICATION_CONTEXT_LOCATOR
        Object pricingService = applicationContext.getBean("pricingService");
        return pricingService;
    }

    /** Safe: dependency declared as constructor parameter — Spring injects it. */
    public Object processModernOrder(String orderId, Object pricingService) {
        return pricingService;
    }
}

// ── ASYNC_VOID_RETURN ─────────────────────────────────────────────────────────

@Service
class JavaWave10NotificationService {

    /** ASYNC_VOID_RETURN: @Async with void return — exceptions silently discarded. */
    @Async
    public void sendEmail(String recipient, String body) { // → ASYNC_VOID_RETURN
        // if this throws, the exception is swallowed by SimpleAsyncUncaughtExceptionHandler
    }

    /** Safe: returns CompletableFuture<Void> — caller can observe exceptions. */
    @Async
    public CompletableFuture<Void> sendEmailSafe(String recipient, String body) {
        return CompletableFuture.completedFuture(null);
    }
}

// ── CONFIGURATION_PROPERTIES_NOT_VALIDATED ────────────────────────────────────

@ConfigurationProperties(prefix = "app.payment")
class JavaWave10PaymentProperties {

    /** CONFIGURATION_PROPERTIES_NOT_VALIDATED: @ConfigurationProperties without @Validated. */
    public String getGatewayUrl() { return gatewayUrl; } // → CONFIGURATION_PROPERTIES_NOT_VALIDATED (type-level)

    private String gatewayUrl;
}

@ConfigurationProperties(prefix = "app.notification")
@Validated
class JavaWave10NotificationPropertiesSafe {

    /** Safe: @Validated present — @NotNull/@Pattern on fields are enforced at startup. */
    public String getEmailHost() { return emailHost; }

    private String emailHost;
}

// ── CIRCUIT_BREAKER_WITHOUT_FALLBACK ─────────────────────────────────────────

@Service
class JavaWave10InventoryClient {

    /** CIRCUIT_BREAKER_WITHOUT_FALLBACK: no fallbackMethod declared. */
    @CircuitBreaker(name = "inventory")
    public Object getInventory(String productId) { // → CIRCUIT_BREAKER_WITHOUT_FALLBACK
        return null;
    }

    /** Safe: fallbackMethod provides graceful degradation when circuit opens. */
    @CircuitBreaker(name = "inventory", fallbackMethod = "getInventoryFallback")
    public Object getInventorySafe(String productId) {
        return null;
    }

    public Object getInventoryFallback(String productId, Throwable t) {
        return java.util.Map.of("available", false);
    }
}

// ── BULKHEAD_WITHOUT_FALLBACK ─────────────────────────────────────────────────

@Service
class JavaWave10SearchService {

    /** BULKHEAD_WITHOUT_FALLBACK: no fallbackMethod declared. */
    @Bulkhead(name = "search")
    public Object search(String query) { // → BULKHEAD_WITHOUT_FALLBACK
        return null;
    }

    /** Safe: fallbackMethod returns degraded result when bulkhead is full. */
    @Bulkhead(name = "search", fallbackMethod = "searchFallback")
    public Object searchSafe(String query) {
        return null;
    }

    public Object searchFallback(String query, Throwable t) {
        return java.util.Collections.emptyList();
    }
}

// ── RATE_LIMITER_WITHOUT_FALLBACK ─────────────────────────────────────────────

@Service
class JavaWave10PricingClient {

    /** RATE_LIMITER_WITHOUT_FALLBACK: no fallbackMethod declared. */
    @RateLimiter(name = "pricing")
    public Object getPrice(String productId) { // → RATE_LIMITER_WITHOUT_FALLBACK
        return null;
    }

    /** Safe: fallbackMethod returns cached price when rate limit is exceeded. */
    @RateLimiter(name = "pricing", fallbackMethod = "getPriceFallback")
    public Object getPriceSafe(String productId) {
        return null;
    }

    public Object getPriceFallback(String productId, Throwable t) {
        return java.util.Map.of("price", 0.0, "cached", true);
    }
}

// ── THREAD_LOCAL_NOT_REMOVED ──────────────────────────────────────────────────

@Service
class JavaWave10RequestContextService {

    private static final ThreadLocal<String> threadLocalUserId = new ThreadLocal<>();

    /** THREAD_LOCAL_NOT_REMOVED: set() without remove() — value leaks to next request on same thread. */
    public void setUser(String userId) {
        threadLocalUserId.set(userId); // → THREAD_LOCAL_NOT_REMOVED
        doWork();
    }

    /** Safe: remove() in finally ensures no value leaks across requests. */
    public void setUserSafe(String userId) {
        try {
            threadLocalUserId.set(userId);
            doWork();
        } finally {
            threadLocalUserId.remove(); // always removed, even on exception
        }
    }

    private void doWork() {}
}

// ── ENTITY_MANAGER_CLEAR_IN_TRANSACTION ──────────────────────────────────────

@Service
class JavaWave10OrderBatchService {

    /** ENTITY_MANAGER_CLEAR_IN_TRANSACTION: clear() outside a loop detaches managed entities. */
    @Transactional
    public void reprocessOrder(EntityManager em, Object order) {
        em.clear(); // → ENTITY_MANAGER_CLEAR_IN_TRANSACTION — entities already loaded are now detached
        // mutations to 'order' after this line are silently ignored
    }

    /** Safe: clear() inside a batch loop with flush() before — intentional pattern. */
    @Transactional
    public void processBatch(EntityManager em, java.util.List<Object> items) {
        int i = 0;
        for (Object item : items) {
            em.persist(item);
            if (++i % 50 == 0) {
                em.flush();
                em.clear(); // inside a loop — intentional batch pattern
            }
        }
    }
}

// ── CACHEABLE_ON_PRIVATE_METHOD ───────────────────────────────────────────────

@Service
class JavaWave10ProductService {

    /** CACHEABLE_ON_PRIVATE_METHOD: @Cacheable on a private method — proxy cannot intercept. */
    @Cacheable("products")
    private Object loadProduct(String id) { // → CACHEABLE_ON_PRIVATE_METHOD
        return null; // cache never populated — always calls through
    }

    /** Safe: @Cacheable on a public method — proxy intercepts and populates the cache. */
    @Cacheable("products")
    public Object loadProductSafe(String id) {
        return null;
    }
}

// ── EVENT_LISTENER_ON_PRIVATE_METHOD ─────────────────────────────────────────

@Service
class JavaWave10AuditService {

    /** EVENT_LISTENER_ON_PRIVATE_METHOD: @EventListener on a private method — event never dispatched. */
    @EventListener
    private void onOrderPlaced(Object event) { // → EVENT_LISTENER_ON_PRIVATE_METHOD
        // silently never called — event is published, listener never runs
    }

    /** Safe: @EventListener on a public method — proxy dispatches the event correctly. */
    @EventListener
    public void onOrderPlacedSafe(Object event) {
        // listener is invoked when the event is published
    }
}

// ── TIMED_ON_PRIVATE_METHOD ───────────────────────────────────────────────────

@Service
class JavaWave10CheckoutService {

    /** TIMED_ON_PRIVATE_METHOD: @Timed on a private method — no timing recorded. */
    @Timed(value = "checkout.calculate", description = "Time to calculate checkout total")
    private double calculateTotal(java.util.List<Object> items) { // → TIMED_ON_PRIVATE_METHOD
        return 0.0; // proxy cannot intercept — no metric ever recorded
    }

    /** Safe: @Timed on a public method — proxy intercepts and records the timer. */
    @Timed(value = "checkout.total")
    public double calculateTotalSafe(java.util.List<Object> items) {
        return 0.0;
    }
}

// ── NATIVE_QUERY_STRING_CONCAT ────────────────────────────────────────────────

@Repository
class JavaWave10OrderRepository {

    /** NATIVE_QUERY_STRING_CONCAT: query built with string concatenation — SQL injection risk. */
    public Object findByStatus(EntityManager em, String status) {
        return em.createNativeQuery("SELECT * FROM orders WHERE status = '" + status + "'"); // → NATIVE_QUERY_STRING_CONCAT
    }

    /** Safe: parameterized query — value is bound separately, cannot alter query structure. */
    public Object findByStatusSafe(EntityManager em, String status) {
        return em.createNativeQuery("SELECT * FROM orders WHERE status = :status")
                .setParameter("status", status);
    }
}
