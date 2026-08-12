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
import java.util.HashSet;
import java.util.Set;

/**
 * Servlet handling user login via POST /api/login.
 * Validates credentials against app_users table and configures the session.
 */
@WebServlet("/api/login")
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
            String sql = "SELECT userid, username, role, link, description FROM app_users WHERE (userid = ? OR username = ?) AND password = ? AND status = 'active'";
            ps = conn.prepareStatement(sql);
            ps.setString(1, userid);
            ps.setString(2, userid);
            ps.setString(3, password);

            rs = ps.executeQuery();
            
            String dbUserid = null;
            String dbUsername = null;
            String dbRole = null;
            String firstLink = null;
            Set<String> allowedDepts = new HashSet<>();
            Set<Long> allowedDatasetIds = new HashSet<>();

            while (rs.next()) {
                if (dbUserid == null) {
                    dbUserid = rs.getString("userid");
                    dbUsername = rs.getString("username");
                    dbRole = rs.getString("role") != null ? rs.getString("role").trim() : "";
                }
                String linkVal = rs.getString("link");
                if (linkVal != null) {
                    if (firstLink == null) {
                        firstLink = linkVal;
                    }
                    if (linkVal.contains("datasetId=")) {
                        String[] parts = linkVal.split("datasetId=");
                        if (parts.length > 1) {
                            try {
                                long dsId = Long.parseLong(parts[1].split("&")[0]);
                                allowedDatasetIds.add(dsId);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    if (linkVal.contains("department=")) {
                        String[] parts = linkVal.split("department=");
                        if (parts.length > 1) {
                            try {
                                String dept = java.net.URLDecoder.decode(parts[1].split("&")[0], "UTF-8");
                                allowedDepts.add(dept.trim());
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (dbUserid != null) {
                // Establish Session
                HttpSession session = req.getSession(true);
                session.setAttribute("userid", dbUserid);
                session.setAttribute("username", dbUsername);
                session.setAttribute("role", dbRole);
                session.setMaxInactiveInterval(3600); // 1 hour timeout

                // Fallback to username if allowedDepts is empty
                if (allowedDepts.isEmpty()) {
                    allowedDepts.add(dbUsername.trim());
                }

                session.setAttribute("allowedDepts", allowedDepts);
                session.setAttribute("allowedDatasetIds", allowedDatasetIds);
                session.setAttribute("link", firstLink);

                JsonObject successResp = new JsonObject();
                successResp.addProperty("success", true);
                successResp.addProperty("username", dbUsername);
                successResp.addProperty("role", dbRole);
                successResp.addProperty("link", firstLink);

                resp.getWriter().write(gson.toJson(successResp));
            } else {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.getWriter().write("{\"success\":false,\"message\":\"Invalid credentials or inactive account.\"}");
            }
        } catch (SQLException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("{\"success\":false,\"message\":\"Database error: " + e.getMessage() + "\"}");
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(conn);
        }
    }
}
