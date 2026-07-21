/**
 * @file view.js
 * @description Candidate application detailed form visualizer.
 * Loads a single candidate record dynamically via its database primary ID,
 * retrieves the corresponding form configuration JSON, parses the structure,
 * and renders a polished, print-friendly single-column layout form.
 * 
 * <h3>Logic & Parsing Walkthrough</h3>
 * <ol>
 *   <li><b>URL Parameter Parsing:</b> Extracts the candidate record <code>id</code> from the browser query string (e.g. <code>view.html?id=45</code>).</li>
 *   <li><b>Candidate Record Fetch:</b> Contacts <code>GET api/record?id=X</code> to retrieve the raw CSV column key-value map for this entry.</li>
 *   <li><b>Form Config Fetch:</b> Contacts <code>GET api/datasets?action=getFormConfig&datasetId=Y</code> to load the dataset-specific Form Layout Configuration.</li>
 *   <li><b>Dynamic Layout Generation:</b> Feeds the candidate record map and form layout config into <code>generateDynamicForm()</code>.</li>
 *   <li><b>Section Sorting and Interleaving:</b> Traverses config sections (arrays/objects) and tables, maps key prefixes (e.g., "A", "B", "qualificationTable"),
 *       sorts them alphabetically by prefix code to interleave tables and form fields in the correct order, and dynamically draws styled cards.</li>
 * </ol>
 * 
 * @example
 * // Sample Record Key-Value Map:
 * {
 *   "A1. Name of School": "School of Computing",
 *   "A2. Name of Department": "Information Technology",
 *   "B1. Name of Candidate": "Aadi Dev",
 *   "B2. Father's Name": "S. Dev",
 *   "Email Address": "aadi.dev@example.com",
 *   "S11. Photograph": "https://example.com/photo.jpg"
 * }
 * 
 * // Sample Form Layout Config JSON:
 * {
 *   "sections": {
 *     "A": ["A1", "A2"],
 *     "B": ["B1", "B2", "Email Address"]
 *   },
 *   "qualificationTable": {
 *     "columns": ["courseLevel", "institutionName", "yearOfPassing"],
 *     "rows": [
 *       { "qualification": "10th", "source": ["A5", "A6"] }
 *     ]
 *   }
 * }
 */

document.addEventListener("DOMContentLoaded", () => {
  // Extract record ID parameter from browser URL
  const params = new URLSearchParams(location.search);
  const recordId = params.get("id");

  // Safeguard against null or missing ID parameters
  if (!recordId) {
    showAlert("No record ID specified in URL.", "error");
    hideLoading();
    return;
  }

  // Phase 1: Retrieve session and validate
  fetch("api/session")
    .then(resp => {
      if (!resp.ok) throw new Error("Authentication check failed.");
      return resp.json();
    })
    .then(session => {
      if (!session.loggedIn) {
        window.location.href = "login.html";
        return;
      }

      // Retrieve candidate record key-value pairs
      return fetch(`api/record?id=${recordId}`)
        .then(resp => {
          if (!resp.ok) {
            return resp.json().then(errData => {
              throw new Error(errData.error || "Failed to fetch record details.");
            }).catch(() => {
              throw new Error("Failed to fetch record details.");
            });
          }
          return resp.json();
        });
    })
    .then(data => {
      if (!data) return;
      const record = data.record;
      const datasetId = data.datasetId;
      document.getElementById("recordTitleId").textContent = `Record Database ID: #${recordId}`;

      // Phase 2: Retrieve the visual form configuration mapped to this dataset
      return fetch(`api/datasets?action=getFormConfig&datasetId=${datasetId}`)
        .then(resp => resp.json())
        .then(config => {
          hideLoading();
          // Reveal form container and paint fields dynamically
          document.getElementById("viewContainer").style.display = "block";
          const container = document.getElementById("formContent");
          generateDynamicForm(record, config, container);
        });
    })
    .catch(err => {
      hideLoading();
      showAlert("Error loading application record: " + err.message, "error");
    });
});

/**
 * Displays user-facing alert notifications at the top of the form layout.
 * 
 * @param {string} message The text content of the message.
 * @param {"error"|"success"|"info"} type The style category for color themes.
 */
function showAlert(message, type) {
  const alertDiv = document.getElementById("alert");
  alertDiv.style.display = "block";
  alertDiv.className = `alert alert-${type} show`;
  alertDiv.textContent = message;
}

/**
 * Hides the startup loading spinner from the viewport.
 */
function hideLoading() {
  document.getElementById("loading").style.display = "none";
}

/**
 * Escapes hazardous characters in string values to prevent HTML Injection / Cross-Site Scripting (XSS).
 * 
 * @param {string} s The raw input string.
 * @returns {string} Safe HTML-escaped string.
 */
function escapeHtml(s) {
  if (!s) return "";
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

/**
 * Core engine that parses form layouts and candidate records to generate dynamic HTML forms.
 * Supports sections, structured data arrays, nested grids, and external hyperlink resolution.
 * 
 * @param {Object} record The candidate key-value mapping (raw CSV row data).
 * @param {Object} config The form configuration layout schema definition.
 * @param {HTMLElement} container The DOM node to attach the compiled elements.
 */
function generateDynamicForm(record, config, container) {
  container.innerHTML = "";

  /**
   * Helper function to perform prefix-based value resolution for fuzzy key matching.
   * Resolves field configurations like "A1" to "A1. Name of School" dynamically.
   * 
   * @param {Object} record Candidate map.
   * @param {string} prefix Key prefix.
   * @returns {null|{key: string, value: *}} Resolved key and value object, or null.
   */
  function getRecordFieldValueByPrefix(record, prefix) {
    if (!record || !prefix) return null;
    const lowerPrefix = prefix.toLowerCase().trim();
    for (let key in record) {
      const lowerKey = key.toLowerCase().trim();
      
      // Exact Match
      if (lowerKey === lowerPrefix) {
        return { key: key, value: record[key] };
      }
      // Prefix match (ignores symbols or extra text trailing the code)
      if (lowerKey.startsWith(lowerPrefix)) {
        const remaining = lowerKey.slice(lowerPrefix.length);
        if (remaining.length === 0 || !/^[a-z0-9]/.test(remaining)) {
          return { key: key, value: record[key] };
        }
      }
    }
    return null;
  }

  /**
   * Cleans alphanumeric index codes (e.g. "A2. ") from labels to present clean headers.
   * 
   * @param {string} label Raw display label.
   * @param {string} prefix Prefix pattern to strip.
   * @returns {string} Stripped human-readable label.
   */
  function cleanFieldLabel(label, prefix) {
    if (!label) return "";
    let clean = label.trim();
    const prefixPattern = new RegExp(`^${prefix}\\.?\\s*`, 'i');
    return clean.replace(prefixPattern, '');
  }

  /**
   * Formats cell values. Handles links (URL strings) by drawing clickable external link buttons.
   * Replaces empty or undefined values with a standardized "—".
   * 
   * @param {*} val Raw value from the candidate record.
   * @returns {string} Rendered safe HTML string.
   */
  function formatFieldValue(val) {
    if (val === undefined || val === null || String(val).trim() === "") {
      return "—";
    }
    const strVal = String(val).trim();
    // Regex verification for URL addresses
    if (strVal.startsWith("http://") || strVal.startsWith("https://")) {
      return `<a href="${escapeHtml(strVal)}" target="_blank" class="btn btn-outline btn-sm" style="padding: 2px 8px; font-size: 0.8rem; font-weight: 600; text-decoration: none; display: inline-flex; align-items: center; gap: 4px;">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="margin-right:2px;"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path><polyline points="15 3 21 3 21 9"></polyline><line x1="10" y1="14" x2="21" y2="3"></line></svg>
        View Document
      </a>`;
    }
    return escapeHtml(strVal);
  }

  // Hardcoded dictionary to resolve section prefixes to professional block headers
  const sectionTitles = {
    "A": "Program Details",
    "B": "Personal Information",
    "C": "Permanent Address",
    "D": "Correspondence Address",
    "J": "Entrance Examination Details",
    "K": "Experience Summary",
    "P": "Payment Details",
    "Q": "Hostel Requirement",
    "R": "Other Information",
    "S": "Document Uploads",
    "T": "Score Calculation",
    "U": "Declaration"
  };

  /**
   * Formats camelCase column configurations into capital spaced header labels.
   * 
   * @param {string} col Column identifier code.
   * @returns {string} Human-readable header name.
   */
  const cleanColumnHeader = (col) => {
    if (col === "courseLevel") return "Course Level";
    if (col === "institutionName") return "Institution Name";
    if (col === "subjectBranch") return "Subject / Branch";
    if (col === "boardUniversity") return "Board / University";
    if (col === "yearOfPassing") return "Year of Passing";
    if (col === "marksObtained") return "Marks Obtained";
    if (col === "totalMarks") return "Total Marks";
    if (col === "conversionFormula") return "CGPA Conversion Formula";
    if (col === "degreeCertificate") return "Degree / Certificate";
    if (col === "organizationName") return "Organization Name";
    if (col === "organizationType") return "Organization Type";
    if (col === "fromDate") return "From Date";
    if (col === "toDate") return "To Date";
    if (col === "employmentType") return "Employment Type";
    if (col === "natureOfJob") return "Nature of Job";
    if (col === "document") return "Document";
    
    return col.replace(/([A-Z])/g, ' $1')
              .replace(/^[a-z]/, c => c.toUpperCase())
              .trim();
  };

  /**
   * Maps individual row-level column names to their prefix source fields.
   * Resolves row configurations such as course level designations.
   */
  const mapRowData = (columns, source, qualification, record) => {
    const rowData = {};
    let sourceIndex = 0;
    
    for (let i = 0; i < columns.length; i++) {
      const colName = columns[i];
      if (colName === "courseLevel" && source.length < columns.length) {
        rowData[colName] = qualification || "";
        continue;
      }
      if (sourceIndex < source.length) {
        const prefix = source[sourceIndex];
        const match = getRecordFieldValueByPrefix(record, prefix);
        rowData[colName] = match ? match.value : "";
        sourceIndex++;
      } else {
        rowData[colName] = "";
      }
    }
    return rowData;
  };

  /**
   * Traverses structural grid definitions and maps database values to table cells.
   */
  const mapTableRows = (tableDef, record) => {
    const rows = [];
    const columns = tableDef.columns || [];

    // Strategy A: Row configurations explicitly structured (e.g. academic credentials)
    if (tableDef.rows && tableDef.rows.length > 0) {
      tableDef.rows.forEach(rowConfig => {
        const qualification = rowConfig.qualification;
        const source = rowConfig.source || [];
        const rowData = mapRowData(columns, source, qualification, record);
        
        // Populate document column value if defined in the row config (e.g. S1, S2)
        if (rowConfig.document) {
          const match = getRecordFieldValueByPrefix(record, rowConfig.document);
          rowData["document"] = match ? match.value : "";
        }

        const hasData = Object.keys(rowData).some(k => {
          if (k === "courseLevel" && !source.includes(k)) return false;
          if (k === "document") return false;
          return rowData[k] && String(rowData[k]).trim() !== "";
        });

        if (hasData) {
          rows.push(rowData);
        }
      });
    } 
    // Strategy B: Dynamic array source block
    else if (tableDef.source && tableDef.source.length > 0) {
      const source = tableDef.source;
      const rowData = mapRowData(columns, source, null, record);
      
      if (tableDef.document) {
        const match = getRecordFieldValueByPrefix(record, tableDef.document);
        rowData["document"] = match ? match.value : "";
      }

      const hasData = Object.keys(rowData).some(k => {
        if (k === "document") return false;
        return rowData[k] && String(rowData[k]).trim() !== "";
      });
      if (hasData) {
        rows.push(rowData);
      }
    }

    return rows;
  };

  // 1. Gather all sections and tables to render
  if (config) {
    const items = [];

    // Loop through all keys in the config to process multiple section blocks dynamically
    for (let key in config) {
      if (key === "schema_version" || key === "entity") {
        continue;
      }
      
      const val = config[key];
      if (!val || typeof val !== 'object') {
        continue;
      }

      // If the key starts with "sections" (e.g. sections, sections2, sections3)
      if (key.startsWith("sections")) {
        // Format B: key-value object of prefix arrays
        if (!Array.isArray(val)) {
          Object.entries(val).forEach(([secKey, fields]) => {
            items.push({
              type: "section_object",
              key: secKey,
              title: sectionTitles[secKey] || `Section ${secKey}`,
              fields: fields,
              sortKey: secKey
            });
          });
        }
        // Format A: array of section objects
        else {
          val.forEach((section, idx) => {
            items.push({
              type: "section_array",
              title: section.title || `Section ${idx + 1}`,
              fields: section.fields || [],
              sortKey: String(idx).padStart(3, '0')
            });
          });
        }
      }
      // If the key is a table config
      else if (val.columns && Array.isArray(val.columns)) {
        let sortKey = "Z";
        if (val.rows && val.rows.length > 0 && val.rows[0].source && val.rows[0].source.length > 0) {
          sortKey = val.rows[0].source[0].substring(0, 1);
        } else if (val.source && val.source.length > 0) {
          sortKey = val.source[0].substring(0, 1);
        }
        
        let title = "Table Details";
        if (key === "qualificationTable") title = "Academic Qualifications";
        else if (key === "additionalQualificationTable") title = "Additional Qualifications";
        else if (key === "experienceTable") title = "Work Experience Details";
        else title = key.replace(/([A-Z])/g, ' $1').replace(/^[a-z]/, c => c.toUpperCase()).trim();

        items.push({
          type: "table",
          key: key,
          title: title,
          tableDef: val,
          sortKey: sortKey
        });
      }
    }

    // Sort sections and tables alphabetically to interleave them naturally
    items.sort((a, b) => a.sortKey.localeCompare(b.sortKey));

    if (items.length > 0) {
      items.forEach(item => {
        // Render Array-based Section Layouts
        if (item.type === "section_array") {
          const sectionDiv = document.createElement("div");
          sectionDiv.classList.add("form-section");

          const title = document.createElement("h4");
          title.classList.add("form-section-title");
          title.textContent = item.title;
          sectionDiv.appendChild(title);

          const body = document.createElement("div");
          body.classList.add("form-section-body");

          item.fields.forEach(field => {
            const fieldKey = typeof field === 'string' ? field : (field.field || field.id);
            const displayLabel = typeof field === 'object' && field.label ? field.label : fieldKey;

            const match = getRecordFieldValueByPrefix(record, fieldKey) || { key: displayLabel, value: record[fieldKey] };
            const finalVal = match.value;

            const fieldDiv = document.createElement("div");
            fieldDiv.classList.add("form-field");
            // Values exceeding 60 characters span full width
            if (finalVal && String(finalVal).length > 60) {
              fieldDiv.classList.add("full-width");
            }

            const label = document.createElement("div");
            label.classList.add("form-field-label");
            label.textContent = cleanFieldLabel(match.key || displayLabel, fieldKey);

            const val = document.createElement("div");
            val.classList.add("form-field-value");
            val.innerHTML = formatFieldValue(finalVal);

            fieldDiv.appendChild(label);
            fieldDiv.appendChild(val);
            body.appendChild(fieldDiv);
          });

          sectionDiv.appendChild(body);
          container.appendChild(sectionDiv);
        }
        // Render Object-based Section Layouts
        else if (item.type === "section_object") {
          const sectionDiv = document.createElement("div");
          sectionDiv.classList.add("form-section");

          const title = document.createElement("h4");
          title.classList.add("form-section-title");
          title.textContent = item.title;
          sectionDiv.appendChild(title);

          const body = document.createElement("div");
          body.classList.add("form-section-body");

          item.fields.forEach(prefix => {
            const match = getRecordFieldValueByPrefix(record, prefix);
            const labelText = match ? cleanFieldLabel(match.key, prefix) : prefix;
            const finalVal = match ? match.value : "";

            const fieldDiv = document.createElement("div");
            fieldDiv.classList.add("form-field");
            if (finalVal && String(finalVal).length > 60) {
              fieldDiv.classList.add("full-width");
            }

            const label = document.createElement("div");
            label.classList.add("form-field-label");
            label.textContent = labelText;

            const val = document.createElement("div");
            val.classList.add("form-field-value");
            val.innerHTML = formatFieldValue(finalVal);

            fieldDiv.appendChild(label);
            fieldDiv.appendChild(val);
            body.appendChild(fieldDiv);
          });

          sectionDiv.appendChild(body);
          container.appendChild(sectionDiv);
        }
        // Render Tables Layouts
        else if (item.type === "table") {
          const rows = mapTableRows(item.tableDef, record);
          if (rows.length === 0) return;

          const sectionDiv = document.createElement("div");
          sectionDiv.classList.add("form-section");

          const title = document.createElement("h4");
          title.classList.add("form-section-title");
          title.textContent = item.title;
          sectionDiv.appendChild(title);

          const body = document.createElement("div");
          body.style.padding = "1.25rem";
          body.style.background = "#fff";

          const tableWrap = document.createElement("div");
          tableWrap.classList.add("table-wrap");

          const table = document.createElement("table");
          table.classList.add("rep-table");

          const renderCols = [...item.tableDef.columns];

          // Table Headers
          const thead = document.createElement("thead");
          const trHeader = document.createElement("tr");
          renderCols.forEach(col => {
            const th = document.createElement("th");
            th.textContent = cleanColumnHeader(col);
            trHeader.appendChild(th);
          });
          thead.appendChild(trHeader);
          table.appendChild(thead);

          // Table Rows
          const tbody = document.createElement("tbody");
          rows.forEach(rowData => {
            const tr = document.createElement("tr");
            renderCols.forEach(col => {
              const td = document.createElement("td");
              const val = rowData[col];
              const docUrl = rowData["document"];

              // Put document link on courseLevel, degreeCertificate, or first column as a fallback
              const isLinkColumn = (col === "courseLevel" || col === "degreeCertificate" || 
                                    (renderCols.indexOf(col) === 0 && !renderCols.includes("courseLevel") && !renderCols.includes("degreeCertificate")));

              if (isLinkColumn && docUrl && String(docUrl).trim().startsWith("http")) {
                const cleanUrl = String(docUrl).trim();
                const cellText = val !== undefined && val !== null && String(val).trim() !== "" ? String(val).trim() : "View";
                td.innerHTML = `<a href="${escapeHtml(cleanUrl)}" target="_blank" style="color: var(--primary); font-weight: 700; text-decoration: underline; display: inline-flex; align-items: center; gap: 4px;">
                   ${escapeHtml(cellText)}
                   <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path><polyline points="15 3 21 3 21 9"></polyline><line x1="10" y1="14" x2="21" y2="3"></line></svg>
                </a>`;
              } else {
                td.innerHTML = formatFieldValue(val);
              }
              tr.appendChild(td);
            });
            tbody.appendChild(tr);
          });
          table.appendChild(tbody);

          tableWrap.appendChild(table);
          body.appendChild(tableWrap);
          sectionDiv.appendChild(body);
          container.appendChild(sectionDiv);
        }
      });
      return;
    }
  }

  // Fallback to default flat list layout if no JSON configuration exists
  const sectionDiv = document.createElement("div");
  sectionDiv.classList.add("form-section");

  const title = document.createElement("h4");
  title.classList.add("form-section-title");
  title.textContent = "Application Form Details";
  sectionDiv.appendChild(title);

  const body = document.createElement("div");
  body.classList.add("form-section-body");

  for (let key in record) {
    const fieldDiv = document.createElement("div");
    fieldDiv.classList.add("form-field");

    const label = document.createElement("div");
    label.classList.add("form-field-label");
    label.textContent = key;

    const val = document.createElement("div");
    val.classList.add("form-field-value");
    val.innerHTML = formatFieldValue(record[key]);

    fieldDiv.appendChild(label);
    fieldDiv.appendChild(val);
    body.appendChild(fieldDiv);
  }

  sectionDiv.appendChild(body);
  container.appendChild(sectionDiv);
}
