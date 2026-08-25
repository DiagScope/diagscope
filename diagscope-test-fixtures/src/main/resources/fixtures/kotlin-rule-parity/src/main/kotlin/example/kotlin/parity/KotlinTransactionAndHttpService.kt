package example.kotlin.parity

@Service
class KotlinTransactionAndHttpService(
    private val userRepository: UserRepository,
    private val webClient: WebClient
) {
    // TRIGGERS: MISSING_TRANSACTION_ANNOTATION — write op with no @Transactional
    fun saveWithoutTransaction(entity: Any) {
        userRepository.save(entity)
    }

    // SAFE: @Transactional provides the boundary
    @Transactional
    fun saveWithTransaction(entity: Any) {
        userRepository.save(entity)
    }

    // TRIGGERS: HTTP_TIMEOUT_NOT_SET — assigns to 'response' (HTTP hint) then blocks without timeout
    fun callWithoutTimeout(uri: String): String {
        val response: Mono<String> = webClient.get(uri)
        return response.block()
    }

    // SAFE: timeout is configured before blocking
    fun callWithTimeout(uri: String): String {
        val response: Mono<String> = webClient.get(uri)
        return response.timeout("PT5S").block()
    }
}
