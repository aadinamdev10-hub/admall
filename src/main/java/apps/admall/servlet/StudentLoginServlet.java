package apps.admall.servlet;

import apps.admall.util.DBHelper;
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
@WebServlet("/apps/admall/api/student-login")
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

        String enrollmentNumber = "";
        String email = "";

        try {
            JsonObject jsonObject = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (jsonObject.has("enrollmentNumber")) {
                enrollmentNumber = jsonObject.get("enrollmentNumber").getAsString().trim();
            }
            if (jsonObject.has("email")) {
                email = jsonObject.get("email").getAsString().trim();
            }
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"success\":false,\"message\":\"Malformed JSON payload.\"}");
            return;
        }

        if (enrollmentNumber.isEmpty() || email.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"success\":false,\"message\":\"Enrollment Number and Email Address are required.\"}");
            return;
        }

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = DBHelper.getConnection();
            String sql = "SELECT id, dataset_id, record_json FROM dataset_records";
            ps = conn.prepareStatement(sql);
            rs = ps.executeQuery();

            boolean found = false;
            long matchedRecordId = -1;
            long matchedDatasetId = -1;

            while (rs.next()) {
                long id = rs.getLong("id");
                long datasetId = rs.getLong("dataset_id");
                String recordJsonStr = rs.getString("record_json");

                try {
                    JsonObject record = JsonParser.parseString(recordJsonStr).getAsJsonObject();
                    if (record.has("enrollment_number") && record.has("Email Address")) {
                        String dbEnrollment = record.get("enrollment_number").getAsString().trim();
                        String dbEmail = record.get("Email Address").getAsString().trim();

                        if (dbEnrollment.equals(enrollmentNumber) && dbEmail.equalsIgnoreCase(email)) {
                            found = true;
                            matchedRecordId = id;
                            matchedDatasetId = datasetId;
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed records
                }
            }

            if (found) {
                JsonObject successResp = new JsonObject();
                successResp.addProperty("success", true);
                successResp.addProperty("recordId", matchedRecordId);
                successResp.addProperty("datasetId", matchedDatasetId);
                try {
                    String token = apps.admall.util.CryptoHelper.encrypt(matchedRecordId + ":" + matchedDatasetId);
                    successResp.addProperty("token", token);
                } catch (Exception e) {
                    // Fallback
                }

                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(gson.toJson(successResp));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write("{\"success\":false,\"message\":\"No record found.\"}");
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
