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
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>DatasetServlet</h2>
 * <p>
 * This servlet handles administrative queries and mutations related to dataset definitions
 * and their respective schema/UI configuration models (Form Layout, Report, Attendance, Admit Card).
 * </p>
 * 
 * <h3>Endpoints Supported</h3>
 * <ul>
 *   <li><b>GET /api/datasets?action=list:</b> Lists all active datasets with metadata and candidate counts.</li>
 *   <li><b>GET /api/datasets?action=getFormConfig&datasetId=X:</b> Returns Form layout JSON config.</li>
 *   <li><b>GET /api/datasets?action=getSummaryConfig&datasetId=X:</b> Returns Report pivot JSON config.</li>
 *   <li><b>GET /api/datasets?action=getAttendanceConfig&datasetId=X:</b> Returns Attendance Sheet JSON config.</li>
 *   <li><b>GET /api/datasets?action=getAdmitConfig&datasetId=X:</b> Returns Admit Card layout JSON config.</li>
 *   <li><b>POST /api/datasets?action=uploadFormConfig&datasetId=X:</b> Saves/updates the Form layout configuration.</li>
 *   <li><b>POST /api/datasets?action=uploadSummaryConfig&datasetId=X:</b> Saves/updates the Report pivot configuration.</li>
 *   <li><b>POST /api/datasets?action=uploadAttendanceConfig&datasetId=X:</b> Saves/updates the Attendance Sheet configuration.</li>
 *   <li><b>POST /api/datasets?action=uploadAdmitConfig&datasetId=X:</b> Saves/updates the Admit Card layout configuration.</li>
 *   <li><b>POST /api/datasets?action=delete&id=X:</b> Deletes the dataset definition and cascaded records.</li>
 * </ul>
 * 
 * <h3>Design Patterns</h3>
 * <ul>
 *   <li><b>Command Dispatcher:</b> Custom action dispatcher in <code>doGet</code> and <code>doPost</code> delegates execution logic.</li>
 *   <li><b>JSON Validation:</b> Parsers validate JSON payloads to prevent database corruption.</li>
 * </ul>
 */
@WebServlet("/apps/admall/api/datasets")
public class DatasetServlet extends HttpServlet {

    /** GSON helper instance used for compiling and formatting JSON responses. */
    private final Gson gson = new Gson();

    /**
     * Dispatcher method for HTTP GET operations.
     * Maps query parameters to individual configuration readers.
     * 
     * @param req  The HTTP request containing action parameters.
     * @param resp The HTTP response writing back configurations or listings.
     * @throws ServletException if a servlet-specific error occurs.
     * @throws IOException      if transmission fails.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String action = req.getParameter("action");

        // Action dispatcher routing logic
        if ("list".equalsIgnoreCase(action)) {
            handleList(resp);
        } else if ("getFormConfig".equalsIgnoreCase(action)) {
            handleGetFormConfig(req, resp);
        } else if ("getSummaryConfig".equalsIgnoreCase(action)) {
            handleGetSummaryConfig(req, resp);
        } else if ("getAttendanceConfig".equalsIgnoreCase(action)) {
            handleGetAttendanceConfig(req, resp);
        } else if ("getAdmitConfig".equalsIgnoreCase(action)) {
            handleGetAdmitConfig(req, resp);
        } else if ("getStudentListConfig".equalsIgnoreCase(action)) {
            handleGetStudentListConfig(req, resp);
        } else {
            resp.setStatus(400); // Bad Request
            resp.getWriter().write("{\"error\":\"Invalid or missing action parameter. Use action=list, getFormConfig, getSummaryConfig, getAttendanceConfig, getAdmitConfig or getStudentListConfig\"}");
        }
    }

    /**
     * Dispatcher method for HTTP POST operations.
     * Handles layout/report config saves and deletion operations.
     * 
     * @param req  The HTTP request containing dataset parameters and request payloads.
     * @param resp The HTTP response writing status responses.
     * @throws ServletException if a servlet-specific error occurs.
     * @throws IOException      if transmission fails.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        
        HttpSession session = req.getSession(false);
        if (session == null || !"superadmin".equalsIgnoreCase((String) session.getAttribute("role"))) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.getWriter().write("{\"success\":false,\"error\":\"Forbidden: Only Super Admin can mutate dataset configurations.\"}");
            return;
        }

        String action = req.getParameter("action");

        // Action dispatcher routing logic for mutations
        if ("uploadFormConfig".equalsIgnoreCase(action)) {
            handleUploadFormConfig(req, resp);
        } else if ("uploadSummaryConfig".equalsIgnoreCase(action)) {
            handleUploadSummaryConfig(req, resp);
        } else if ("uploadAttendanceConfig".equalsIgnoreCase(action)) {
            handleUploadAttendanceConfig(req, resp);
        } else if ("uploadAdmitConfig".equalsIgnoreCase(action)) {
            handleUploadAdmitConfig(req, resp);
        } else if ("uploadStudentListConfig".equalsIgnoreCase(action)) {
            handleUploadStudentListConfig(req, resp);
        } else if ("delete".equalsIgnoreCase(action)) {
            handleDelete(req, resp);
        } else {
            resp.setStatus(400); // Bad Request
            resp.getWriter().write("{\"error\":\"Invalid or missing action parameter. Use action=uploadFormConfig, uploadSummaryConfig, uploadAttendanceConfig, uploadAdmitConfig, uploadStudentListConfig or delete\"}");
        }
    }

    /**
     * Retrieves all datasets registered in the system along with count of rows uploaded.
     * <p>
     * <b>Query logic:</b>
     * Uses a left join query to aggregate the row count from <code>dataset_records</code> grouped by the dataset ID.
     * </p>
     * 
     * @param resp The HTTP response object.
     * @throws IOException if database or writer streams fail.
     */
    private void handleList(HttpServletResponse resp) throws IOException {
        String sql = "SELECT d.id, d.name, d.headers, d.form_config, d.uploaded_at, COUNT(r.id) AS row_count " +
                     "FROM datasets d LEFT JOIN dataset_records r ON d.id = r.dataset_id " +
                     "GROUP BY d.id ORDER BY d.uploaded_at DESC";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            rs = ps.executeQuery();

            List<Map<String, Object>> datasets = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> ds = new HashMap<>();
                ds.put("id", rs.getLong("id"));
                ds.put("name", rs.getString("name"));
                ds.put("headers", rs.getString("headers"));
                ds.put("formConfig", rs.getString("form_config"));
                ds.put("uploadedAt", rs.getTimestamp("uploaded_at").toString());
                ds.put("rowCount", rs.getInt("row_count"));
                datasets.add(ds);
            }
            resp.getWriter().write(gson.toJson(datasets));
        } catch (SQLException e) {
            resp.setStatus(500); // Internal Server Error
            resp.getWriter().write("{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Reads and prints the Form Config JSON string stored in the datasets record database.
     * 
     * @param req  HTTP request container.
     * @param resp HTTP response container.
     * @throws IOException if IO streams fail.
     */
    private void handleGetFormConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"datasetId parameter is required\"}");
            return;
        }

        String sql = "SELECT form_config FROM datasets WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setLong(1, Long.parseLong(datasetIdStr));
            rs = ps.executeQuery();
            if (rs.next()) {
                String formConfig = rs.getString("form_config");
                resp.getWriter().write(formConfig != null ? formConfig : "{}");
            } else {
                resp.setStatus(404); // Not Found
                resp.getWriter().write("{\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Updates/saves the custom Form Config configuration.
     * Checks if the incoming request body has a valid JSON format before updating.
     * 
     * @param req  HTTP request container.
     * @param resp HTTP response container.
     * @throws IOException if stream reader fails.
     */
    private void handleUploadFormConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"datasetId parameter is required\"}");
            return;
        }

        // Read request body content
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        String body = sb.toString().trim();

        if (body.isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Request body is empty\"}");
            return;
        }

        // Validate incoming string is syntax-compliant JSON
        try {
            JsonParser.parseString(body);
        } catch (Exception e) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Invalid JSON format for form configuration\"}");
            return;
        }

        // Perform MySQL Update
        String sql = "UPDATE datasets SET form_config = ? WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setString(1, body);
            ps.setLong(2, Long.parseLong(datasetIdStr));
            int rowsUpdated = ps.executeUpdate();
            if (rowsUpdated > 0) {
                resp.getWriter().write("{\"success\":true,\"message\":\"Form configuration updated successfully\"}");
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"success\":false,\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"success\":false,\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Cascade deletes a dataset definition from the datasets catalog.
     * The MySQL schema automatically deletes associated candidate rows via FOREIGN KEY cascade.
     * 
     * @param req  HTTP request container.
     * @param resp HTTP response container.
     * @throws IOException if writer fails.
     */
    private void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("id");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"id parameter is required\"}");
            return;
        }

        String sql = "DELETE FROM datasets WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setLong(1, Long.parseLong(datasetIdStr));
            int rowsDeleted = ps.executeUpdate();
            if (rowsDeleted > 0) {
                resp.getWriter().write("{\"success\":true,\"message\":\"Dataset deleted successfully\"}");
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"success\":false,\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"success\":false,\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Reads and returns the summary config JSON used to render the reporting pivot table.
     * 
     * @param req  HTTP request container.
     * @param resp HTTP response container.
     * @throws IOException if database fails.
     */
    private void handleGetSummaryConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"datasetId parameter is required\"}");
            return;
        }

        String sql = "SELECT summary_config FROM datasets WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setLong(1, Long.parseLong(datasetIdStr));
            rs = ps.executeQuery();
            if (rs.next()) {
                String summaryConfig = rs.getString("summary_config");
                resp.getWriter().write(summaryConfig != null ? summaryConfig : "{}");
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Saves/updates the custom summary configuration model.
     * 
     * @param req  HTTP request container.
     * @param resp HTTP response container.
     * @throws IOException if reader fails.
     */
    private void handleUploadSummaryConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"datasetId parameter is required\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        String body = sb.toString().trim();

        if (body.isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Request body is empty\"}");
            return;
        }

        // Validate JSON
        try {
            JsonParser.parseString(body);
        } catch (Exception e) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Invalid JSON format for summary configuration\"}");
            return;
        }

        String sql = "UPDATE datasets SET summary_config = ? WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setString(1, body);
            ps.setLong(2, Long.parseLong(datasetIdStr));
            int rowsUpdated = ps.executeUpdate();
            if (rowsUpdated > 0) {
                resp.getWriter().write("{\"success\":true,\"message\":\"Summary configuration updated successfully\"}");
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"success\":false,\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"success\":false,\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Reads and prints the Attendance Config JSON used to format print-ready attendance layout sheets.
     * 
     * @param req  HTTP request container.
     * @param resp HTTP response container.
     * @throws IOException if database fails.
     */
    private void handleGetAttendanceConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"datasetId parameter is required\"}");
            return;
        }

        String sql = "SELECT attendance_config FROM datasets WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setLong(1, Long.parseLong(datasetIdStr));
            rs = ps.executeQuery();
            if (rs.next()) {
                String attendanceConfig = rs.getString("attendance_config");
                resp.getWriter().write(attendanceConfig != null ? attendanceConfig : getDefaultAttendanceConfig());
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Saves/updates the custom attendance layout configuration template.
     * 
     * @param req  HTTP request container.
     * @param resp HTTP response container.
     * @throws IOException if reader fails.
     */
    private void handleUploadAttendanceConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"datasetId parameter is required\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        String body = sb.toString().trim();

        if (body.isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Request body is empty\"}");
            return;
        }

        // Validate JSON
        try {
            JsonParser.parseString(body);
        } catch (Exception e) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Invalid JSON format for attendance configuration\"}");
            return;
        }

        String sql = "UPDATE datasets SET attendance_config = ? WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setString(1, body);
            ps.setLong(2, Long.parseLong(datasetIdStr));
            int rowsUpdated = ps.executeUpdate();
            if (rowsUpdated > 0) {
                resp.getWriter().write("{\"success\":true,\"message\":\"Attendance configuration updated successfully\"}");
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"success\":false,\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"success\":false,\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Default fallback Attendance configuration string (RFC compliant JSON).
     */
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

    /**
     * Reads and prints the Admit Card configuration.
     * 
     * @param req  HTTP request container.
     * @param resp HTTP response container.
     * @throws IOException if database fails.
     */
    private void handleGetAdmitConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        String token = req.getParameter("token");
        if (token != null && !token.trim().isEmpty()) {
            try {
                String decrypted = apps.admall.util.CryptoHelper.decrypt(token);
                String[] parts = decrypted.split(":");
                datasetIdStr = parts[1];
            } catch (Exception e) {
                resp.setStatus(400);
                resp.getWriter().write("{\"error\":\"Invalid or corrupted security token.\"}");
                return;
            }
        }

        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"datasetId or token parameter is required\"}");
            return;
        }

        String sql = "SELECT admit_config FROM datasets WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setLong(1, Long.parseLong(datasetIdStr));
            rs = ps.executeQuery();
            if (rs.next()) {
                String admitConfig = rs.getString("admit_config");
                resp.getWriter().write(admitConfig != null ? admitConfig : getDefaultAdmitConfig());
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Saves/updates the custom Admit Card template configuration.
     * 
     * @param req  HTTP request container.
     * @param resp HTTP response container.
     * @throws IOException if reader fails.
     */
    private void handleUploadAdmitConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"datasetId parameter is required\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        String body = sb.toString().trim();

        if (body.isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Request body is empty\"}");
            return;
        }

        // Validate JSON
        try {
            JsonParser.parseString(body);
        } catch (Exception e) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Invalid JSON format for admit card configuration\"}");
            return;
        }

        String sql = "UPDATE datasets SET admit_config = ? WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setString(1, body);
            ps.setLong(2, Long.parseLong(datasetIdStr));
            int rowsUpdated = ps.executeUpdate();
            if (rowsUpdated > 0) {
                resp.getWriter().write("{\"success\":true,\"message\":\"Admit card configuration updated successfully\"}");
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"success\":false,\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"success\":false,\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Default fallback Admit Card layout configuration string (RFC compliant JSON).
     */
    private String getDefaultAdmitConfig() {
        return "{\n" +
               "  \"admitCard\": {\n" +
               "    \"title\": \"Admit Card - Ph.D. Entrance Examination\",\n" +
               "    \"fields\": [\n" +
               "      { \"label\": \"Candidate ID\", \"source\": \"candidateId\" },\n" +
               "      { \"label\": \"Name of Candidate\", \"source\": \"B1\" },\n" +
               "      { \"label\": \"Father's Name\", \"source\": \"B2\" },\n" +
               "      { \"label\": \"Department\", \"source\": \"A2\" },\n" +
               "      { \"label\": \"Discipline/Area\", \"source\": \"A4\" }\n" +
               "    ],\n" +
               "    \"photographSource\": \"S11\",\n" +
               "    \"signatureSource\": \"S12\",\n" +
               "    \"examDetails\": {\n" +
               "      \"date\": \"July 15, 2026 (Wednesday)\",\n" +
               "      \"time\": \"10:00 AM to 01:00 PM\",\n" +
               "      \"reportingTime\": \"09:00 AM\",\n" +
               "      \"venue\": \"App Main Campus, Academic Block A\"\n" +
               "    },\n" +
               "    \"instructions\": [\n" +
               "      \"Please bring this Admit Card along with a valid photo ID proof to the examination hall.\",\n" +
               "      \"Electronic gadgets, mobile phones, and calculators are strictly prohibited inside the hall.\",\n" +
               "      \"Candidates will not be allowed to enter the exam hall 30 minutes after the commencement of the exam.\",\n" +
               "      \"Please maintain physical distance and follow local guidelines.\"\n" +
               "    ]\n" +
               "  }\n" +
               "}";
    }

    /**
     * Reads and prints the Student List configuration.
     */
    private void handleGetStudentListConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"datasetId parameter is required\"}");
            return;
        }

        String sql = "SELECT student_list_config FROM datasets WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setLong(1, Long.parseLong(datasetIdStr));
            rs = ps.executeQuery();
            if (rs.next()) {
                String config = rs.getString("student_list_config");
                resp.getWriter().write(config != null ? config : getDefaultStudentListConfig());
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Saves/updates the custom Student List configuration.
     */
    private void handleUploadStudentListConfig(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String datasetIdStr = req.getParameter("datasetId");
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"datasetId parameter is required\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        String body = sb.toString().trim();

        if (body.isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Request body is empty\"}");
            return;
        }

        // Validate JSON
        try {
            JsonParser.parseString(body);
        } catch (Exception e) {
            resp.setStatus(400);
            resp.getWriter().write("{\"success\":false,\"error\":\"Invalid JSON format for student list configuration\"}");
            return;
        }

        String sql = "UPDATE datasets SET student_list_config = ? WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setString(1, body);
            ps.setLong(2, Long.parseLong(datasetIdStr));
            int rowsUpdated = ps.executeUpdate();
            if (rowsUpdated > 0) {
                resp.getWriter().write("{\"success\":true,\"message\":\"Student list configuration updated successfully\"}");
            } else {
                resp.setStatus(404);
                resp.getWriter().write("{\"success\":false,\"error\":\"Dataset not found\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"success\":false,\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Default fallback Student List configuration string (RFC compliant JSON).
     */
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

    /** Escapes standard strings safely for JSON serialization in error payloads. */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
