package apps.admall.util;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import apps.admall.servlet.PropertyFileConnection;

/**
 * <h2>DBHelper</h2>
 * <p>
 * Database utility helper class that handles JDBC driver loading, resource retrieval of database configuration properties,
 * and connection creation factory methods for MySQL database interactions.
 * </p>
 *
 * <h3>Design & Architecture</h3>
 * <ul>
 *   <li><b>Singleton Configuration Initialization:</b> Config parameters are loaded lazily upon the first request for a connection.</li>
 *   <li><b>Thread Safety:</b> Initialization block is wrapped in a <code>synchronized</code> static method to prevent multiple threads from loading properties or drivers concurrently.</li>
 *   <li><b>Classpath Resource Loading:</b> Uses the ClassLoader to dynamically locate <code>db.properties</code> in the classpath.</li>
 * </ul>
 *
 * <h3>Example db.properties file content:</h3>
 * <pre>
 * db.url=jdbc:mysql://localhost:3306/formsapp2?useSSL=false&serverTimezone=UTC
 * db.username=root
 * db.password=MySecurePassword123
 * db.driver=com.mysql.cj.jdbc.Driver
 * </pre>
 */
public class DBHelper {

    /** JDBC URL used to establish connection to the database instance. */
    private static String url;

    /** Database user credential. */
    private static String username;

    /** Database user password. */
    private static String password;

    /** JDBC Driver class name (defaulting to MySQL Connector/J). */
    private static String driver;

    /** Thread-safety initialization flag to check if db properties have been successfully loaded. */
    private static boolean initialized = false;

    /**
     * Lazily initializes database properties by loading them from the <code>db.properties</code> resource file.
     * <p>
     * Logic flow:
     * <ol>
     *   <li>Check the thread-safe flag <code>initialized</code>. If true, return immediately.</li>
     *   <li>Open an input stream to read <code>db.properties</code> using ClassLoader.</li>
     *   <li>Parse the Properties input stream to retrieve configuration attributes.</li>
     *   <li>Initialize class fields: URL, Username, Password, and Driver.</li>
     *   <li>Dynamically load the JDBC driver class via <code>Class.forName()</code>.</li>
     *   <li>Set the <code>initialized</code> flag to true.</li>
     * </ol>
     * </p>
     *
     * @throws RuntimeException if db.properties is missing or cannot be parsed, or if the JDBC driver class is missing.
     */
    private static synchronized void init() {
        // If already initialized, return to avoid redundant reading
        if (initialized) return;
        
        PropertyFileConnection testConnection = new  PropertyFileConnection();
		testConnection.readProperty();
        
        try {
            
            url      = "jdbc:mysql://localhost:3306/apps?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            username = testConnection.getDbUser();
            password = testConnection.getDbpassword();
            driver   = testConnection.getDriver();
            
            
           
//            url      = props.getProperty("url"+"/apps");
//            username = props.getProperty("dbuser");
//            password = props.getProperty("dbpassword");
//            driver   = props.getProperty("driver");
            
            // Register JDBC driver class dynamically in the current ClassLoader
            Class.forName(driver);
            initialized = true;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("DBHelper init failed: " + e.getMessage(), e);
        }
    }

    /**
     * Factory method that initializes parameters (if not done) and returns a new active JDBC connection to MySQL.
     * <p>
     * Usage Example:
     * <pre>
     * try (Connection con = DBHelper.getConnection()) {
     *     // Execute queries
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * </pre>
     * </p>
     *
     * @return Connection An active JDBC connection object.
     * @throws SQLException If a database access error occurs or connection credentials fail.
     */
    public static Connection getConnection() throws SQLException {
        init(); // Ensures properties are loaded
        return DriverManager.getConnection(url, username, password);
    }

    /**
     * Closes the database connection safely.
     * 
     * @param con Connection to close.
     */
    public static void closeConnection(Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException e) {
                // Ignore
            }
        }
    }
}
