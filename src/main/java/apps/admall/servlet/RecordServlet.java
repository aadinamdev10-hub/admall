package apps.admall.servlet;

import apps.admall.util.DBHelper;
import com.google.gson.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.*;

/**
 * <h2>RecordServlet</h2>
 * <p>
 * This servlet handles retrieval of a single candidate application form record from the database.
 * It is called when a user wants to view the detailed form layout of a specific candidate (e.g. via view.html).
 * </p>
 * 
 * <h3>API Endpoint</h3>
 * <p>GET /api/record?id={record_id}</p>
 * 
 * <h3>Database Interactions</h3>
 * <p>
 * It queries the <code>dataset_records</code> table by the record primary key <code>id</code>.
 * </p>
 * 
 * <h3>Sample JSON Response</h3>
 * <pre>
 * {
 *   "id": 52,
 *   "datasetId": 2,
 *   "record": {
 *     "A2. Name of Department": "Department of Civil Engineering",
 *     "B1. Name of Candidate": "John Doe",
 *     "Email Address": "john.doe@example.com"
 *   }
 * }
 * </pre>
 *
 * <h3>Servlet Configuration</h3>
 * <p>
 * mapped dynamically using {@link WebServlet} annotation to "/api/record".
 * </p>
 */
@WebServlet("/apps/admall/api/record")
public class RecordServlet extends HttpServlet {

    /** Gson instance configured with pretty-printing enabled for readable API responses. */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Handles HTTP GET requests to fetch detailed record values.
     * 
     * @param req  The servlet request containing parameter "id".
     * @param resp The servlet response containing the JSON representation of the candidate record.
     * @throws ServletException if any servlet-specific exception occurs.
     * @throws IOException      if an input/output exception occurs while reading or writing parameters.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // Force UTF-8 encoding and set content type to application/json
        resp.setContentType("application/json; charset=UTF-8");

        // Extract and validate the candidate record ID or token parameter
        String recordIdStr = req.getParameter("id");
        String token = req.getParameter("token");
        if (token != null && !token.trim().isEmpty()) {
            try {
                String decrypted = apps.admall.util.CryptoHelper.decrypt(token);
                String[] parts = decrypted.split(":");
                recordIdStr = parts[0];
            } catch (Exception e) {
                resp.setStatus(400);
                resp.getWriter().write("{\"error\":\"Invalid or corrupted security token.\"}");
                return;
            }
        }

        if (recordIdStr == null || recordIdStr.trim().isEmpty()) {
            resp.setStatus(400); // Bad Request
            resp.getWriter().write("{\"error\":\"id or token parameter is required.\"}");
            return;
        }

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
            }
        }

        // Database SELECT query to fetch candidate information
        String sql = "SELECT id, dataset_id, record_json FROM dataset_records WHERE id = ?";
        
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            con = DBHelper.getConnection();
            ps = con.prepareStatement(sql);
            
            // Set the dynamic parameter in pre-compiled statement (1-indexed)
            ps.setLong(1, Long.parseLong(recordIdStr));
            
            rs = ps.executeQuery();
            if (rs.next()) {
                // Extract values from DB result set columns
                long id = rs.getLong("id");
                long datasetId = rs.getLong("dataset_id");
                String recordJsonStr = rs.getString("record_json");

                // Restrict for department role
                if ("department".equalsIgnoreCase(role)) {
                    if (forcedDatasetId != -1 && datasetId != forcedDatasetId) {
                        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        resp.getWriter().write("{\"error\":\"Forbidden. Access denied for this dataset.\"}");
                        return;
                    }
                    JsonObject recordObj = JsonParser.parseString(recordJsonStr).getAsJsonObject();
                    if (forcedDept != null && !forcedDept.isEmpty()) {
                        if (!matchDepartmentOrSchool(recordObj, forcedDept)) {
                            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            resp.getWriter().write("{\"error\":\"Forbidden. Access denied for this department.\"}");
                            return;
                        }
                    }
                }

                // Build response JSON object
                JsonObject responseObj = new JsonObject();
                responseObj.addProperty("id", id);
                responseObj.addProperty("datasetId", datasetId);
                
                // Parse raw JSON record string back to a JsonElement structure to avoid double escaping
                responseObj.add("record", JsonParser.parseString(recordJsonStr));

                // Write output JSON response
                resp.getWriter().write(gson.toJson(responseObj));
            } else {
                resp.setStatus(404); // Not Found
                resp.getWriter().write("{\"error\":\"Record not found.\"}");
            }
        } catch (SQLException | NumberFormatException e) {
            resp.setStatus(500); // Internal Server Error
            resp.getWriter().write("{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException ignored) {} }
            if (ps != null) { try { ps.close(); } catch (SQLException ignored) {} }
            DBHelper.closeConnection(con);
        }
    }

    /**
     * Escapes critical characters (backslashes and double quotes) to safeguard JSON strings.
     * 
     * @param s The string to escape.
     * @return An escaped version of the input string safe for direct insertion into inline JSON.
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
