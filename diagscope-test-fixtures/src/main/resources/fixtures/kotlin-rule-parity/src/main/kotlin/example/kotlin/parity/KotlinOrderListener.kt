package example.kotlin.parity

@KafkaListener(topics = ["orders"])
class KotlinOrderListener(
    private val repository: KotlinRepository,
    private val logger: Logger
) {
    @KafkaHandler
    fun onOrder(payload: String, acknowledgment: Acknowledgment) {
        try {
            repository.save(payload)
        } catch (failure: RuntimeException) {
            logger.warn("Could not process order", failure)
        }
    }

    @KafkaHandler
    fun onOrderSafely(payload: SafePayload, acknowledgment: Acknowledgment) {
        try {
            repository.save(payload.value)
            acknowledgment.acknowledge()
        } catch (failure: RuntimeException) {
            throw failure
        }
    }
}

data class SafePayload(val value: String)
