package example.kotlin.parity

@Service
class KotlinTransactionAndHttpService(
    private val userRepository: UserRepository,
    private val entityManager: EntityManager,
    private val webClient: WebClient,
    private val cache: MutableMap<String, Any>
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

    // TRIGGERS: READONLY_TRANSACTION_WRITE — readOnly transaction performs a write
    @Transactional(readOnly = true)
    fun saveInsideReadOnlyTransaction(entity: Any) {
        userRepository.save(entity)
    }

    // TRIGGERS: ENTITY_MANAGER_FIND_DEREFERENCE — EntityManager.find may return null
    fun findAndDereference(id: String): String {
        val order = entityManager.find(KotlinOrderEntity::class, id)
        return order!!.hashCode().toString()
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

    // TRIGGERS: OPTIONAL_OR_ELSE_NULL — orElse(null) propagates null instead of enforcing presence
    fun lookupOrNull(key: String): Any? {
        return Optional.ofNullable(cache[key]).orElse(null)
    }

    // SAFE: orElse with a real fallback
    fun lookupWithFallback(key: String): Any {
        return Optional.ofNullable(cache[key]).orElse(Any())
    }

    // TRIGGERS: MAP_GET_DEREFERENCED_WITHOUT_CHECK — Map.get result used without null guard
    fun getAndDereference(key: String): String {
        val value = cache.get(key)
        return value!!.toString()
    }

    // SAFE: getOrDefault provides a non-null fallback
    fun getWithDefault(key: String): String {
        val value = cache.getOrDefault(key, "")
        return value.toString()
    }

    // TRIGGERS: TRANSACTION_ISOLATION_DANGEROUS — READ_UNCOMMITTED enables dirty reads
    @Transactional(isolation = Isolation.READ_UNCOMMITTED)
    fun fetchWithDirtyReads(): List<Any> {
        return userRepository.findAll()
    }

    // SAFE: default isolation (READ_COMMITTED on most databases)
    @Transactional
    fun fetchWithDefaultIsolation(): List<Any> {
        return userRepository.findAll()
    }

    // TRIGGERS: JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR — persist in loop without flush/clear
    @Transactional
    fun importEntities(entities: List<Any>) {
        for (entity in entities) {
            entityManager.persist(entity)
        }
    }

    // SAFE: flush + clear every batch to bound the persistence context
    @Transactional
    fun importEntitiesSafe(entities: List<Any>) {
        entities.forEachIndexed { index, entity ->
            entityManager.persist(entity)
            if ((index + 1) % 50 == 0) {
                entityManager.flush()
                entityManager.clear()
            }
        }
        entityManager.flush()
        entityManager.clear()
    }

    // REQUIRES_NEW: each invocation suspends the outer transaction and opens a fresh one
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processItemInNewTransaction(item: Any) {
        userRepository.save(item)
    }

    // SAFE: the loop lives inside the REQUIRES_NEW method itself — no per-iteration transaction boundary
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processItemBatch(items: List<Any>) {
        for (item in items) {
            userRepository.save(item)
        }
    }
}
