package example.pipeline;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OrderRepository {

    private final DataSource dataSource;

    public OrderRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Transactional
    public void store(String payload) {
        try {
            insert(payload);
        } catch (RuntimeException exception) {
            log.error("insert failed", exception);
        }
    }

    public void insert(String payload) throws java.sql.SQLException {
        Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement("insert into orders values (?)");
        statement.setString(1, payload);
        statement.executeUpdate();
    }

    public void insertSafely(String payload) throws java.sql.SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("insert into orders values (?)")) {
            statement.setString(1, payload);
            statement.executeUpdate();
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderRepository.class);
}
