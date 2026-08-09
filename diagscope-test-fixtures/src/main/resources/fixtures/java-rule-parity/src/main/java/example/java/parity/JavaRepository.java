package example.java.parity;

@Repository
class JavaRepository {
    private final DataSource dataSource;
    private final EntityManagerFactory entityManagerFactory;
    private final JdbcTemplate jdbcTemplate;

    JavaRepository(DataSource dataSource, EntityManagerFactory entityManagerFactory, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.entityManagerFactory = entityManagerFactory;
        this.jdbcTemplate = jdbcTemplate;
    }

    void save(String id) {}

    void connectionLeak() {
        Connection connection = dataSource.getConnection();
        connection.prepareStatement("select 1");
    }

    void connectionClosedOnHappyPath() {
        Connection connection = dataSource.getConnection();
        connection.prepareStatement("select 1");
        connection.close();
    }

    void connectionManagedByTry() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select 1")) {
            statement.executeQuery();
        }
    }

    Object entityManagerLeak(String id) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        return entityManager.find(Object.class, id);
    }

    Object entityManagerClosedInFinally(String id) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try { return entityManager.find(Object.class, id); }
        finally { entityManager.close(); }
    }

    void escapeJdbcTemplate() {
        Connection connection = jdbcTemplate.getDataSource().getConnection();
        connection.prepareStatement("delete from orders");
    }
}
