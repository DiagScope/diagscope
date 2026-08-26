package example.java.parity;

import java.util.List;

/**
 * Fixture: exercises EXCEPTION_SUPPRESSED_IN_FINALLY and MASS_ASSIGNMENT_RISK.
 */

// ── EXCEPTION_SUPPRESSED_IN_FINALLY ──────────────────────────────────────────

/**
 * Service demonstrating throw-in-finally pattern that suppresses original exceptions.
 */
@Service
class JavaExceptionHandlingService {

    private final JavaRepository repository;
    private final Logger logger;

    JavaExceptionHandlingService(JavaRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    /**
     * EXCEPTION_SUPPRESSED_IN_FINALLY: if repository.save() throws, the cleanup exception
     * from finally replaces it — root cause is silently discarded.
     */
    void persistWithCleanup(String id) {
        try {
            repository.save(id);          // may throw
        } finally {
            throw new RuntimeException("cleanup failed"); // → EXCEPTION_SUPPRESSED_IN_FINALLY
        }
    }

    /**
     * Safe: finally only calls cleanup — no throw statement.
     */
    void persistWithSafeCleanup(String id) {
        try {
            repository.save(id);
        } finally {
            logger.info("cleanup complete");  // no throw — safe
        }
    }
}

// ── MASS_ASSIGNMENT_RISK ──────────────────────────────────────────────────────

/**
 * REST controller that incorrectly accepts a JPA entity as a request body.
 */
@RestController
class JavaOrderWriteController {

    private final JavaRepository repository;

    JavaOrderWriteController(JavaRepository repository) {
        this.repository = repository;
    }

    /**
     * MASS_ASSIGNMENT_RISK: accepts JavaOrderEntity directly — attacker can set 'id', 'status', etc.
     */
    @PostMapping("/orders")
    String createOrder(JavaOrderEntity order) { // → MASS_ASSIGNMENT_RISK
        repository.save(order.getId().toString());
        return order.getId().toString();
    }

    /**
     * Safe: accepts a dedicated DTO — only the fields the caller may set.
     */
    @PostMapping("/orders/safe")
    String createOrderSafe(CreateJavaOrderRequest request) { // safe — DTO, not entity
        repository.save(request.customerId);
        return request.customerId;
    }

    /**
     * Safe: GET endpoint — read-only, mass assignment not applicable.
     */
    @GetMapping("/orders/{id}")
    JavaOrderEntity getOrder(String id) {
        return new JavaOrderEntity();
    }
}

/**
 * Controller entrypoint that touches both fixture services, making all methods reachable.
 */
@RestController
class JavaSecurityController {

    private final JavaExceptionHandlingService exceptionService;

    JavaSecurityController(JavaExceptionHandlingService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @GetMapping("/security-and-exception")
    String trigger(String id) {
        exceptionService.persistWithCleanup(id); // → EXCEPTION_SUPPRESSED_IN_FINALLY
        return id;
    }
}
