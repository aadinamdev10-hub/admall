package apps.admall.servlet;

import apps.admall.util.DBHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Servlet providing detailed department user info via GET /api/department-info.
 * Restricted to authenticated users with the "department" role.
 */
@WebServlet("/apps/admall/api/department-info")
public class DepartmentInfoServlet extends HttpServlet {

    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("userid") == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write("{\"success\":false,\"message\":\"Unauthorized. Please login.\"}");
            return;
        }

        String sessionUserid = (String) session.getAttribute("userid");
        String sessionRole = (String) session.getAttribute("role");

        if (!"department".equalsIgnoreCase(sessionRole)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.getWriter().write("{\"success\":false,\"message\":\"Forbidden. Department access only.\"}");
            return;
        }

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = DBHelper.getConnection();
            String sql = "SELECT userid, username, description, link FROM app_users WHERE userid = ? AND role = 'department' AND status = 'active'";
            ps = conn.prepareStatement(sql);
            ps.setString(1, sessionUserid);

            rs = ps.executeQuery();
            if (rs.next()) {
                JsonObject info = new JsonObject();
                info.addProperty("userid", rs.getString("userid"));
                info.addProperty("username", rs.getString("username"));
                info.addProperty("description", rs.getString("description"));
                info.addProperty("link", rs.getString("link"));

                resp.getWriter().write(gson.toJson(info));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write("{\"success\":false,\"message\":\"Department user not found or inactive.\"}");
            }
        } catch (SQLException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("{\"success\":false,\"message\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(conn);
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
