package example.java.parity;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Fixture: covers EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE, PROPAGATION_SUPPORTS_WRITE_RISK,
 * STREAM_IO_NOT_CLOSED, LOG_MESSAGE_STRING_CONCAT, CACHE_NAME_MISMATCH,
 * SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE.
 */

// ── EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE ─────────────────────────────────────

@RestController
class JavaWave7ExceptionService {

    /** EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE: no-arg exception constructor — getMessage() is null. */
    @GetMapping("/wave7/orders/{orderId}")
    String processOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) throw new RuntimeException(); // → EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE
        return orderId;
    }

    /** Safe: exception includes a descriptive message with domain context. */
    @GetMapping("/wave7/orders/safe/{orderId}")
    String processOrderSafe(String orderId) {
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("orderId must not be blank");
        return orderId;
    }
}

// ── PROPAGATION_SUPPORTS_WRITE_RISK ───────────────────────────────────────────

@Service
class JavaWave7AuditService {

    private final Object auditRepository = null;

    /** PROPAGATION_SUPPORTS_WRITE_RISK: SUPPORTS propagation with a write operation. */
    @Transactional(propagation = "SUPPORTS")
    void recordAudit(Object entry) {
        auditRepository.save(entry); // → PROPAGATION_SUPPORTS_WRITE_RISK
    }

    /** Safe: REQUIRED (default) guarantees a transaction exists before any write. */
    @Transactional
    void recordAuditSafe(Object entry) {
        auditRepository.save(entry); // safe — REQUIRED ensures transactional context
    }
}

// ── STREAM_IO_NOT_CLOSED ──────────────────────────────────────────────────────

@RestController
class JavaWave7FileService {

    /** STREAM_IO_NOT_CLOSED: Files.walk() without try-with-resources. */
    @GetMapping("/wave7/files/count")
    long countFiles(String dir) throws Exception {
        var stream = Files.walk(Paths.get(dir)); // → STREAM_IO_NOT_CLOSED — not closed
        return stream.count();
    }

    /** Safe: try-with-resources ensures the stream is always closed. */
    @GetMapping("/wave7/files/count/safe")
    long countFilesSafe(String dir) throws Exception {
        try (var stream = Files.walk(Paths.get(dir))) {
            return stream.count(); // safe — closed on exit
        }
    }
}

// ── LOG_MESSAGE_STRING_CONCAT ─────────────────────────────────────────────────

@RestController
class JavaWave7LoggingService {

    private final Logger logger = null;

    /** LOG_MESSAGE_STRING_CONCAT: string concatenation in logger argument. */
    @PostMapping("/wave7/payments")
    void handlePayment(String orderId, double amount) {
        logger.debug("Processing payment for order " + orderId + " amount=" + amount); // → LOG_MESSAGE_STRING_CONCAT
    }

    /** Safe: SLF4J parameterised substitution — message built only when level is enabled. */
    @PostMapping("/wave7/payments/safe")
    void handlePaymentSafe(String orderId, double amount) {
        logger.debug("Processing payment for order {} amount={}", orderId, amount);
    }
}

// ── CACHE_NAME_MISMATCH ───────────────────────────────────────────────────────

@Service
class JavaWave7ProductService {

    private final Object repo = null;

    /** CACHE_NAME_MISMATCH target: @Cacheable stores under "products". */
    @Cacheable(value = "products")
    Object findProduct(String id) { return repo.findById(id); }

    /** CACHE_NAME_MISMATCH: @CacheEvict targets "product-cache" — mismatched name. */
    @CacheEvict(value = "product-cache")
    void updateProduct(Object product) { repo.save(product); } // → CACHE_NAME_MISMATCH
}

/** Safe: @Cacheable and @CacheEvict use the same cache name. */
@Service
class JavaWave7CategoriesService {

    private final Object repo = null;

    @Cacheable(value = "categories")
    Object findCategory(String id) { return repo.findById(id); }

    @CacheEvict(value = "categories")
    void updateCategory(Object category) { repo.save(category); } // safe — names match
}

// ── SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE ───────────────────────────────────────

@Service
class JavaWave7PollingService {

    private final Object statusRepo = null;

    /** SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE: fixedRate = 100ms with non-trivial body. */
    @Scheduled(fixedRate = 100)
    void pollForUpdates() {
        var pending = statusRepo.findPending();  // → SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE
        statusRepo.updateAll(pending);
    }

    /** Safe: fixedRate well above threshold — a 30-second interval is reasonable. */
    @Scheduled(fixedRate = 30000)
    void syncInventorySafe() {
        var items = statusRepo.findOutdated();
        statusRepo.refreshAll(items);
    }
}
