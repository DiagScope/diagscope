package example.java.parity;

/**
 * Fixture: covers SCHEDULED_NON_VOID_RETURN, AOP_ADVICE_ON_PRIVATE_METHOD,
 * FEIGN_CLIENT_NO_FALLBACK, MULTIPLE_SCHEDULED_NO_THREAD_POOL,
 * OBJECT_MAPPER_CREATED_PER_REQUEST.
 */

// ── SCHEDULED_NON_VOID_RETURN ─────────────────────────────────────────────────

@Service
class JavaWave5ReportService {

    /** SCHEDULED_NON_VOID_RETURN: @Scheduled with non-void return — value is silently discarded. */
    @Scheduled(cron = "0 0 * * * *")
    String generateReport() { return "report"; } // → SCHEDULED_NON_VOID_RETURN

    /** Safe: void return type — Spring scheduler ignores nothing. */
    @Scheduled(cron = "0 30 * * * *")
    void cleanupTempFiles() {} // safe — void
}

// ── AOP_ADVICE_ON_PRIVATE_METHOD ──────────────────────────────────────────────

@Service
class JavaWave5LoggingAspect {

    /** AOP_ADVICE_ON_PRIVATE_METHOD: @Around on private method — proxy cannot intercept it. */
    @Around
    private Object logExecutionTime(Object joinPoint) { return null; } // → AOP_ADVICE_ON_PRIVATE_METHOD

    /** Safe: public visibility — proxy can intercept and execute the advice. */
    @Around
    public Object logPublicMethod(Object joinPoint) { return null; } // safe — public
}

// ── FEIGN_CLIENT_NO_FALLBACK ──────────────────────────────────────────────────

/** FEIGN_CLIENT_NO_FALLBACK: @FeignClient without fallback or fallbackFactory. */
@FeignClient(name = "payment-service")
interface JavaWave5PaymentClient {
    String charge(String orderId); // → FEIGN_CLIENT_NO_FALLBACK (on the type)
}

/** Safe: fallback is explicitly declared. */
@FeignClient(name = "inventory-service", fallback = "JavaWave5InventoryClientFallback")
interface JavaWave5InventoryClient {
    String getStock(String productId); // safe — fallback configured
}

// ── MULTIPLE_SCHEDULED_NO_THREAD_POOL ────────────────────────────────────────
// (The two @Scheduled methods below, combined with others in the project,
//  trigger MULTIPLE_SCHEDULED_NO_THREAD_POOL because no ThreadPoolTaskScheduler @Bean exists.)

@Service
class JavaWave5SchedulerService {

    /** Contributes to MULTIPLE_SCHEDULED_NO_THREAD_POOL count. */
    @Scheduled(fixedRate = 60000)
    void syncInventory() {} // counts toward scheduled total

    /** Contributes to MULTIPLE_SCHEDULED_NO_THREAD_POOL count. */
    @Scheduled(fixedRate = 120000)
    void sendDailyDigest() {} // counts toward scheduled total
}

// ── OBJECT_MAPPER_CREATED_PER_REQUEST ─────────────────────────────────────────

@Service
class JavaWave5JsonService {

    /** OBJECT_MAPPER_CREATED_PER_REQUEST: new ObjectMapper() inside a regular method. */
    String serialize(Object payload) throws Exception {
        return new ObjectMapper().writeValueAsString(payload); // → OBJECT_MAPPER_CREATED_PER_REQUEST
    }

    /** Safe: ObjectMapper is injected as a @Bean (simulated by being a field here). */
    private final ObjectMapper objectMapper = null; // injected via constructor in real code

    String serializeSafe(Object payload) throws Exception {
        return objectMapper.writeValueAsString(payload); // safe — injected mapper reused
    }
}

/** Safe: ObjectMapper constructed inside a @Bean factory method. */
class JavaWave5JacksonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper(); // safe — this is the factory, not a per-call construction
    }
}
