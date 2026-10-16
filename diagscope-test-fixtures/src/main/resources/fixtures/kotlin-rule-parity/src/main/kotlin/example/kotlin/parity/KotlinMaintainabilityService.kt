package example.kotlin.parity

/**
 * Fixture service exercising the maintainability and atomic-operations rules.
 *
 * Each "unsafe" method contains the exact anti-pattern targeted by the corresponding rule.
 * Each "safe" counterpart demonstrates the recommended fix.
 */
@Service
class KotlinMaintainabilityService {

    private val sharedCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sharedItems = java.util.concurrent.CopyOnWriteArrayList<String>()

    // ── CHECK_THEN_ACT_ON_MAP ────────────────────────────────────────────────

    /**
     * TRIGGERS CHECK_THEN_ACT_ON_MAP: containsKey() followed by put() on the same
     * receiver — a classic race condition under concurrent access.
     */
    fun registerIfAbsent(key: String, value: String) {
        if (!sharedCache.containsKey(key)) {
            sharedCache.put(key, value) // non-atomic: another thread can interleave here
        }
    }

    /**
     * SAFE: the same check-then-act on a map created inside the function. It never leaves the function, so no
     * other thread can interleave.
     */
    fun registerIfAbsentLocalOnly(key: String, value: String) {
        val cache = HashMap<String, String>()
        if (!cache.containsKey(key)) {
            cache.put(key, value)
        }
    }

    /**
     * SAFE: replaces the non-atomic pattern with the atomic Map.putIfAbsent().
     */
    fun registerIfAbsentSafe(key: String, value: String) {
        val cache = HashMap<String, String>()
        cache.putIfAbsent(key, value) // atomic — no race window
    }

    /**
     * TRIGGERS CHECK_THEN_ACT_ON_MAP via contains + add on a Collection receiver.
     */
    fun addIfNotPresent(item: String) {
        if (!sharedItems.contains(item)) {
            sharedItems.add(item) // non-atomic
        }
    }

    // ── EXCESSIVE_METHOD_PARAMETERS ──────────────────────────────────────────

    /**
     * TRIGGERS EXCESSIVE_METHOD_PARAMETERS: 6 parameters exceeds the recommended
     * maximum of 5. Callers must track the order and meaning of every argument.
     */
    fun createOrder(
        orderId: String,
        customerId: String,
        productId: String,
        region: String,
        currency: String,
        channel: String
    ): String = "$orderId-$customerId-$productId@$region/$currency($channel)"

    /**
     * SAFE: groups the related parameters into a dedicated data class.
     */
    fun createOrderSafe(request: OrderRequest): String =
        "${request.orderId}-${request.customerId}"

    data class OrderRequest(
        val orderId: String,
        val customerId: String,
        val productId: String,
        val region: String,
        val currency: String,
        val channel: String
    )

    // ── HIGH_METHOD_COMPLEXITY ────────────────────────────────────────────────
    // Covered by the dedicated controller fixture (KotlinComplexityController).
}
