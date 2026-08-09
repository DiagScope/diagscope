package example.policy

annotation class BatchBoundary

class AuditChannel {
    fun error(message: String) = Unit
    fun info(message: String, value: Any) = Unit
}

class KotlinPolicyJob(private val auditChannel: AuditChannel) {
    @BatchBoundary
    fun run(secretAlias: String) {
        try {
            process()
        } catch (failure: IllegalStateException) {
            auditChannel.error("failed")
            auditChannel.info("alias {}", secretAlias)
        }
    }

    private fun process(): Nothing = error("failed")
}
