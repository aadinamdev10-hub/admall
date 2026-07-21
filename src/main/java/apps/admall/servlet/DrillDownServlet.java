package apps.admall.servlet;

import apps.admall.util.DBHelper;
import com.google.gson.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <h2>DrillDownServlet</h2>
 * <p>
 * This servlet handles dynamic filtering and extraction of dataset records matching 
 * a selected intersection (row x column cell) inside the Report dashboard pivot table.
 * </p>
 * 
 * <h3>API Endpoint</h3>
 * <p>GET /api/drilldown?datasetId=X&rowField=A&rowValue=B&columnField=C&columnValue=D</p>
 * 
 * <h3>Filter Logic</h3>
 * <ul>
 *   <li>If <code>rowValue</code> is <b>"Total"</b>, no filtering is applied on the <code>rowField</code> (returns all rows).</li>
 *   <li>If <code>columnValue</code> is <b>"Total"</b>, no filtering is applied on the <code>columnField</code> (returns all columns).</li>
 *   <li>Otherwise, only records matching both the rowValue and columnValue (resolved via fuzzy-matching) are returned.</li>
 * </ul>
 * 
 * <h3>Database Interactions</h3>
 * <p>
 * Fetches all candidate records from <code>dataset_records</code> table mapped to <code>dataset_id = ?</code>
 * and filters them on the fly in memory.
 * </p>
 */
@WebServlet("/apps/admall/api/drilldown")
public class DrillDownServlet extends HttpServlet {

    /** Gson instance configured with pretty-printing enabled for readable API responses. */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private String getRowFieldFromDb(long datasetId) {
        String sql = "SELECT summary_config FROM datasets WHERE id = ?";
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setLong(1, datasetId);
            rs = ps.executeQuery();
            if (rs.next()) {
                String configStr = rs.getString("summary_config");
                if (configStr != null && !configStr.trim().isEmpty()) {
                    JsonObject configObj = JsonParser.parseString(configStr).getAsJsonObject();
                    if (configObj.has("rowField")) {
                        return configObj.get("rowField").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading summary config in DrillDownServlet: " + e.getMessage());
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
        return "A2. Name of Department"; // fallback default
    }

    /**
     * Handles HTTP GET requests to retrieve a list of records matching row x column filters.
     * 
     * @param req  The servlet request containing query parameters: datasetId, rowField, rowValue, columnField, columnValue.
     * @param resp The servlet response containing the JSON array of filtered records.
     * @throws ServletException if any servlet-specific exception occurs.
     * @throws IOException      if an input/output exception occurs while writing the response.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");

        // Parse query parameters
        String datasetIdStr = req.getParameter("datasetId");
        String rowField = req.getParameter("rowField");     // e.g. "A2. Name of Department"
        String rowValue = req.getParameter("rowValue");     // e.g. "Department of CSE" or "Total"
        String columnField = req.getParameter("columnField"); // e.g. "B9. Category"
        String columnValue = req.getParameter("columnValue"); // e.g. "General" or "Total"

        String role = "";
        String forcedDept = "";
        HttpSession session = req.getSession(false);
        if (session != null) {
            role = (String) session.getAttribute("role");
            if ("department".equalsIgnoreCase(role)) {
                String link = (String) session.getAttribute("link");
                long forcedDatasetId = -1;
                 if (link != null && !link.isEmpty()) {
                    String queryString = link;
                    if (link.contains("?")) {
                        String[] parts = link.split("\\?");
                        if (parts.length > 1) {
                            queryString = parts[1];
                        }
                    }
                    String[] pairs = queryString.split("&");
                    for (String pair : pairs) {
                        String[] kv = pair.split("=");
                        if (kv.length == 2) {
                            if (kv[0].equals("datasetId")) {
                                try {
                                    forcedDatasetId = Long.parseLong(kv[1]);
                                } catch (NumberFormatException ignored) {}
                            } else if (kv[0].equals("department")) {
                                forcedDept = java.net.URLDecoder.decode(kv[1], "UTF-8");
                            }
                        }
                    }
                    if (forcedDept.isEmpty()) {
                        if (link != null && !link.contains("=") && !link.contains("?")) {
                            forcedDept = link;
                        } else {
                            forcedDept = (String) session.getAttribute("username");
                        }
                    }
                }
                if (forcedDatasetId != -1) {
                    datasetIdStr = String.valueOf(forcedDatasetId);
                }
            }
        }

        // Dataset ID is a mandatory key to target records
        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400); // Bad Request
            resp.getWriter().write("{\"error\":\"datasetId parameter is required.\"}");
            return;
        }

        List<Map<String, Object>> filteredRecords = new ArrayList<>();
        String sql = "SELECT id, record_json FROM dataset_records WHERE dataset_id = ?";

        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setLong(1, Long.parseLong(datasetIdStr));
            
            rs = ps.executeQuery();
            while (rs.next()) {
                long recordId = rs.getLong("id");
                String recordJsonStr = rs.getString("record_json");

                try {
                    // Parse candidate record JSON
                    JsonObject recordObj = JsonParser.parseString(recordJsonStr).getAsJsonObject();
                    boolean match = true;

                    // Apply department filter if role is department
                    if ("department".equalsIgnoreCase(role) && forcedDept != null && !forcedDept.isEmpty()) {
                        if (!matchDepartmentOrSchool(recordObj, forcedDept)) {
                            match = false;
                        }
                    }

                    // Apply row filter if rowField and rowValue are specified, and rowValue is not "Total"
                    if (rowField != null && rowValue != null && !rowValue.equalsIgnoreCase("Total")) {
                        String actualRowVal = getFieldValue(recordObj, rowField);
                        if (actualRowVal == null || !actualRowVal.equalsIgnoreCase(rowValue)) {
                            match = false;
                        }
                    }

                    // Apply column filter if columnField and columnValue are specified, and columnValue is not "Total"
                    if (columnField != null && columnValue != null && !columnValue.equalsIgnoreCase("Total")) {
                        String actualColVal = getFieldValue(recordObj, columnField);
                        if (actualColVal == null || !actualColVal.equalsIgnoreCase(columnValue)) {
                            match = false;
                        }
                    }

                    // Add matching record to the list
                    if (match) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("id", recordId);
                        entry.put("record", recordObj);
                        filteredRecords.add(entry);
                    }
                } catch (Exception e) {
                    // Skip malformed individual records to maintain stability
                }
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500); // Internal Server Error
            resp.getWriter().write("{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            return;
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }

        // Return pretty JSON array of matching records
        resp.getWriter().write(gson.toJson(filteredRecords));
    }

    /**
     * Fuzzy matching helper to extract values from Candidate JSON records.
     * Often, keys in CSV records may contain prefix codes, leading spaces, or minor casing differences.
     * 
     * @param record    The candidate record JSON object.
     * @param fieldName The configured field label to search for.
     * @return The string value of the matching field, or null if not resolved.
     */
    private String stripPrefix(String s) {
        if (s == null) return "";
        String stripped = s.replaceAll("^[A-Za-z][0-9]+\\.?\\s*", "").trim();
        return stripped.isEmpty() ? s : stripped;
    }

    private boolean isStopWord(String w) {
        String lower = w.toLowerCase();
        return lower.equals("of") || lower.equals("and") || lower.equals("or") || lower.equals("the") || lower.equals("in") || lower.equals("a") || lower.equals("an");
    }

    private int getWordMatchScore(String key, String fieldName) {
        if (key == null || fieldName == null) return 0;
        
        String strippedKey = stripPrefix(key).toLowerCase();
        String strippedFieldName = stripPrefix(fieldName).toLowerCase();
        
        String[] keyWords = strippedKey.split("[^a-zA-Z0-9]+");
        String[] fieldWords = strippedFieldName.split("[^a-zA-Z0-9]+");
        
        Set<String> keyWordSet = new HashSet<>();
        for (String w : keyWords) {
            if (!w.isEmpty()) keyWordSet.add(w);
        }
        
        int score = 0;
        for (String w : fieldWords) {
            if (!w.isEmpty() && !isStopWord(w)) {
                if (keyWordSet.contains(w)) {
                    score += 10;
                } else {
                    for (String kw : keyWordSet) {
                        if (kw.contains(w) || w.contains(kw)) {
                            score += 5;
                            break;
                        }
                    }
                }
            }
        }
        return score;
    }

    private String getFieldValue(JsonObject record, String fieldName) {
        if (record == null || fieldName == null || fieldName.isEmpty()) return null;
        
        // Match Pattern 1: Exact key match
        if (record.has(fieldName) && !record.get(fieldName).isJsonNull()) {
            return record.get(fieldName).getAsString();
        }
        
        // Match Pattern 2: Case-insensitive exact match
        for (String key : record.keySet()) {
            if (key.equalsIgnoreCase(fieldName)) {
                JsonElement el = record.get(key);
                return el.isJsonNull() ? null : el.getAsString();
            }
        }
        
        // Match Pattern 3: Clean alphanumeric comparison (ignores spaces, periods, symbols)
        String cleanFieldName = clean(fieldName);
        for (String key : record.keySet()) {
            if (clean(key).contains(cleanFieldName)) {
                JsonElement el = record.get(key);
                return el.isJsonNull() ? null : el.getAsString();
            }
        }
        
        // Match Pattern 4: Case-insensitive substring fallback match
        String lowerFieldName = fieldName.toLowerCase();
        for (String key : record.keySet()) {
            if (key.toLowerCase().contains(lowerFieldName)) {
                JsonElement el = record.get(key);
                return el.isJsonNull() ? null : el.getAsString();
            }
        }

        // Match Pattern 5: Strip prefix and do case-insensitive exact match
        String strippedFieldName = stripPrefix(fieldName);
        for (String key : record.keySet()) {
            if (stripPrefix(key).equalsIgnoreCase(strippedFieldName)) {
                JsonElement el = record.get(key);
                return el.isJsonNull() ? null : el.getAsString();
            }
        }

        // Match Pattern 6: Strip prefix and do clean alphanumeric containment match
        String cleanStrippedFieldName = clean(strippedFieldName);
        for (String key : record.keySet()) {
            String cleanStrippedKey = clean(stripPrefix(key));
            if (cleanStrippedKey.contains(cleanStrippedFieldName) || cleanStrippedFieldName.contains(cleanStrippedKey)) {
                JsonElement el = record.get(key);
                return el.isJsonNull() ? null : el.getAsString();
            }
        }

        // Match Pattern 7: Strip prefix and do case-insensitive substring fallback match
        String lowerStrippedFieldName = strippedFieldName.toLowerCase();
        for (String key : record.keySet()) {
            String lowerStrippedKey = stripPrefix(key).toLowerCase();
            if (lowerStrippedKey.contains(lowerStrippedFieldName) || lowerStrippedFieldName.contains(lowerStrippedKey)) {
                JsonElement el = record.get(key);
                return el.isJsonNull() ? null : el.getAsString();
            }
        }

        // Match Pattern 8: Word-based match score
        String bestKey = null;
        int maxScore = 0;
        for (String key : record.keySet()) {
            int score = getWordMatchScore(key, fieldName);
            if (score > maxScore) {
                maxScore = score;
                bestKey = key;
            }
        }
        if (bestKey != null && maxScore >= 5) {
            JsonElement el = record.get(bestKey);
            return el.isJsonNull() ? null : el.getAsString();
        }

        return null;
    }

    /**
     * Helper to clean strings by removing all non-alphanumeric characters and converting to lowercase.
     * 
     * @param s The string to clean.
     * @return Cleaned lowercase string.
     */
    private String clean(String s) {
        return s.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    /**
     * Escapes double quotes and backslashes in exception messages to generate safe JSON response strings.
     * 
     * @param s The string to escape.
     * @return Escaped string.
     */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean matchDepartmentOrSchool(JsonObject record, String forcedDept) {
        if (record == null || forcedDept == null || forcedDept.trim().isEmpty()) {
            return false;
        }
        String target = forcedDept.trim();
        
        // Check keys containing "department" or "a2"
        for (String key : record.keySet()) {
            String keyLower = key.toLowerCase();
            if (keyLower.contains("department") || keyLower.contains("a2")) {
                JsonElement val = record.get(key);
                if (val != null && !val.isJsonNull()) {
                    if (val.getAsString().trim().equalsIgnoreCase(target)) {
                        return true;
                    }
                }
            }
        }
        
        // Check keys containing "school", "institute", "college", or "a1"
        for (String key : record.keySet()) {
            String keyLower = key.toLowerCase();
            if (keyLower.contains("school") || keyLower.contains("institute") || keyLower.contains("college") || keyLower.contains("a1")) {
                JsonElement val = record.get(key);
                if (val != null && !val.isJsonNull()) {
                    if (val.getAsString().trim().equalsIgnoreCase(target)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
}
