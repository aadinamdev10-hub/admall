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
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h2>UploadServlet</h2>
 * <p>
 * This servlet handles multipart file uploads specifically for CSV datasets.
 * It processes the uploaded CSV bytes, parses them into key-value records (headers mapped to cell values),
 * registers/updates the dataset meta in the <code>datasets</code> table, seeds it with default layouts
 * (Form Layout, Report, Attendance Sheet, Admit Card configs), and batch-inserts candidate records
 * inside a single, safe transactional block.
 * </p>
 * 
 * <h3>API Endpoint</h3>
 * <p>POST /api/upload</p>
 * 
 * <h3>Request Parameters (Multipart Form-Data)</h3>
 * <ul>
 *   <li><b>file</b>: The CSV file raw input stream.</li>
 *   <li><b>datasetName</b>: The user-supplied custom name for the dataset.</li>
 * </ul>
 * 
 * <h3>Multipart Config Constraints</h3>
 * <ul>
 *   <li><code>fileSizeThreshold = 1MB</code>: Spools files exceeding 1MB to temporary disk storage to conserve server memory.</li>
 *   <li><code>maxFileSize = 10MB</code>: Rejects files larger than 10MB.</li>
 *   <li><code>maxRequestSize = 15MB</code>: Rejects entire multi-part requests exceeding 15MB.</li>
 * </ul>
 */
@WebServlet("/apps/admall/api/upload")
@MultipartConfig(
    fileSizeThreshold = 1024 * 1024,      // 1 MB
    maxFileSize       = 10 * 1024 * 1024, // 10 MB max per file
    maxRequestSize    = 15 * 1024 * 1024  // 15 MB max total request
)
public class UploadServlet extends HttpServlet {

    /**
     * Handles HTTP POST requests to upload and process the CSV dataset.
     * Runs database insertions inside a single transaction to ensure atomicity.
     *
     * @param req  The servlet request containing multipart form data.
     * @param resp The servlet response outputting standard success/error JSON.
     * @throws ServletException if a servlet-specific exception occurs.
     * @throws IOException      if an input/output exception occurs during file read/write.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();

        HttpSession session = req.getSession(false);
        if (session == null || !"superadmin".equalsIgnoreCase((String) session.getAttribute("role"))) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            out.write("{\"error\":\"Forbidden: Only Super Admin can upload datasets.\"}");
            return;
        }

        // ── 1. Extract and Validate Uploaded File Part ───────────────────────
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
            out.write("{\"error\":\"Uploaded file is empty.\"}");
            return;
        }

        // Extract clean file name and user-supplied dataset name
        String submittedName = filePart.getSubmittedFileName();
        String fileName = (submittedName != null && !submittedName.isBlank())
                ? Paths.get(submittedName).getFileName().toString()
                : "upload_" + System.currentTimeMillis() + ".csv";

        String datasetNameParam = req.getParameter("datasetName");
        String datasetName = (datasetNameParam != null && !datasetNameParam.isBlank())
                ? datasetNameParam.trim()
                : fileName;

        // ── 2. Read bytes and convert to UTF-8 String ────────────────────────
        byte[] rawBytes = filePart.getInputStream().readAllBytes();
        String csvContent = new String(rawBytes, StandardCharsets.UTF_8);

        // ── 3. Parse CSV rows via CSVHelper utility ──────────────────────────
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
            out.write("{\"error\":\"No valid data rows found in the CSV file. Make sure the file has a header row and data rows.\"}");
            return;
        }

        // Build header lists using first row keys (LinkedHashMap preserves CSV order)
        List<String> headerList = new ArrayList<>(rows.get(0).keySet());
        String headersJson = new Gson().toJson(headerList);

        Part formConfigPart = null;
        Part summaryConfigPart = null;
        Part attendanceConfigPart = null;
        Part admitConfigPart = null;
        Part studentListConfigPart = null;
        try {
            formConfigPart = req.getPart("formConfigFile");
        } catch (Exception ignored) {}
        try {
            summaryConfigPart = req.getPart("summaryConfigFile");
        } catch (Exception ignored) {}
        try {
            attendanceConfigPart = req.getPart("attendanceConfigFile");
        } catch (Exception ignored) {}
        try {
            admitConfigPart = req.getPart("admitConfigFile");
        } catch (Exception ignored) {}
        try {
            studentListConfigPart = req.getPart("studentListConfigFile");
        } catch (Exception ignored) {}

        String formConfigStr = readPartContent(formConfigPart);
        String summaryConfigStr = readPartContent(summaryConfigPart);
        String attendanceConfigStr = readPartContent(attendanceConfigPart);
        String admitConfigStr = readPartContent(admitConfigPart);
        String studentListConfigStr = readPartContent(studentListConfigPart);

        // ── 4. Persist to Database under a single Atomic Transaction ─────────
        Connection conn = null;
        try {
            conn = DBHelper.getConnection();
            conn.setAutoCommit(false); // BEGIN TRANSACTION

            // Check if a dataset with the same name already exists in datasets table
            Integer existingDatasetId = getDatasetIdByName(conn, datasetName);
            int datasetId;
            
            if (existingDatasetId != null) {
                // OVERWRITE STRATEGY: reuse the existing ID, clear old records, and update headers & configurations
                datasetId = existingDatasetId;
                deleteDatasetRecords(conn, datasetId);
                updateDatasetMeta(conn, datasetId, headersJson, formConfigStr, summaryConfigStr, attendanceConfigStr, admitConfigStr, studentListConfigStr);
            } else {
                // NEW DATASET STRATEGY: insert record and obtain generated primary key
                datasetId = insertDatasetMeta(conn, datasetName, headersJson, formConfigStr, summaryConfigStr, attendanceConfigStr, admitConfigStr, studentListConfigStr);
            }

            // Perform batch insert of candidate records
            int rowsInserted = batchInsertRecords(conn, datasetId, rows);

            conn.commit(); // COMMIT TRANSACTION - all inserts succeeded!

            // ── 5. Output JSON Success Response ──────────────────────────────
            out.write("{\"success\":true"
                    + ",\"datasetId\":"   + datasetId
                    + ",\"rowsInserted\":" + rowsInserted
                    + ",\"fileName\":\""   + escapeJson(fileName) + "\""
                    + "}");

        } catch (SQLException e) {
            // ROLLBACK TRANSACTION on database error to avoid partial uploads
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write("{\"error\":\"Database error: " + escapeJson(e.getMessage()) + "\"}");

        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true); // restore default autocommit setting
                } catch (SQLException ignored) {}
                DBHelper.closeConnection(conn);
            }
        }
    }

    // ── Database Operations Helpers ──────────────────────────────────────────

    /**
     * Reads a multipart Part file content to string.
     */
    private String readPartContent(Part part) throws IOException {
        if (part == null || part.getSize() == 0) return null;
        try (java.io.InputStream is = part.getInputStream()) {
            byte[] bytes = is.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8).trim();
        }
    }

    /**
     * Inserts new dataset metadata row with pre-seeded configurations.
     */
    private int insertDatasetMeta(Connection conn, String name, String headersJson,
                                  String formConfig, String summaryConfig, String attendanceConfig,
                                  String admitConfig, String studentListConfig)
            throws SQLException {
        String sql = "INSERT INTO datasets (name, headers, form_config, summary_config, attendance_config, admit_config, student_list_config) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, headersJson);
            ps.setString(3, formConfig != null && !formConfig.isEmpty() ? formConfig : "{}");
            ps.setString(4, summaryConfig != null && !summaryConfig.isEmpty() ? summaryConfig : getDefaultSummaryConfig());
            ps.setString(5, attendanceConfig != null && !attendanceConfig.isEmpty() ? attendanceConfig : getDefaultAttendanceConfig());
            ps.setString(6, admitConfig != null && !admitConfig.isEmpty() ? admitConfig : getDefaultAdmitConfig());
            ps.setString(7, studentListConfig != null && !studentListConfig.isEmpty() ? studentListConfig : getDefaultStudentListConfig());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new SQLException("Failed to retrieve generated dataset id after INSERT.");
            }
        }
    }

    /** Hardcoded default fallback Summary Config. */
    private String getDefaultSummaryConfig() {
        return "{\n" +
                "  \"reportName\": \"Department Wise Category Report\",\n" +
                "  \"rowField\": \"A2. Name of Department\",\n" +
                "  \"columnField\": \"B9. Category\",\n" +
                "  \"columns\": [],\n" +
                "  \"dynamicColumns\": true,\n" +
                "  \"showRowTotal\": true,\n" +
                "  \"showColumnTotal\": true\n" +
                "}";
    }

    /** Hardcoded default fallback Attendance Config. */
    private String getDefaultAttendanceConfig() {
        return "{\n" +
               "  \"candidateSummaryTable\": {\n" +
               "    \"columns\": [\n" +
               "      \"id\",\n" +
               "      \"name\",\n" +
               "      \"department\",\n" +
               "      \"photograph\",\n" +
               "      \"uploadedSignature\",\n" +
               "      \"signature\"\n" +
               "    ],\n" +
               "    \"source\": [\n" +
               "      \"candidateId\",\n" +
               "      \"B1\",\n" +
               "      \"A2\",\n" +
               "      \"S11\",\n" +
               "      \"S12\",\n" +
               "      \"\"\n" +
               "    ]\n" +
               "  }\n" +
               "}";
    }

    /** Hardcoded default fallback Admit Card Config. */
    private String getDefaultAdmitConfig() {
        return "{\n" +
               "  \"admitCard\": {\n" +
               "    \"title\": \"Admit Card for PhD. Entrance Examination for Category-2 at App Portal\\n(Session: July 2025)\",\n" +
               "    \"fields\": [\n" +
               "      { \"label\": \"Candidate Name\", \"source\": \"B1\" },\n" +
               "      { \"label\": \"Application ID\", \"source\": \"candidateId\" },\n" +
               "      { \"label\": \"Date of Birth (DD/MM/YYYY)\", \"source\": \"B3\" },\n" +
               "      { \"label\": \"Gender\", \"source\": \"B5\" },\n" +
               "      { \"label\": \"Caste Category\", \"source\": \"B9\" },\n" +
               "      { \"label\": \"Name of School\", \"source\": \"A1\" },\n" +
               "      { \"label\": \"Name of Department\", \"source\": \"A2\" },\n" +
               "      { \"label\": \"Subject\", \"source\": \"A4\" }\n" +
               "    ],\n" +
               "    \"photographSource\": \"S11\",\n" +
               "    \"signatureSource\": \"S12\",\n" +
               "    \"examDetails\": {\n" +
               "      \"date\": \"21-07-2025\",\n" +
               "      \"time\": \"10:30 AM-12:30 PM\",\n" +
               "      \"reportingTime\": \"9:30 AM\",\n" +
               "      \"gateClosureTime\": \"9:45 AM\",\n" +
               "      \"venue\": \"Application Portal, Main Campus, Academic Block A\"\n" +
               "    },\n" +
               "    \"instructions\": [\n" +
               "      \"Candidate has to fix recent colored passport size photograph over the admit card at the given appropriate place.\",\n" +
               "      \"The candidate needs to carry the Admit Card along with original photo identity document (ID) from below list, without which he/she will not be allowed to appear in the exam.<br><strong>Original photo identity document (ID)</strong><ul style=\\\"margin-top: 5px; padding-left: 20px;\\\"><li>Voter ID / Polling ID</li><li>Passport</li><li>Pan Card</li><li>Driving License</li><li>Central/State Government-issued photo ID cards for employees.</li><li>UID Aadhar Card.</li></ul>\",\n" +
               "      \"The candidates need to carry the hard copy of their Admit Card and Original Photo ID proof at the examination centre, without which the candidates will not be allowed to appear in the examination.\",\n" +
               "      \"The candidates should be present 60 minutes before the commencement of exam, as some formalities before appearing in the examination are needed. 15 minutes before the commencement of the examination, exam centre door will be closed and no candidates will be allowed to enter the examination centre.\",\n" +
               "      \"The question paper will have two sections A & B. All questions in both sections are compulsory.<ul style=\\\"margin-top: 5px; padding-left: 20px;\\\"><li><strong>Section A - Research Methodology:</strong> 50 Questions (50 Marks) \\u2013 Common for all subjects/schools</li><li><strong>Section B - Subject Specific Questions:</strong> 50 Questions (50 Marks)</li></ul>\",\n" +
               "      \"There will be no negative marking.\",\n" +
               "      \"For calculation purposes, rough sheets can be used, if required.\",\n" +
               "      \"Write answer keys at the appropriate place only by using a ball point pen.\",\n" +
               "      \"Items such as books, mobile phone, electronic diary, cell phones, writing pads, pencil boxes, beepers, and cameras etc. will not be allowed into the examination hall. Candidates will have to leave these items outside the examination hall at their own risk, if they bring so. If any item of the candidate is stolen or lost, the examination centre will not be responsible for that.\",\n" +
               "      \"During the exam, please follow the instructions given by the invigilator.\",\n" +
               "      \"Candidates will not be allowed to carry the rough sheets. During the exam, rough sheets will be provided to candidates at the examination centre. The candidates need to handover the used or unused rough sheets to the invigilator before leaving the exam centre.\",\n" +
               "      \"The admit card is to be submitted at the exam centre for further verification.\"\n" +
               "    ]\n" +
               "  }\n" +
               "}";
    }

    /** Hardcoded default fallback Student List Config. */
    private String getDefaultStudentListConfig() {
        return "{\n" +
               "  \"studentList\": {\n" +
               "    \"columns\": [\n" +
               "      { \"label\": \"Candidate ID\", \"source\": \"id\" },\n" +
               "      { \"label\": \"Name\", \"source\": \"B1\" },\n" +
               "      { \"label\": \"Email Address\", \"source\": \"Email Address\" },\n" +
               "      { \"label\": \"Department\", \"source\": \"A2\" }\n" +
               "    ]\n" +
               "  }\n" +
               "}";
    }

    /**
     * Checks if dataset exists and returns its ID.
     */
    private Integer getDatasetIdByName(Connection conn, String name) throws SQLException {
        String sql = "SELECT id FROM datasets WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        }
        return null;
    }

    /**
     * Clears all old candidate records for a dataset.
     */
    private void deleteDatasetRecords(Connection conn, int datasetId) throws SQLException {
        String sql = "DELETE FROM dataset_records WHERE dataset_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, datasetId);
            ps.executeUpdate();
        }
    }

    /**
     * Updates an existing dataset's headers and timestamp, and optionally its layouts if new configs are uploaded.
     */
    private void updateDatasetMeta(Connection conn, int datasetId, String headersJson,
                                   String formConfig, String summaryConfig, String attendanceConfig,
                                   String admitConfig, String studentListConfig)
            throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE datasets SET headers = ?, uploaded_at = CURRENT_TIMESTAMP");
        List<Object> params = new ArrayList<>();
        params.add(headersJson);

        if (formConfig != null && !formConfig.isEmpty()) {
            sql.append(", form_config = ?");
            params.add(formConfig);
        }
        if (summaryConfig != null && !summaryConfig.isEmpty()) {
            sql.append(", summary_config = ?");
            params.add(summaryConfig);
        }
        if (attendanceConfig != null && !attendanceConfig.isEmpty()) {
            sql.append(", attendance_config = ?");
            params.add(attendanceConfig);
        }
        if (admitConfig != null && !admitConfig.isEmpty()) {
            sql.append(", admit_config = ?");
            params.add(admitConfig);
        }
        if (studentListConfig != null && !studentListConfig.isEmpty()) {
            sql.append(", student_list_config = ?");
            params.add(studentListConfig);
        }

        sql.append(" WHERE id = ?");
        params.add(datasetId);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer) {
                    ps.setInt(i + 1, (Integer) p);
                } else {
                    ps.setString(i + 1, (String) p);
                }
            }
            ps.executeUpdate();
        }
    }

    /**
     * Performs fast batch inserts of parsed CSV candidate records.
     * Executes in batches of 500 to keep DB communication stable.
     */
    private int batchInsertRecords(Connection conn, int datasetId,
                                   List<Map<String, String>> rows)
            throws SQLException {
        String sql = "INSERT INTO dataset_records (dataset_id, record_json) VALUES (?, ?)";
        Gson gson = new Gson();
        int count = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, String> row : rows) {
                String json = gson.toJson(row);

                // Skip blank JSON elements defensively
                if (json == null || json.trim().equals("{}") || json.trim().isEmpty()) {
                    continue;
                }

                ps.setInt(1, datasetId);
                ps.setString(2, json);
                ps.addBatch();
                count++;

                // Trigger batch execution every 500 rows
                if (count % 500 == 0) {
                    ps.executeBatch();
                }
            }
            // Flush leftover rows
            ps.executeBatch();
        }
        return count;
    }

    /** Escapes strings safely for JSON encoding error responses. */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}