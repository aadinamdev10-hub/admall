package apps.admall.servlet;

import apps.admall.util.CSVHelper;
import apps.admall.util.DBHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import java.util.HashMap;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h2>UpdateCsvServlet</h2>
 * <p>
 * Handles CSV-only updates for an existing dataset.
 * Clears old candidate records and re-imports from the new CSV file.
 * All JSON configurations (Form Layout, Report, Attendance, Admit Card, Student List) remain untouched.
 * </p>
 *
 * <h3>API Endpoint</h3>
 * <p>POST /api/updateCsv</p>
 *
 * <h3>Request Parameters (Multipart Form-Data)</h3>
 * <ul>
 *   <li><b>datasetId</b>: The ID of the existing dataset to update.</li>
 *   <li><b>file</b>: The new CSV file.</li>
 * </ul>
 */
@WebServlet("/api/updateCsv")
@MultipartConfig(
    fileSizeThreshold = 1024 * 1024,      // 1 MB
    maxFileSize       = 10 * 1024 * 1024, // 10 MB max per file
    maxRequestSize    = 12 * 1024 * 1024  // 12 MB max total request
)
public class UpdateCsvServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();

        HttpSession session = req.getSession(false);
        if (session == null || !"superadmin".equalsIgnoreCase((String) session.getAttribute("role"))) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            out.write("{\"error\":\"Forbidden: Only Super Admin can update CSV.\"}");
            return;
        }

        // ── 1. Validate dataset ID ──────────────────────────────────────
        String datasetIdParam = req.getParameter("datasetId");
        if (datasetIdParam == null || datasetIdParam.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write("{\"error\":\"Missing required parameter: datasetId\"}");
            return;
        }

        int datasetId;
        try {
            datasetId = Integer.parseInt(datasetIdParam.trim());
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write("{\"error\":\"Invalid datasetId: must be a number.\"}");
            return;
        }

        // ── 2. Extract and validate CSV file ────────────────────────────
        Part filePart;
        try {
            filePart = req.getPart("file");
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write("{\"error\":\"No file part found in request. Use multipart field name 'file'.\"}");
            return;
        }

        if (filePart == null || filePart.getSize() == 0) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write("{\"error\":\"Uploaded CSV file is empty.\"}");
            return;
        }

        // ── 3. Read and parse CSV ───────────────────────────────────────
        byte[] rawBytes = filePart.getInputStream().readAllBytes();
        String csvContent = new String(rawBytes, StandardCharsets.UTF_8);

        List<Map<String, String>> rows;
        try {
            rows = CSVHelper.parseString(csvContent);
        } catch (IOException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write("{\"error\":\"Failed to parse CSV: " + escapeJson(e.getMessage()) + "\"}");
            return;
        }

        if (rows.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write("{\"error\":\"No valid data rows found in the CSV file.\"}");
            return;
        }

        // Build headers from first row
        List<String> headerList = new ArrayList<>(rows.get(0).keySet());
        String headersJson = new Gson().toJson(headerList);

        // ── 4. Update database: delete old records, insert new, update headers ─
        Connection conn = null;
        try {
            conn = DBHelper.getConnection();
            conn.setAutoCommit(false);

            // Verify dataset exists
            if (!datasetExists(conn, datasetId)) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write("{\"error\":\"Dataset with ID " + datasetId + " not found.\"}");
                return;
            }

            // Retrieve all existing records to map unique identifier (e.g. Email/App ID) to record ID
            Map<String, Long> existingMap = new HashMap<>();
            String selectSql = "SELECT id, record_json FROM dataset_records WHERE dataset_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setInt(1, datasetId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String jsonStr = rs.getString("record_json");
                        try {
                            JsonObject record = JsonParser.parseString(jsonStr).getAsJsonObject();
                            String uniqueKey = extractUniqueKey(record);
                            if (uniqueKey != null && !uniqueKey.isEmpty()) {
                                existingMap.put(uniqueKey.toLowerCase(), id);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Update headers and timestamp only (configs untouched)
            updateHeadersOnly(conn, datasetId, headersJson);

            // Batch insert/update records incrementally
            int[] counts = batchUpsertRecords(conn, datasetId, rows, existingMap);
            int rowsInserted = counts[0];
            int rowsUpdated = counts[1];

            conn.commit();

            out.write("{\"success\":true"
                    + ",\"datasetId\":" + datasetId
                    + ",\"rowsInserted\":" + rowsInserted
                    + ",\"rowsUpdated\":" + rowsUpdated
                    + "}");

        } catch (SQLException e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write("{\"error\":\"Database error: " + escapeJson(e.getMessage()) + "\"}");

        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                DBHelper.closeConnection(conn);
            }
        }
    }

    // ── Helper Methods ──────────────────────────────────────────────────

    private boolean datasetExists(Connection conn, int datasetId) throws SQLException {
        String sql = "SELECT id FROM datasets WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, datasetId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void updateHeadersOnly(Connection conn, int datasetId, String headersJson) throws SQLException {
        String sql = "UPDATE datasets SET headers = ?, uploaded_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, headersJson);
            ps.setInt(2, datasetId);
            ps.executeUpdate();
        }
    }

    private String extractUniqueKey(JsonObject record) {
        for (String key : record.keySet()) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("email") && !lowerKey.contains("alternate")) {
                JsonElement val = record.get(key);
                if (val != null && !val.isJsonNull()) {
                    return val.getAsString().trim();
                }
            }
        }
        for (String key : record.keySet()) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("application") || lowerKey.contains("candidate id") || lowerKey.contains("enrollment")) {
                JsonElement val = record.get(key);
                if (val != null && !val.isJsonNull()) {
                    return val.getAsString().trim();
                }
            }
        }
        return null;
    }

    private String extractUniqueKeyFromCsv(Map<String, String> row) {
        for (String key : row.keySet()) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("email") && !lowerKey.contains("alternate")) {
                String val = row.get(key);
                if (val != null) {
                    return val.trim();
                }
            }
        }
        for (String key : row.keySet()) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("application") || lowerKey.contains("candidate id") || lowerKey.contains("enrollment")) {
                String val = row.get(key);
                if (val != null) {
                    return val.trim();
                }
            }
        }
        return null;
    }

    private int[] batchUpsertRecords(Connection conn, int datasetId, List<Map<String, String>> rows, Map<String, Long> existingMap)
            throws SQLException {
        String insertSql = "INSERT INTO dataset_records (dataset_id, record_json) VALUES (?, ?)";
        String updateSql = "UPDATE dataset_records SET record_json = ? WHERE id = ?";
        Gson gson = new Gson();
        int rowsInserted = 0;
        int rowsUpdated = 0;

        try (PreparedStatement insertPs = conn.prepareStatement(insertSql);
             PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
            int batchCount = 0;
            for (Map<String, String> row : rows) {
                String json = gson.toJson(row);
                if (json == null || json.trim().equals("{}") || json.trim().isEmpty()) {
                    continue;
                }

                String uniqueKey = extractUniqueKeyFromCsv(row);
                Long existingId = (uniqueKey != null) ? existingMap.get(uniqueKey.toLowerCase()) : null;

                if (existingId != null) {
                    updatePs.setString(1, json);
                    updatePs.setLong(2, existingId);
                    updatePs.addBatch();
                    rowsUpdated++;
                } else {
                    insertPs.setInt(1, datasetId);
                    insertPs.setString(2, json);
                    insertPs.addBatch();
                    rowsInserted++;
                }
                batchCount++;

                if (batchCount % 500 == 0) {
                    insertPs.executeBatch();
                    updatePs.executeBatch();
                }
            }
            insertPs.executeBatch();
            updatePs.executeBatch();
        }
        return new int[]{rowsInserted, rowsUpdated};
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
