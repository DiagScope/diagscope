package example.java.parity;

/** Fixture for AUTOWIRED_REQUIRED_FALSE_UNSAFE. */
@RestController
class JavaOptionalAutowiredService {
    @Autowired(required = false)
    private JavaAuditClient auditClient;

    // TRIGGERS: AUTOWIRED_REQUIRED_FALSE_UNSAFE — the optional field is used without a null check
    @GetMapping("/optional/unsafe")
    public void recordUnsafe(String event) {
        auditClient.record(event);
    }

    // SAFE: the field is compared with null first
    @GetMapping("/optional/safe")
    public void recordSafe(String event) {
        if (auditClient != null) {
            auditClient.record(event);
        }
    }
}

class JavaAuditClient {
    void record(String event) {
    }
}
