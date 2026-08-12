package apps.admall.servlet;

import apps.admall.util.DBHelper;
import apps.admall.util.CryptoHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Servlet handling student login via POST /api/student-login.
 * Validates Enrollment Number and Email Address by querying all records.
 */
@WebServlet("/api/student-login")
public class StudentLoginServlet extends HttpServlet {

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

        String candidateIdStr = "";
        String email = "";

        try {
            JsonObject jsonObject = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (jsonObject.has("candidateId")) {
                candidateIdStr = jsonObject.get("candidateId").getAsString().trim();
            }
            if (jsonObject.has("email")) {
                email = jsonObject.get("email").getAsString().trim();
            }
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"success\":false,\"message\":\"Malformed JSON payload.\"}");
            return;
        }

        if (candidateIdStr.isEmpty() || email.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"success\":false,\"message\":\"Candidate ID and Email Address are required.\"}");
            return;
        }

        long candidateId = -1;
        try {
            String rawId = candidateIdStr;
            if (rawId.startsWith("#")) {
                rawId = rawId.substring(1);
            }
            candidateId = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"success\":false,\"message\":\"Invalid Candidate ID format.\"}");
            return;
        }

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = DBHelper.getConnection();
            String sql = "SELECT id, dataset_id, record_json FROM dataset_records WHERE id = ?";
            ps = conn.prepareStatement(sql);
            ps.setLong(1, candidateId);
            rs = ps.executeQuery();

            boolean found = false;
            long matchedRecordId = -1;
            long matchedDatasetId = -1;

            if (rs.next()) {
                long id = rs.getLong("id");
                long datasetId = rs.getLong("dataset_id");
                String recordJsonStr = rs.getString("record_json");

                try {
                    JsonObject record = JsonParser.parseString(recordJsonStr).getAsJsonObject();
                    String dbEmail = "";
                    for (String key : record.keySet()) {
                        if (key.toLowerCase().contains("email")) {
                            dbEmail = record.get(key).getAsString().trim();
                            break;
                        }
                    }

                    if (!dbEmail.isEmpty() && dbEmail.equalsIgnoreCase(email)) {
                        found = true;
                        matchedRecordId = id;
                        matchedDatasetId = datasetId;
                    }
                } catch (Exception e) {
                    // Skip malformed records
                }
            }

            if (found) {
                JsonObject successResp = new JsonObject();
                successResp.addProperty("success", true);
                // Encrypt output parameters using CryptoHelper
                successResp.addProperty("recordId", CryptoHelper.encrypt(String.valueOf(matchedRecordId)));
                successResp.addProperty("datasetId", CryptoHelper.encrypt(String.valueOf(matchedDatasetId)));

                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(gson.toJson(successResp));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write("{\"success\":false,\"message\":\"No record found. Please check your Candidate ID and Email Address.\"}");
            }

        } catch (SQLException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject err = new JsonObject();
            err.addProperty("success", false);
            err.addProperty("message", "Database error: " + e.getMessage());
            resp.getWriter().write(gson.toJson(err));
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
