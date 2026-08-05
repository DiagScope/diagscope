package example.pipeline;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderQueries {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final EntityManagerFactory entityManagerFactory;

    public OrderQueries(JdbcTemplate jdbcTemplate, DataSource dataSource,
                        EntityManagerFactory entityManagerFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.entityManagerFactory = entityManagerFactory;
    }

    /** Closed on the success path only: a failing read leaks the statement and the result set. */
    public String readOnHappyPathOnly(String id) throws java.sql.SQLException {
        Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement("select payload from orders where id = ?");
        statement.setString(1, id);
        ResultSet resultSet = statement.executeQuery();
        String payload = resultSet.next() ? resultSet.getString(1) : null;
        resultSet.close();
        statement.close();
        connection.close();
        return payload;
    }

    /** Application-managed EntityManager that nobody closes. */
    public Object loadOrder(String id) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        return entityManager.find(Object.class, id);
    }

    /** Correct shape: released on every path. */
    public Object loadOrderSafely(String id) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return entityManager.find(Object.class, id);
        } finally {
            entityManager.close();
        }
    }

    /** Raw connection pulled out of the template, detached from the active transaction. */
    public void escapeTemplate(String id) throws java.sql.SQLException {
        Connection connection = jdbcTemplate.getDataSource().getConnection();
        connection.prepareStatement("delete from orders where id = '" + id + "'").executeUpdate();
    }
}
