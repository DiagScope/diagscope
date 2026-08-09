package example.policy;

@interface BusinessEndpoint {
}

final class CustomAuditSink {
    void error(String message) {
    }

    void info(String message, Object value) {
    }
}

final class JavaPolicyEndpoint {
    private final CustomAuditSink auditSink = new CustomAuditSink();

    @BusinessEndpoint
    void execute(String accountNumber) {
        try {
            charge();
        } catch (RuntimeException failure) {
            auditSink.error("failed");
            auditSink.info("account {}", accountNumber);
        }
    }

    private void charge() {
        throw new IllegalStateException("declined");
    }
}
