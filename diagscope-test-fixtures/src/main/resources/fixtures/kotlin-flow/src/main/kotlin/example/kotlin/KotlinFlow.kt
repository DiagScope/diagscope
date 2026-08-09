package example.kotlin

@RestController
@RequestMapping("/api")
class KotlinController(private val service: KotlinService) {
    @GetMapping("/payments/{id}")
    fun payment(id: String): Boolean = service.load(id)
}

@Service
class KotlinService(private val kafkaTemplate: KafkaTemplate) {
    fun load(id: String): Boolean {
        return try {
            kafkaTemplate.send("payments", id)
            true
        } catch (exception: RuntimeException) {
            false
        }
    }

    @Scheduled(cron = "0 */5 * * * *")
    fun reconcile() {
        try {
            work()
        } catch (ignored: Exception) {
        }
    }

    private fun work() {
        System.err.println("reconciliation failed")
    }
}
