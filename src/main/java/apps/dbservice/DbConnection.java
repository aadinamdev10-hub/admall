package apps.dbservice;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Shared HikariCP connection pool for all application modules.
 *
 * Configuration priority (highest to lowest):
 *   1. Environment variables: DB_URL, DB_USERNAME, DB_PASSWORD
 *   2. db.properties in classpath
 *
 * For production deployments, always set DB_PASSWORD (and ideally DB_URL,
 * DB_USERNAME) via environment variables rather than storing them in
 * db.properties. Example in Tomcat setenv.sh:
 *   export DB_URL="jdbc:mysql://prod-host:3306/App2026?useSSL=true&serverTimezone=UTC"
 *   export DB_USERNAME="appuser"
 *   export DB_PASSWORD="your_secure_password"
 */
public class DbConnection {
    private static HikariDataSource dataSource;

    static {
        try {
            // Load db.properties as baseline configuration
            Properties props = new Properties();
            try (InputStream is = DbConnection.class.getClassLoader().getResourceAsStream("db.properties")) {
                if (is == null) {
                    throw new RuntimeException("Could not find db.properties in classpath");
                }
                props.load(is);
            }

            // Resolve each credential: env var takes priority over db.properties
            String url      = resolveConfig("DB_URL",      props.getProperty("db.url"));
            String username = resolveConfig("DB_USERNAME",  props.getProperty("db.username"));
            String password = resolveConfig("DB_PASSWORD",  props.getProperty("db.password"));

            if (url == null || url.isBlank()) {
                throw new RuntimeException("Database URL is not configured (DB_URL env var or db.url property)");
            }
            if (username == null || username.isBlank()) {
                throw new RuntimeException("Database username is not configured (DB_USERNAME env var or db.username property)");
            }
            if (password == null) {
                throw new RuntimeException("Database password is not configured (DB_PASSWORD env var or db.password property)");
            }

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(username);
            config.setPassword(password);

            int poolSize = Integer.parseInt(props.getProperty("db.pool.size", "10"));
            config.setMaximumPoolSize(poolSize);
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            // Performance & fast fail-over settings (prevents 30-second hang on DB delay)
            config.setConnectionTimeout(3000);       // 3 seconds max wait for connection
            config.setValidationTimeout(1500);       // 1.5 seconds max validation
            config.setInitializationFailTimeout(-1);  // Non-blocking startup

            config.addDataSourceProperty("cachePrepStmts",      "true");
            config.addDataSourceProperty("prepStmtCacheSize",    "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit","2048");

            dataSource = new HikariDataSource(config);

            // Log source of credentials (never log actual values)
            System.out.println("[DbConnection] Initialized HikariCP pool.");
            System.out.println("[DbConnection] DB_URL source:      " + (System.getenv("DB_URL")      != null ? "env var" : "db.properties"));
            System.out.println("[DbConnection] DB_USERNAME source:  " + (System.getenv("DB_USERNAME") != null ? "env var" : "db.properties"));
            System.out.println("[DbConnection] DB_PASSWORD source:  " + (System.getenv("DB_PASSWORD") != null ? "env var" : "db.properties"));

        } catch (Exception e) {
            e.printStackTrace();
            throw new ExceptionInInitializerError("Failed to initialize HikariCP connection pool: " + e.getMessage());
        }
    }

    /**
     * Returns the value of the given environment variable if set and non-blank,
     * otherwise falls back to the provided default value.
     */
    private static String resolveConfig(String envVarName, String fallback) {
        String envValue = System.getenv(envVarName);
        return (envValue != null && !envValue.isBlank()) ? envValue.trim() : fallback;
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public static void closePool() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
