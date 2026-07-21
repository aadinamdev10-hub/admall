package apps.admall.util;

import java.sql.Connection;
import java.sql.SQLException;

public class DBHelper {
    public static Connection getConnection() throws SQLException {
        Connection con = apps.dbservice.DbConnection.getConnection();
        con.setCatalog("formsapp2");
        return con;
    }
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
