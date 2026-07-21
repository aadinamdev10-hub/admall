package apps.admall.util;

import java.io.*;
import java.util.*;

/**
 * <h2>CSVHelper</h2>
 * <p>
 * A utility class designed to parse CSV (Comma-Separated Values) strings into structured
 * collections of data (List of Maps), mapping header names to row cell values.
 * </p>
 * 
 * <h3>Key Features</h3>
 * <ul>
 *   <li><b>Embedded Newlines:</b> Correctly handles values that contain line breaks inside double quotes.</li>
 *   <li><b>Escaped Double Quotes:</b> Parses double-quotes within fields escaped in standard RFC-4180 format (e.g. <code>""</code> is treated as a literal <code>"</code>).</li>
 *   <li><b>Trailing/Leading Spaces & Carriage Returns:</b> Automatically normalizes and trims spaces and replaces carriage returns with spaces for uniform string representations.</li>
 *   <li><b>Empty Row Filtering:</b> Filters out empty rows to prevent blank entries in the database.</li>
 * </ul>
 * 
 * <h3>Usage Example</h3>
 * <pre>
 * String csvData = "Name,Department,Email\n" +
 *                  "John Doe,\"Civil Engineering\",john.doe@example.com\n" +
 *                  "\"Jane \"\"Editor\"\" Doe\",CSE,jane@example.com";
 * List&lt;Map&lt;String, String&gt;&gt; records = CSVHelper.parseString(csvData);
 * </pre>
 */
public class CSVHelper {

    /**
     * Parses a CSV string content into a List of Maps where each Map represents a row (header name -> trimmed field value).
     * 
     * <p>
     * <b>Logic details:</b>
     * <ol>
     *   <li>Validates the input string; returns empty list if null or empty.</li>
     *   <li>Calls {@link #parseCSV(String)} to perform the raw char-by-char double-quote aware split of fields and rows.</li>
     *   <li>Extracts the first row as the raw header columns and cleans them (normalizes whitespace, strips symbols).</li>
     *   <li>Iterates over remaining rows, filters out completely empty lines (e.g. trailing empty CSV lines).</li>
     *   <li>Constructs a <code>LinkedHashMap</code> (to preserve column sequence) for each record, mapping headers to values.</li>
     * </ol>
     * </p>
     * 
     * @param csvContent The raw multi-line CSV string contents.
     * @return List&lt;Map&lt;String, String&gt;&gt; List of records mapping header names to row field values.
     * @throws IOException If parsing or character reading fails.
     */
    public static List<Map<String, String>> parseString(String csvContent) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        if (csvContent == null || csvContent.trim().isEmpty()) {
            return rows;
        }

        // Parse raw rows character-by-character
        List<List<String>> allParsedRows = parseCSV(csvContent);
        if (allParsedRows.isEmpty()) {
            return rows;
        }

        // Clean headers: normalizes internal newlines or tabs to spaces
        List<String> rawHeaders = allParsedRows.get(0);
        List<String> headers = new ArrayList<>();
        for (String h : rawHeaders) {
            if (h != null) {
                String cleanHeader = h.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
                headers.add(cleanHeader);
            } else {
                headers.add("");
            }
        }

        // Map values to headers for each row
        for (int i = 1; i < allParsedRows.size(); i++) {
            List<String> values = allParsedRows.get(i);
            
            // Check if this row is completely empty to filter out trailing empty lines
            boolean isEmptyRow = true;
            for (String val : values) {
                if (val != null && !val.trim().isEmpty()) {
                    isEmptyRow = false;
                    break;
                }
            }
            if (isEmptyRow) continue;

            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) {
                String key = headers.get(j);
                String val = j < values.size() ? values.get(j).trim() : "";
                val = val.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
                row.put(key, val);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Parses the raw CSV content character-by-character using an internal state machine.
     * This method respects double quotes and allows newlines to be embedded directly inside quoted fields.
     * 
     * <p>
     * <b>State Machine Rules:</b>
     * <ul>
     *   <li>If a character is <code>"</code>:
     *     <ul>
     *       <li>If followed by another <code>"</code> and currently in a quoted block (escaped quotes, e.g. <code>""</code>), append a single <code>"</code> to field value and skip.</li>
     *       <li>Otherwise, toggle the <code>inQuotes</code> boolean flag.</li>
     *     </ul>
     *   </li>
     *   <li>If a character is <code>,</code> and <code>inQuotes</code> is false, finalize the current field.</li>
     *   <li>If a character is <code>\n</code> or <code>\r</code> and <code>inQuotes</code> is false, finalize the field and row.</li>
     *   <li>Otherwise, append the character directly to the field value buffer.</li>
     * </ul>
     * </p>
     * 
     * @param csvContent The raw CSV content.
     * @return List&lt;List&lt;String&gt;&gt; A list of raw rows containing list of field strings.
     */
    private static List<List<String>> parseCSV(String csvContent) {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        
        int len = csvContent.length();
        for (int i = 0; i < len; i++) {
            char c = csvContent.charAt(i);
            
            if (c == '"') {
                // Handle escaped double quotes (e.g. "")
                if (inQuotes && i + 1 < len && csvContent.charAt(i + 1) == '"') {
                    currentField.append('"');
                    i++; // Skip the next quote
                } else {
                    inQuotes = !inQuotes; // Toggle quotes status
                }
            } else if (c == ',' && !inQuotes) {
                currentRow.add(currentField.toString());
                currentField.setLength(0);
            } else if ((c == '\n' || c == '\r') && !inQuotes) {
                if (c == '\r' && i + 1 < len && csvContent.charAt(i + 1) == '\n') {
                    i++; // Skip standard Windows line endings
                }
                currentRow.add(currentField.toString());
                currentField.setLength(0);
                
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            } else {
                currentField.append(c);
            }
        }
        
        // Add last remaining field/row (handles file without ending newline)
        if (currentField.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(currentField.toString());
            rows.add(currentRow);
        }
        
        return rows;
    }
}