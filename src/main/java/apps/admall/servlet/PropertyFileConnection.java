package apps.admall.servlet;

import java.io.InputStream;
import java.util.Properties;

/**
 * Helper class that resolves the missing PropertyFileConnection class.
 * It reads credentials from db.properties on the classpath and provides getters.
 */
public class PropertyFileConnection {
    private String dbUser;
    private String dbpassword;
    private String driver;

    public void readProperty() {
        try (InputStream is = PropertyFileConnection.class.getClassLoader().getResourceAsStream("db.properties")) {
            Properties props = new Properties();
            if (is != null) {
                props.load(is);
                this.dbUser = props.getProperty("db.username", "root");
                this.dbpassword = props.getProperty("db.password", "#Akshat54321");
                this.driver = props.getProperty("db.driver", "com.mysql.cj.jdbc.Driver");
            } else {
                // Fallback defaults
                this.dbUser = "root";
                this.dbpassword = "#Akshat54321";
                this.driver = "com.mysql.cj.jdbc.Driver";
            }
        } catch (Exception e) {
            e.printStackTrace();
            this.dbUser = "root";
            this.dbpassword = "#Akshat54321";
            this.driver = "com.mysql.cj.jdbc.Driver";
        }
    }

    public String getDbUser() {
        return dbUser;
    }

    public String getDbpassword() {
        return dbpassword;
    }

    public String getDriver() {
        return driver;
    }
}
