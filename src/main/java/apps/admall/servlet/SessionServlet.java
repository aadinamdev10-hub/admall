package apps.admall.servlet;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import apps.admall.util.DBHelper;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Servlet providing session status info via GET /api/session.
 * Queries the app_users table directly to fetch all authorized department links.
 */
@WebServlet("/api/session")
public class SessionServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        HttpSession session = req.getSession(false);
        JsonObject respJson = new JsonObject();

        if (session != null && session.getAttribute("userid") != null) {
            String userid = (String) session.getAttribute("userid");
            respJson.addProperty("loggedIn", true);
            respJson.addProperty("userid", userid);
            respJson.addProperty("username", (String) session.getAttribute("username"));
            respJson.addProperty("role", (String) session.getAttribute("role"));

            JsonArray linksArray = new JsonArray();
            String firstLink = null;

            Connection conn = null;
            PreparedStatement ps = null;
            ResultSet rs = null;

            try {
                conn = DBHelper.getConnection();
                String sql = "SELECT link FROM app_users WHERE userid = ?";
                ps = conn.prepareStatement(sql);
                ps.setString(1, userid);
                rs = ps.executeQuery();
                while (rs.next()) {
                    String link = rs.getString("link");
                    if (link != null) {
                        linksArray.add(link);
                        if (firstLink == null) {
                            firstLink = link;
                        }
                    }
                }
            } catch (SQLException e) {
                System.err.println("SessionServlet db query failed: " + e.getMessage());
            } finally {
                if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
                if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
                DBHelper.closeConnection(conn);
            }

            // Fallback to session attribute if app_users is empty
            if (firstLink == null) {
                firstLink = (String) session.getAttribute("link");
                if (firstLink != null) {
                    linksArray.add(firstLink);
                }
            }

            respJson.add("links", linksArray);
            respJson.addProperty("link", firstLink);
        } else {
            respJson.addProperty("loggedIn", false);
        }

        resp.getWriter().write(gson.toJson(respJson));
    }
}
