package apps.admall.servlet;

import apps.admall.util.DBHelper;
import com.google.gson.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * <h2>ReportServlet</h2>
 * <p>
 * This servlet generates on-the-fly cross-tabulation (pivot table) aggregates for any uploaded dataset.
 * It reads dynamic configurations from the database <code>datasets</code> table (column <code>summary_config</code>),
 * maps configured keys to actual candidate record JSON fields, aggregates values across rows/columns,
 * and outputs structured JSON containing rows, column values, and column/row/grand totals.
 * </p>
 * 
 * <h3>API Endpoint</h3>
 * <p>GET /api/report?datasetId={id}</p>
 * 
 * <h3>Pivot Grid Mathematical Structure</h3>
 * <p>
 * Let R be the rowField (e.g. "A2. Name of Department") and C be the columnField (e.g. "B9. Category").
 * For each record, the servlet resolves:
 * <ul>
 *   <li>r = record.get(R)</li>
 *   <li>c = record.get(C)</li>
 * </ul>
 * It maps these values to a grid mapping: <code>Map&lt;RowName, Map&lt;ColumnName, Count&gt;&gt;</code>.
 * Row totals, column totals, and grand totals are calculated incrementally.
 * </p>
 * 
 * <h3>Sample Output JSON Shape</h3>
 * <pre>
 * {
 *   "reportName": "Department Wise Category Report",
 *   "rowField": "A2. Name of Department",
 *   "columnField": "B9. Category",
 *   "columns": ["General", "OBC", "ST"],
 *   "rows": [
 *     {
 *       "rowName": "Civil Engineering",
 *       "values": {
 *         "General": 8,
 *         "OBC": 1,
 *         "ST": 1,
 *         "Total": 10
 *       }
 *     }
 *   ],
 *   "totals": {
 *     "General": 8,
 *     "OBC": 1,
 *     "ST": 1,
 *     "Total": 10
 *   }
 * }
 * </pre>
 */
@WebServlet("/apps/admall/api/report")
public class ReportServlet extends HttpServlet {

    /** Gson instance configured with pretty-printing enabled for readable API responses. */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Handles HTTP GET requests to build the pivot table report dynamically.
     * 
     * @param req  The servlet request containing parameter "datasetId".
     * @param resp The servlet response containing the pretty JSON representation of the pivot report data.
     * @throws ServletException if any servlet-specific exception occurs.
     * @throws IOException      if an input/output exception occurs while writing the response.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        String datasetIdStr = req.getParameter("datasetId");
        String role = "";
        String forcedDept = "";
        long forcedDatasetId = -1;

        HttpSession session = req.getSession(false);
        if (session != null) {
            role = (String) session.getAttribute("role");
            if ("department".equalsIgnoreCase(role)) {
                String link = (String) session.getAttribute("link");
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

        if (datasetIdStr == null || datasetIdStr.trim().isEmpty()) {
            resp.setStatus(400); // Bad Request
            resp.getWriter().write("{\"error\":\"datasetId parameter is required.\"}");
            return;
        }

        // 1. Load summary report configuration from the DB datasets table
        JsonObject reportConfig = loadReportConfig(Long.parseLong(datasetIdStr));
        if (reportConfig == null) {
            resp.setStatus(500); // Internal Server Error
            resp.getWriter().write("{\"error\":\"Could not load report configuration for dataset.\"}");
            return;
        }

        // Parse configurations (casing-insensitive defaults applied if missing)
        String reportName = reportConfig.has("reportName") ? reportConfig.get("reportName").getAsString() : "Report";
        String rowField = reportConfig.has("rowField") ? reportConfig.get("rowField").getAsString() : "Row";
        String columnField = reportConfig.has("columnField") ? reportConfig.get("columnField").getAsString() : "Column";

        // Parse specific allowed columns from configuration if populated
        List<String> allowedCols = new ArrayList<>();
        if (reportConfig.has("columns") && reportConfig.get("columns").isJsonArray()) {
            JsonArray colsArr = reportConfig.getAsJsonArray("columns");
            for (JsonElement c : colsArr) {
                if (!c.getAsString().trim().isEmpty()) {
                    allowedCols.add(c.getAsString().trim());
                }
            }
        }
        
        // Respect "dynamicColumns": true parameter to auto-scan values dynamically
        boolean dynamicColumns = reportConfig.has("dynamicColumns")
                && reportConfig.get("dynamicColumns").getAsBoolean();
        if (dynamicColumns) {
            allowedCols.clear(); // force dynamic scan even if columns array was pre-populated
        }

        // 2. Query all candidate records for this dataset from the DB
        List<JsonObject> records = new ArrayList<>();
        String sql = "SELECT record_json FROM dataset_records WHERE dataset_id = ?";
        
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            ps.setLong(1, Long.parseLong(datasetIdStr));
            rs = ps.executeQuery();
            while (rs.next()) {
                String recordJsonStr = rs.getString("record_json");
                try {
                    JsonElement el = JsonParser.parseString(recordJsonStr);
                    if (el.isJsonObject()) {
                        records.add(el.getAsJsonObject());
                    }
                } catch (Exception e) {
                    // Skip malformed records silently to ensure stability
                }
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            return;
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }

        // Filter records for department role
        if ("department".equalsIgnoreCase(role) && forcedDept != null && !forcedDept.isEmpty()) {
            List<JsonObject> filteredRecords = new ArrayList<>();
            for (JsonObject record : records) {
                if (matchDepartmentOrSchool(record, forcedDept)) {
                    filteredRecords.add(record);
                }
            }
            records = filteredRecords;
        }

        // 3. Resolve configured field names to actual CSV header keys inside JSON records.
        // E.g. "Department" resolves to "A2. Name of Department" based on fuzzy alphanumeric containment.
        String resolvedRowField = rowField;
        String resolvedColumnField = columnField;

        if (!records.isEmpty()) {
            JsonObject firstRecord = records.get(0);
            resolvedRowField = resolveFieldName(firstRecord, rowField);
            resolvedColumnField = resolveFieldName(firstRecord, columnField);
        }

        // 4. Dynamic columns extraction (if columns list is empty, gather all unique values in columnField)
        if (allowedCols.isEmpty()) {
            Set<String> uniqueCols = new LinkedHashSet<>();
            for (JsonObject record : records) {
                String colVal = getRecordValue(record, resolvedColumnField);
                if (colVal != null && !colVal.trim().isEmpty()) {
                    uniqueCols.add(colVal.trim());
                }
            }
            allowedCols.addAll(uniqueCols);
            Collections.sort(allowedCols); // Sort columns alphabetically for neat presentation
        }

        // 5. Generate summary report pivot aggregates
        // pivotGrid: rowName -> (columnName -> count)
        Map<String, Map<String, Integer>> pivotGrid = new TreeMap<>();
        Map<String, Integer> colTotals = new HashMap<>();
        int grandTotal = 0;

        for (JsonObject record : records) {
            // Retrieve row and column values for current candidate record
            String rowVal = getRecordValue(record, resolvedRowField);
            if (rowVal == null || rowVal.trim().isEmpty()) {
                rowVal = "Unknown";
            }

            String colVal = getRecordValue(record, resolvedColumnField);
            if (colVal == null || colVal.trim().isEmpty()) {
                colVal = "Unknown";
            }

            // Map colVal case-insensitively against the allowed columns list
            String matchedCol = null;
            for (String col : allowedCols) {
                if (col.equalsIgnoreCase(colVal)) {
                    matchedCol = col;
                    break;
                }
            }

            // Fallback unrecognized values into "Other" bucket so they aren't lost
            if (matchedCol == null) {
                matchedCol = "Other";
                if (!allowedCols.contains("Other")) {
                    allowedCols.add("Other");
                }
            }

            // Increment count in the grid
            pivotGrid.putIfAbsent(rowVal, new HashMap<>());
            Map<String, Integer> rowColCounts = pivotGrid.get(rowVal);
            rowColCounts.put(matchedCol, rowColCounts.getOrDefault(matchedCol, 0) + 1);
        }

        // 6. Build structured response JSON containing rows, cell values, and totals
        JsonObject respJson = new JsonObject();
        respJson.addProperty("reportName", reportName);
        respJson.addProperty("rowField", resolvedRowField);
        respJson.addProperty("columnField", resolvedColumnField);

        JsonArray columnsJson = new JsonArray();
        for (String col : allowedCols) {
            columnsJson.add(col);
        }
        respJson.add("columns", columnsJson);

        JsonArray rowsJson = new JsonArray();
        for (Map.Entry<String, Map<String, Integer>> rowEntry : pivotGrid.entrySet()) {
            String rowName = rowEntry.getKey();
            Map<String, Integer> rowCounts = rowEntry.getValue();

            JsonObject rowObj = new JsonObject();
            rowObj.addProperty("rowName", rowName);

            JsonObject valuesObj = new JsonObject();
            int rowTotal = 0;
            for (String col : allowedCols) {
                int count = rowCounts.getOrDefault(col, 0);
                valuesObj.addProperty(col, count);
                rowTotal += count;

                // Accumulate column totals
                colTotals.put(col, colTotals.getOrDefault(col, 0) + count);
            }
            // Add row total
            valuesObj.addProperty("Total", rowTotal);
            grandTotal += rowTotal;

            rowObj.add("values", valuesObj);
            rowsJson.add(rowObj);
        }
        respJson.add("rows", rowsJson);

        // Add overall column and grand totals
        JsonObject totalsObj = new JsonObject();
        for (String col : allowedCols) {
            totalsObj.addProperty(col, colTotals.getOrDefault(col, 0));
        }
        totalsObj.addProperty("Total", grandTotal);
        respJson.add("totals", totalsObj);

        // Write output pretty JSON response
        resp.getWriter().write(gson.toJson(respJson));
    }

    /**
     * Resolves a key's primitive string value from a candidate JSON record.
     * 
     * @param record            The candidate record JSON object.
     * @param resolvedFieldName The resolved field key.
     * @return The string value of the field.
     */
    private String getRecordValue(JsonObject record, String resolvedFieldName) {
        if (record.has(resolvedFieldName) && !record.get(resolvedFieldName).isJsonNull()) {
            JsonElement el = record.get(resolvedFieldName);
            return el.isJsonPrimitive() ? el.getAsString() : el.toString();
        }
        return null;
    }

    /**
     * Maps configured field names fuzzy-match style to actual record keys.
     * 
     * @param record    A candidate record to scan keys.
     * @param fieldName The configured field name to map.
     * @return The actual key present in the JSON record, falling back to original fieldName if unmatched.
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

    private String resolveFieldName(JsonObject record, String fieldName) {
        if (record == null || fieldName == null || fieldName.isEmpty()) return fieldName;
        
        // Match 1: Exact case-sensitive match
        if (record.has(fieldName)) return fieldName;
        
        // Match 2: Case-insensitive match
        for (String key : record.keySet()) {
            if (key.equalsIgnoreCase(fieldName)) return key;
        }
        
        // Match 3: Clean alphanumeric containment match
        String cleanFieldName = clean(fieldName);
        for (String key : record.keySet()) {
            if (clean(key).contains(cleanFieldName)) return key;
        }
        
        // Match 4: Substring case-insensitive match
        String lowerFieldName = fieldName.toLowerCase();
        for (String key : record.keySet()) {
            if (key.toLowerCase().contains(lowerFieldName)) return key;
        }

        // Match 5: Strip prefix and do case-insensitive exact match
        String strippedFieldName = stripPrefix(fieldName);
        for (String key : record.keySet()) {
            if (stripPrefix(key).equalsIgnoreCase(strippedFieldName)) return key;
        }

        // Match 6: Strip prefix and do clean alphanumeric containment match
        String cleanStrippedFieldName = clean(strippedFieldName);
        for (String key : record.keySet()) {
            String cleanStrippedKey = clean(stripPrefix(key));
            if (cleanStrippedKey.contains(cleanStrippedFieldName) || cleanStrippedFieldName.contains(cleanStrippedKey)) return key;
        }

        // Match 7: Strip prefix and do case-insensitive substring fallback match
        String lowerStrippedFieldName = strippedFieldName.toLowerCase();
        for (String key : record.keySet()) {
            String lowerStrippedKey = stripPrefix(key).toLowerCase();
            if (lowerStrippedKey.contains(lowerStrippedFieldName) || lowerStrippedFieldName.contains(lowerStrippedKey)) return key;
        }

        // Match 8: Word-based match score
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
            return bestKey;
        }

        return fieldName;
    }

    /** Helper to strip symbols and spaces for alphanumeric-only checks. */
    private String clean(String s) {
        return s.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    /**
     * Loads the dataset's summary configuration directly from the datasets table column.
     * Falls back to hardcoded configuration if the column value is missing or null.
     */
    private JsonObject loadReportConfig(long datasetId) {
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
                    return JsonParser.parseString(configStr).getAsJsonObject();
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading summary config from datasets table for id " + datasetId + ": " + e.getMessage());
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }

        // Default hardcoded configuration fallback
        try {
            String fallbackJson = "{\n" +
                    "  \"reportName\": \"Department Wise Category Report\",\n" +
                    "  \"rowField\": \"A2. Name of Department\",\n" +
                    "  \"columnField\": \"B9. Category\",\n" +
                    "  \"columns\": [],\n" +
                    "  \"dynamicColumns\": true,\n" +
                    "  \"showRowTotal\": true,\n" +
                    "  \"showColumnTotal\": true\n" +
                    "}";
            return JsonParser.parseString(fallbackJson).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** Escapes special characters for safe output in error JSON responses. */
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