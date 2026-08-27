package example.kotlin.parity

/**
 * Fixture: covers TRANSACTIONAL_READONLY_MISSING, ASYNC_DEFAULT_EXECUTOR,
 * MISSING_RESPONSE_STATUS, CACHE_EVICT_MISSING, TRANSACTIONAL_ON_FINAL_METHOD.
 */

// ── TRANSACTIONAL_READONLY_MISSING ────────────────────────────────────────────

@Service
open class KotlinWave4QueryService {

    /** TRANSACTIONAL_READONLY_MISSING: @Transactional on a query method without readOnly=true. */
    @Transactional
    open fun findById(id: Long): String = "" // → TRANSACTIONAL_READONLY_MISSING

    /** Safe: readOnly=true is explicitly set. */
    @Transactional(readOnly = true)
    open fun findByIdSafe(id: Long): String = "" // safe

    /** Safe: 'save' is a write method — not a query prefix. */
    @Transactional
    open fun saveWave4Order(order: String) {} // safe — not a query prefix
}

// ── ASYNC_DEFAULT_EXECUTOR ────────────────────────────────────────────────────

@Service
open class KotlinWave4NotificationService {

    /** ASYNC_DEFAULT_EXECUTOR: @Async without a named executor uses SimpleAsyncTaskExecutor. */
    @Async
    open fun sendEmail(to: String) {} // → ASYNC_DEFAULT_EXECUTOR
}

// ── MISSING_RESPONSE_STATUS ───────────────────────────────────────────────────

@RestControllerAdvice
open class KotlinWave4ExceptionHandler {

    /** MISSING_RESPONSE_STATUS: @ExceptionHandler without @ResponseStatus returns HTTP 200. */
    @ExceptionHandler
    open fun handleError(ex: RuntimeException): String = ex.message ?: "" // → MISSING_RESPONSE_STATUS

    /** Safe: @ResponseStatus is explicitly declared. */
    @ExceptionHandler
    @ResponseStatus
    open fun handleErrorWithStatus(ex: IllegalArgumentException): String = ex.message ?: "" // safe
}

// ── CACHE_EVICT_MISSING ───────────────────────────────────────────────────────

@Service
open class KotlinWave4CacheService {

    /** CACHE_EVICT_MISSING: @Cacheable present but no @CacheEvict or @CachePut in this class. */
    @Cacheable
    open fun findProduct(id: Long): String = "" // → CACHE_EVICT_MISSING
}

@Service
open class KotlinWave4CacheServiceSafe {

    /** Safe: @Cacheable paired with @CacheEvict on update. */
    @Cacheable
    open fun findProduct(id: Long): String = "" // safe — has eviction

    @CacheEvict
    open fun updateProduct(id: Long, data: String) {} // eviction present
}

// ── TRANSACTIONAL_ON_FINAL_METHOD ─────────────────────────────────────────────

@Service
class KotlinWave4FinalMethodService {

    /**
     * TRANSACTIONAL_ON_FINAL_METHOD: Kotlin methods are final by default.
     * Without 'open' or kotlin-spring plugin, CGLIB cannot override this method.
     */
    @Transactional
    fun placeOrder() {} // → TRANSACTIONAL_ON_FINAL_METHOD (final by default in Kotlin)

    /** Safe: 'open' allows CGLIB to override this method. */
    @Transactional
    open fun cancelOrder() {} // safe — open
}
