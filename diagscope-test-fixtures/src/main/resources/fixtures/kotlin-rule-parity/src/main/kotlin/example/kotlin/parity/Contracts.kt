package example.kotlin.parity

annotation class RestController
annotation class GetMapping(val value: String)
annotation class Scheduled(val cron: String = "")
annotation class Retryable
annotation class Recover
annotation class Timed(val value: String = "")
annotation class Service
annotation class Repository
annotation class KafkaListener(val topics: Array<String>)
annotation class KafkaHandler
annotation class Transactional(val propagation: Propagation = Propagation.REQUIRED)

enum class Propagation {
    REQUIRED,
    REQUIRES_NEW,
    MANDATORY
}

interface Logger {
    fun info(message: String, vararg arguments: Any?)
    fun warn(message: String, vararg arguments: Any?)
    fun error(message: String, vararg arguments: Any?)
}

interface Completion {
    fun whenComplete(callback: (Any?, Throwable?) -> Unit): Completion
}

interface ExecutorService {
    fun submit(task: () -> Unit): Completion
}

interface KafkaTemplate {
    fun send(topic: String, payload: String): Completion
}

interface Acknowledgment {
    fun acknowledge()
}

interface RemoteResponse {
    fun onErrorReturn(value: String): RemoteResponse
    fun onErrorResume(handler: (Throwable) -> String): RemoteResponse
}

interface RemoteClient {
    fun refresh()
    fun remote(): RemoteResponse
    fun describeFailure(failure: Throwable): String
}

interface DataSource {
    fun getConnection(): Connection
}

interface Connection : AutoCloseable {
    fun prepareStatement(sql: String): PreparedStatement
}

interface PreparedStatement : AutoCloseable {
    fun executeQuery(): ResultSet
}

interface ResultSet : AutoCloseable

interface EntityManagerFactory {
    fun createEntityManager(): EntityManager
}

interface EntityManager : AutoCloseable {
    fun find(type: Any, id: String): Any?
}

interface JdbcTemplate {
    fun getDataSource(): DataSource
}

object MDC {
    fun put(key: String, value: String) {}
    fun remove(key: String) {}
    fun clear() {}
    fun getCopyOfContextMap(): Map<String, String> = emptyMap()
    fun setContextMap(context: Map<String, String>) {}
}
