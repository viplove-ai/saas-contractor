package in.nirman;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Base for every integration test. Flyway applies the real migrations plus the dev seed,
 * so a broken migration or seed fails the build rather than a deployment, and tests can
 * log in as the seeded users.
 *
 * <p>Two ways to get a database, because the primary dev machine (macOS 12) cannot run a
 * Docker daemon:</p>
 * <ol>
 *   <li><b>Docker present</b> — one PostgreSQL Testcontainer for the whole suite.</li>
 *   <li><b>No Docker</b> — the local postgresql@16 that {@code scripts/dev-db.sh} manages,
 *       using a separate {@code nirman_test} database whose schema is dropped and
 *       recreated before Flyway runs. The dev database is never touched.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final String LOCAL_TEST_DB = "nirman_test";
    private static final String LOCAL_USER = System.getenv().getOrDefault("DB_USER", "nirman");
    private static final String LOCAL_PASSWORD =
            System.getenv().getOrDefault("DB_PASSWORD", "nirman_dev_password");
    private static final String LOCAL_PORT = System.getenv().getOrDefault("DB_PORT", "5432");

    private static final String JDBC_URL;
    private static final String DB_USER;
    private static final String DB_PASSWORD;

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName(LOCAL_TEST_DB)
                    .withUsername("nirman")
                    .withPassword("nirman_test");
            postgres.start();
            JDBC_URL = postgres.getJdbcUrl();
            DB_USER = postgres.getUsername();
            DB_PASSWORD = postgres.getPassword();
        } else {
            JDBC_URL = "jdbc:postgresql://localhost:" + LOCAL_PORT + "/" + LOCAL_TEST_DB;
            DB_USER = LOCAL_USER;
            DB_PASSWORD = LOCAL_PASSWORD;
            prepareLocalDatabase();
        }
    }

    /** Creates nirman_test if missing, then resets its schema so Flyway starts clean. */
    private static void prepareLocalDatabase() {
        String bootstrapUrl = "jdbc:postgresql://localhost:" + LOCAL_PORT + "/nirman";
        try (Connection conn = DriverManager.getConnection(bootstrapUrl, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + LOCAL_TEST_DB + "'")) {
                if (!rs.next()) {
                    stmt.execute("CREATE DATABASE " + LOCAL_TEST_DB);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("""
                    No Docker daemon and the local dev database is not reachable. \
                    Run ./scripts/dev-db.sh first (see README).""", e);
        }
        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA public CASCADE");
            stmt.execute("CREATE SCHEMA public");
        } catch (Exception e) {
            throw new IllegalStateException("Could not reset the nirman_test schema", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> DB_PASSWORD);
        // Seed included on purpose: tests authenticate as the seeded users.
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed");
        registry.add("app.jwt.secret", () -> "test_secret_test_secret_test_secret_test_secret_32");
    }
}
