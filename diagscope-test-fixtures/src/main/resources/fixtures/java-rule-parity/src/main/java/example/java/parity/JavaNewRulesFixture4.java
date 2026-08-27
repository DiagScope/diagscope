package example.java.parity;

/**
 * Fixture: covers TRANSACTIONAL_READONLY_MISSING, ASYNC_DEFAULT_EXECUTOR,
 * MISSING_RESPONSE_STATUS, CACHE_EVICT_MISSING, TRANSACTIONAL_ON_FINAL_METHOD.
 */

// ── TRANSACTIONAL_READONLY_MISSING ────────────────────────────────────────────

@Service
class JavaWave4QueryService {

    /** TRANSACTIONAL_READONLY_MISSING: @Transactional on a query method without readOnly=true. */
    @Transactional
    String findById(long id) { return ""; } // → TRANSACTIONAL_READONLY_MISSING

    /** Safe: readOnly=true is explicitly set. */
    @Transactional(readOnly = true)
    String findByIdSafe(long id) { return ""; } // safe

    /** Safe: 'save' is a write method — not a query prefix. */
    @Transactional
    void saveWave4Order(String order) {} // safe — not a query prefix
}

// ── ASYNC_DEFAULT_EXECUTOR ────────────────────────────────────────────────────

@Service
class JavaWave4NotificationService {

    /** ASYNC_DEFAULT_EXECUTOR: @Async without a named executor uses SimpleAsyncTaskExecutor. */
    @Async
    void sendEmail(String to) {} // → ASYNC_DEFAULT_EXECUTOR
}

// ── MISSING_RESPONSE_STATUS ───────────────────────────────────────────────────

@RestControllerAdvice
class JavaWave4ExceptionHandler {

    /** MISSING_RESPONSE_STATUS: @ExceptionHandler without @ResponseStatus returns HTTP 200. */
    @ExceptionHandler
    String handleError(RuntimeException ex) { return ex.getMessage(); } // → MISSING_RESPONSE_STATUS

    /** Safe: @ResponseStatus is explicitly declared. */
    @ExceptionHandler
    @ResponseStatus
    String handleErrorWithStatus(IllegalArgumentException ex) { return ex.getMessage(); } // safe
}

// ── CACHE_EVICT_MISSING ───────────────────────────────────────────────────────

@Service
class JavaWave4CacheService {

    /** CACHE_EVICT_MISSING: @Cacheable present but no @CacheEvict or @CachePut in this class. */
    @Cacheable
    String findProduct(long id) { return ""; } // → CACHE_EVICT_MISSING (no eviction in class)
}

@Service
class JavaWave4CacheServiceSafe {

    /** Safe: @Cacheable paired with @CacheEvict on update. */
    @Cacheable
    String findProduct(long id) { return ""; } // safe — has eviction

    @CacheEvict
    void updateProduct(long id, String data) {} // eviction present
}

// ── TRANSACTIONAL_ON_FINAL_METHOD ─────────────────────────────────────────────

@Service
class JavaWave4FinalMethodService {

    /** TRANSACTIONAL_ON_FINAL_METHOD: final method with @Transactional — CGLIB cannot override it. */
    @Transactional
    final void placeOrder() {} // → TRANSACTIONAL_ON_FINAL_METHOD

    /** Safe: non-final method — CGLIB can proxy it. */
    @Transactional
    void cancelOrder() {} // safe — not final
}
