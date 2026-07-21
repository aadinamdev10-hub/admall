package apps.admall.servlet;

import apps.admall.util.DBHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Servlet handling user login via POST /api/login.
 * Validates credentials against app_users table and configures the session.
 */
@WebServlet("/apps/admall/api/login")
public class LoginServlet extends HttpServlet {

    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        StringBuilder sb = new StringBuilder();
        String line;
        try (BufferedReader reader = req.getReader()) {
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        String userid = "";
        String password = "";

        try {
            JsonObject jsonObject = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (jsonObject.has("userid")) {
                userid = jsonObject.get("userid").getAsString().trim();
            }
            if (jsonObject.has("password")) {
                password = jsonObject.get("password").getAsString();
            }
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"success\":false,\"message\":\"Malformed JSON payload.\"}");
            return;
        }

        if (userid.isEmpty() || password.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"success\":false,\"message\":\"userid and password are required.\"}");
            return;
        }

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = DBHelper.getConnection();
            String sql = "SELECT userid, username, role, link FROM app_users WHERE userid = ? AND password = ? AND status = 'active'";
            ps = conn.prepareStatement(sql);
            ps.setString(1, userid);
            ps.setString(2, password);

            rs = ps.executeQuery();
            if (rs.next()) {
                String dbUserid = rs.getString("userid");
                String dbUsername = rs.getString("username");
                String dbRole = rs.getString("role") != null ? rs.getString("role").trim() : "";
                String dbLink = rs.getString("link");

                // Establish Session
                HttpSession session = req.getSession(true);
                session.setAttribute("userid", dbUserid);
                session.setAttribute("username", dbUsername);
                session.setAttribute("role", dbRole);
                session.setAttribute("link", dbLink);
                session.setMaxInactiveInterval(3600); // 1 hour timeout

                JsonObject successResp = new JsonObject();
                successResp.addProperty("success", true);
                successResp.addProperty("username", dbUsername);
                successResp.addProperty("role", dbRole);
                successResp.addProperty("link", dbLink);

                resp.getWriter().write(gson.toJson(successResp));
            } else {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.getWriter().write("{\"success\":false,\"message\":\"Invalid credentials or inactive account.\"}");
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
