package apps.admall.servlet;

import apps.admall.util.CSVHelper;
import apps.admall.util.DBHelper;
import com.google.gson.Gson;

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
@WebServlet("/apps/admall/api/updateCsv")
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

            // Delete old records
            deleteDatasetRecords(conn, datasetId);

            // Update headers and timestamp only (configs untouched)
            updateHeadersOnly(conn, datasetId, headersJson);

            // Batch insert new records
            int rowsInserted = batchInsertRecords(conn, datasetId, rows);

            conn.commit();

            out.write("{\"success\":true"
                    + ",\"datasetId\":" + datasetId
                    + ",\"rowsInserted\":" + rowsInserted
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

    private void deleteDatasetRecords(Connection conn, int datasetId) throws SQLException {
        String sql = "DELETE FROM dataset_records WHERE dataset_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, datasetId);
            ps.executeUpdate();
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

    private int batchInsertRecords(Connection conn, int datasetId, List<Map<String, String>> rows)
            throws SQLException {
        String sql = "INSERT INTO dataset_records (dataset_id, record_json) VALUES (?, ?)";
        Gson gson = new Gson();
        int count = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, String> row : rows) {
                String json = gson.toJson(row);
                if (json == null || json.trim().equals("{}") || json.trim().isEmpty()) {
                    continue;
                }
                ps.setInt(1, datasetId);
                ps.setString(2, json);
                ps.addBatch();
                count++;

                if (count % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        return count;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
