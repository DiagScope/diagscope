package example.kotlin.parity

/** Fixture for AUTOWIRED_REQUIRED_FALSE_UNSAFE. */
@RestController
open class KotlinOptionalAutowiredService {
    @Autowired(required = false)
    lateinit var auditClient: KotlinAuditClient

    // TRIGGERS: AUTOWIRED_REQUIRED_FALSE_UNSAFE — the optional property is used without a null check
    @GetMapping("/optional/unsafe")
    open fun recordUnsafe(event: String) {
        auditClient.record(event)
    }

    // SAFE: the property is compared with null first
    @GetMapping("/optional/safe")
    open fun recordSafe(event: String) {
        if (::auditClient.isInitialized && auditClient != null) {
            auditClient.record(event)
        }
    }
}

open class KotlinAuditClient {
    open fun record(event: String) {
    }
}
