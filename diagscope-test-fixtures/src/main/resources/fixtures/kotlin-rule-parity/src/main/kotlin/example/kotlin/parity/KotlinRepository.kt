package example.kotlin.parity

@Repository
class KotlinRepository(
    private val dataSource: DataSource,
    private val entityManagerFactory: EntityManagerFactory,
    private val jdbcTemplate: JdbcTemplate
) {
    fun save(id: String) {}

    fun connectionLeak() {
        val connection = dataSource.getConnection()
        connection.prepareStatement("select 1")
    }

    fun connectionClosedOnHappyPath() {
        val connection = dataSource.getConnection()
        connection.prepareStatement("select 1")
        connection.close()
    }

    fun connectionManagedByUse() {
        dataSource.getConnection().use { connection ->
            connection.prepareStatement("select 1").use { statement -> statement.executeQuery() }
        }
    }

    fun entityManagerLeak(id: String): Any? {
        val entityManager = entityManagerFactory.createEntityManager()
        return entityManager.find(Any::class, id)
    }

    fun entityManagerClosedInFinally(id: String): Any? {
        val entityManager = entityManagerFactory.createEntityManager()
        try {
            return entityManager.find(Any::class, id)
        } finally {
            entityManager.close()
        }
    }

    fun escapeJdbcTemplate() {
        val connection = jdbcTemplate.getDataSource().getConnection()
        connection.prepareStatement("delete from orders")
    }
}
