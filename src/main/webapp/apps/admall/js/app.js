/**
 * @file app.js
 * @description Central client-side logic controller for the forms application dashboard.
 * Coordinates dataset upload, custom schema layout editors, dynamic reporting aggregates,
 * drilldown student lists, and print-ready card generators.
 * 
 * <h3>State Variables</h3>
 * <ul>
 *   <li><code>activeDatasetId</code>: Currently selected dataset primary ID.</li>
 *   <li><code>activeDatasetName</code>: Display name of the active dataset.</li>
 *   <li><code>activeFormConfig</code>: Current Form layout configuration object (parsed JSON).</li>
 *   <li><code>currentDrillDownRecords</code>: Filtered candidate records cache for search optimization.</li>
 *   <li><code>activeFilterInfo</code>: Active row/column selection context.</li>
 *   <li><code>cachedReportData</code>: Cached pivot grid structure.</li>
 * </ul>
 * 
 * <h3>Design & Architecture</h3>
 * <ul>
 *   <li><b>Parallel Promises:</b> Handles parallel updates to four separate configuration databases concurrently using <code>Promise.all</code>.</li>
 *   <li><b>Event Delegation & Intercepts:</b> Captures click triggers dynamically to route modals or download requests.</li>
 *   <li><b>Cache Preservation:</b> Prevents losing user scroll or search context on minor configurations update.</li>
 * </ul>
 */

// Global Application State Caches
let activeDatasetId = null;
let activeDatasetName = "";
let activeFormConfig = null;
let activeStudentListConfig = null;
let currentDrillDownRecords = []; // Cached filtered records for search filter operations
let activeFilterInfo = null;       // Active row/column coordinate labels
let cachedReportData = null;      // Cached pivot report data for immediate filtering
let activeRole = "";              // User role: superadmin, admission, department
let activeUsername = "";          // Display name of logged-in user
let activeLink = "";              // Scope link for department role
let activeDeptFilter = "";        // Resolved department filter name

// Helper to parse query parameters from session link or URL robustly (handling nested query parameters)
function parseLinkParams(link) {
  const params = {};
  if (!link) return params;
  
  let current = link;
  
  // Repeatedly decode URL if it is double/triple encoded
  try {
    let decoded = decodeURIComponent(current.replace(/\+/g, " "));
    while (decoded !== current) {
      current = decoded;
      decoded = decodeURIComponent(current.replace(/\+/g, " "));
    }
  } catch (e) {}

  // Extract the query part (after the last '?')
  if (current.includes("?")) {
    const parts = current.split("?");
    current = parts[parts.length - 1];
  }

  // Parse key-value pairs
  const pairs = current.split("&");
  pairs.forEach(pair => {
    const kv = pair.split("=");
    if (kv.length === 2) {
      const key = decodeURIComponent(kv[0].replace(/\+/g, " "));
      const val = decodeURIComponent(kv[1].replace(/\+/g, " "));
      params[key] = val;
      // If the value contains query params, parse them recursively
      if (val.includes("?") || val.includes("=")) {
        const sub = parseLinkParams(val);
        Object.assign(params, sub);
      }
    } else if (kv.length === 1 && (kv[0].includes("?") || kv[0].includes("="))) {
      const sub = parseLinkParams(kv[0]);
      Object.assign(params, sub);
    }
  });

  return params;
}

// Helper to handle role-based UI constraints
function handleRoleUIConstraints() {
  const uploadCard = document.getElementById("uploadCard");
  const datasetsTabLink = document.querySelector('.tab-link[data-tab="datasets-tab"]');
  const activeDatasetSelector = document.getElementById("activeDatasetSelector");
  const departmentFilterSelector = document.getElementById("departmentFilterSelector");

  if (activeRole === "superadmin") {
    if (uploadCard) uploadCard.style.display = "block";
    setupTabs();
    loadDatasetsList();
  } else if (activeRole === "admission") {
    if (uploadCard) uploadCard.style.display = "none";
    setupTabs();
    loadDatasetsList();
  } else if (activeRole === "department") {
    // Hide Upload Card and Datasets Dashboard tab link immediately
    if (uploadCard) uploadCard.style.display = "none";
    if (datasetsTabLink) datasetsTabLink.style.display = "none";

    // Hide ← Back to Dashboard button on pivot report tab
    const backToDashboardBtns = document.querySelectorAll('button[onclick*="datasets-tab"]');
    backToDashboardBtns.forEach(btn => btn.style.display = "none");

    // Detect URL parameters first using our robust parser
    const urlParams = parseLinkParams(window.location.search);
    const urlDatasetId = urlParams.datasetId;
    const urlDept = urlParams.department;

    // Parse datasetId and department from the session link as fallback/enforcement
    const linkParams = parseLinkParams(activeLink);
    const sessionDatasetId = Number(linkParams.datasetId);
    let sessionDept = linkParams.department;

    // Support links that are just raw department names (without key=value pairs)
    if (!sessionDept && activeLink && !activeLink.includes("=") && !activeLink.includes("?")) {
      sessionDept = activeLink;
    }

    // We prioritize URL query params if present, but for a department user, we restrict/validate to session values for security
    const forcedDatasetId = sessionDatasetId || Number(urlDatasetId);
    const forcedDept = sessionDept || urlDept || activeUsername;

    if (forcedDept) {
      if (forcedDatasetId) {
        activeDatasetId = forcedDatasetId;
        
        // Normalize URL in history to be clean and simple
        const urlTab = urlParams.tab || "report-tab";
        const cleanUrl = `${window.location.origin}${window.location.pathname}?tab=${urlTab}&datasetId=${forcedDatasetId}&department=${encodeURIComponent(forcedDept)}`;
        window.history.replaceState({}, "", cleanUrl);

        // Auto-hide dataset and department selectors
        if (activeDatasetSelector) activeDatasetSelector.style.display = "none";
        if (departmentFilterSelector) departmentFilterSelector.style.display = "none";

        setupTabs();
        // Load configurations and pivot report
        loadFormConfig(activeDatasetId);
        loadStudentListConfig(activeDatasetId);
        loadReport(activeDatasetId, forcedDept);

        switchTab(urlTab);
      } else {
        // If no dataset ID is configured, allow the department user to select a dataset
        if (activeDatasetSelector) activeDatasetSelector.style.display = "inline-block";
        if (departmentFilterSelector) departmentFilterSelector.style.display = "none";

        setupTabs();
        loadDatasetsList();
        
        // Default to report tab
        switchTab("report-tab");
      }
    } else {
      alert("Error: Department configuration missing or invalid. Please contact Super Admin.");
    }
  }
}

// ── Page Initialization ──
document.addEventListener("DOMContentLoaded", () => {
  // Fetch session details from server
  fetch("api/session")
    .then(resp => {
      if (!resp.ok) throw new Error("Session check failed");
      return resp.json();
    })
    .then(session => {
      if (!session.loggedIn) {
        window.location.href = "login.html";
        return;
      }

      activeRole = session.role ? session.role.trim() : "";
      activeUsername = session.username;
      activeLink = session.link;

      // Perform role-based redirection on the individual pages
      const path = window.location.pathname.toLowerCase();
      const isAdmissionHtml = path.endsWith("admission.html");
      const isIndexHtml = path.endsWith("index.html") || path.endsWith("/admall") || path.endsWith("/admall/");
      const urlParams = parseLinkParams(window.location.search);


      if (activeRole === "department") {
        if (!isIndexHtml || !urlParams.department) {
          window.location.href = "department.html";
          return;
        }
      } else if (activeRole === "admission" && !isAdmissionHtml) {
        window.location.href = "admission.html";
        return;
      } else if (activeRole === "superadmin" && !isIndexHtml) {
        window.location.href = "index.html";
        return;
      }

      if (activeRole === "department") {
        const linkParams = parseLinkParams(activeLink);
        activeDeptFilter = linkParams.department || "";
        if (!activeDeptFilter && activeLink && !activeLink.includes("=") && !activeLink.includes("?")) {
          activeDeptFilter = activeLink;
        }
        if (!activeDeptFilter) {
          activeDeptFilter = activeUsername;
        }
      }

      // Show and populate user top menu
      const userMenuTop = document.getElementById("userMenuTop");
      const userDisplayName = document.getElementById("userDisplayName");
      if (userMenuTop && userDisplayName) {
        userDisplayName.textContent = `${activeUsername} (${activeRole.toUpperCase()})`;
        userMenuTop.style.display = "flex";
      }

      // Handle role-based UI restriction
      handleRoleUIConstraints();
    })
    .catch(err => {
      console.error(err);
      window.location.href = "login.html";
    });
});

// ── Tab Management ──
/**
 * Sets up click event listeners on tab navigation headers.
 * Intercepts transitions to verify if a dataset has been selected first.
 */
function setupTabs() {
  const links = document.querySelectorAll(".tab-link");

  links.forEach(link => {
    link.addEventListener("click", (e) => {
      e.preventDefault();
      const targetTabId = link.getAttribute("data-tab");

      // Guard check: user must select a dataset before checking report or lists
      if ((targetTabId === "report-tab" || targetTabId === "drilldown-tab") && !activeDatasetId) {
        alert("Please select or upload a dataset first.");
        return;
      }

      switchTab(targetTabId);
    });
  });
}

/**
 * Programmatically switches the active tab panel.
 * 
 * @param {string} tabId The data-tab ID (e.g. "report-tab", "drilldown-tab").
 */
function switchTab(tabId) {
  const links = document.querySelectorAll(".tab-link");
  const contents = document.querySelectorAll(".tab-content");

  links.forEach(l => {
    if (l.getAttribute("data-tab") === tabId) {
      l.classList.add("active");
    } else {
      l.classList.remove("active");
    }
  });

  contents.forEach(c => {
    if (c.id === tabId) {
      c.classList.add("active");
    } else {
      c.classList.remove("active");
    }
  });

  // Update URL tab parameter
  const url = new URL(window.location);
  url.searchParams.set("tab", tabId);
  if (tabId === "datasets-tab" || tabId === "report-tab") {
    url.searchParams.delete("rowField");
    url.searchParams.delete("rowValue");
    url.searchParams.delete("columnField");
    url.searchParams.delete("columnValue");
  }
  window.history.replaceState({}, "", url);

  if (tabId === "drilldown-tab") {
    const urlParams = new URLSearchParams(window.location.search);
    const rowField = urlParams.get("rowField");
    const rowValue = urlParams.get("rowValue");
    const columnField = urlParams.get("columnField");
    const columnValue = urlParams.get("columnValue");
    if (!rowField || !rowValue || !columnField || !columnValue) {
      loadAllDrillDownRecords();
    }
  }
}

function loadAllDrillDownRecords() {
  document.getElementById("drillDownBadge").textContent = "FILTER: ALL";
  document.getElementById("drillDownTitle").textContent = "All Records";
  
  document.getElementById("noDrillDownSelected").style.display = "none";
  document.getElementById("drillDownListCard").style.display = "block";

  fetch(`api/drilldown?datasetId=${activeDatasetId}`)
    .then(resp => {
      if (!resp.ok) throw new Error("Failed to load records.");
      return resp.json();
    })
    .then(records => {
      currentDrillDownRecords = records;
      renderDrillDownTable(records);
    })
    .catch(err => {
      alert("Error: " + err.message);
    });
}

/**
 * Sets visibility of admin-only tools (like file upload cards and configuration controls)
 * based on the active user role parsed from URL query parameters.
 */
function applyRoleVisibility() {
  const uploadCard = document.getElementById("uploadCard");
  const formConfigCard = document.getElementById("formConfigCard");
  
  if (activeRole === "user") {
    if (uploadCard) uploadCard.style.display = "none";
    if (formConfigCard) {
      formConfigCard.style.setProperty("display", "none", "important");
    }
  } else {
    if (uploadCard) uploadCard.style.display = "block";
  }
}

// ── Datasets Dashboard (Tab 1) ──
/**
 * Calls the datasets list endpoint to populate the dashboard grid and selector options.
 */
function loadDatasetsList(refreshOnly) {
  const alertDiv = document.getElementById("listAlert");
  alertDiv.style.display = "none";

  fetch("api/datasets?action=list")
    .then(resp => {
      if (!resp.ok) throw new Error("Failed to load datasets list.");
      return resp.json();
    })
    .then(data => {
      const tbody = document.getElementById("datasetsTableBody");
      const selector = document.getElementById("activeDatasetSelector");
      
      tbody.innerHTML = "";
      selector.innerHTML = '<option value="">-- Select Dataset --</option>';

      if (data.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="no-data-msg">No datasets uploaded yet.</td></tr>';
        return;
      }

      data.forEach((ds, idx) => {
        // Add option to report tab dataset selector dropdown
        const option = document.createElement("option");
        option.value = ds.id;
        option.textContent = `${ds.name} (${ds.rowCount} records)`;
        if (activeDatasetId && Number(activeDatasetId) === Number(ds.id)) {
          option.selected = true;
          activeDatasetName = ds.name; // Save the name
        }
        selector.appendChild(option);

        const tr = document.createElement("tr");
        let actionsHtml = "";
        if (activeRole === "superadmin") {
          actionsHtml += `<button class="btn btn-outline btn-sm" onclick="viewCurrentFormConfig(${ds.id})">View Current Config</button>`;
          actionsHtml += `<button class="btn btn-green btn-sm" onclick="updateCsv(${ds.id}, '${escapeJs(ds.name)}')">Update CSV</button>`;
        }
        actionsHtml += `<button class="btn btn-primary btn-sm" onclick="selectDataset(${ds.id}, '${escapeJs(ds.name)}')">View Report</button>`;
        if (activeRole === "superadmin") {
          actionsHtml += `<button class="btn btn-danger btn-sm" onclick="deleteDataset(${ds.id})">Delete</button>`;
        }
        
        tr.innerHTML = `
          <td>${idx + 1}</td>
          <td><strong>${escapeHtml(ds.name)}</strong></td>
          <td><span class="badge badge-info">${ds.rowCount}</span></td>
          <td>${ds.uploadedAt ? ds.uploadedAt.replace(/\.0$/, "") : ""}</td>
          <td>
            <div style="display:flex; flex-wrap:wrap; gap:0.5rem;">
              ${actionsHtml}
            </div>
          </td>
        `;
        tbody.appendChild(tr);
      });

      // If refreshOnly is true, just refresh the table — don't switch tabs or load reports
      if (refreshOnly) return;

      // If we have an activeDatasetId from the URL on initial load, trigger loading the config & report
      if (activeDatasetId && data.some(ds => Number(ds.id) === Number(activeDatasetId))) {
        loadFormConfig(activeDatasetId);
        loadStudentListConfig(activeDatasetId);
        loadReport(activeDatasetId);

        const urlParams = new URLSearchParams(window.location.search);
        let urlTab = urlParams.get("tab");
        const rowField = urlParams.get("rowField");
        const rowValue = urlParams.get("rowValue");
        const columnField = urlParams.get("columnField");
        const columnValue = urlParams.get("columnValue");

        // If filter coordinates are in the URL but tab is unspecified, default to drilldown-tab
        if (!urlTab && rowField && rowValue && columnField && columnValue) {
          urlTab = "drilldown-tab";
        }

        if (urlTab && (urlTab === "datasets-tab" || urlTab === "report-tab" || urlTab === "drilldown-tab")) {
          switchTab(urlTab);
        } else {
          switchTab("report-tab");
        }

        // Auto-restore drilldown cell filter only if active tab is drilldown-tab
        if (urlTab === "drilldown-tab") {
          if (rowField && rowValue && columnField && columnValue) {
            handleCellClick(rowField, rowValue, columnField, columnValue);
          }
        }
      } else {
        // Fallback for department user if no dataset is active: auto-select the latest dataset
        if (activeRole === "department" && data.length > 0) {
          activeDatasetId = data[0].id;
          activeDatasetName = data[0].name;
          selector.value = activeDatasetId;
          
          loadFormConfig(activeDatasetId);
          loadStudentListConfig(activeDatasetId);
          loadReport(activeDatasetId);
          
          switchTab("report-tab");
        } else {
          // Default to datasets-tab if no dataset is active
          const urlParams = new URLSearchParams(window.location.search);
          const urlTab = urlParams.get("tab");
          if (urlTab && (urlTab === "datasets-tab" || urlTab === "report-tab" || urlTab === "drilldown-tab")) {
            switchTab(urlTab);
          } else {
            switchTab("datasets-tab");
          }
        }
      }
    })
    .catch(err => {
      showAlert("listAlert", err.message, "error");
    });
}

/**
 * Gathers form fields, extracts CSV file contents, and initiates upload requests.
 * Uses FormData API to support multi-part transfer encoding.
 * 
 * @param {Event} event The submit trigger event.
 */
function handleUpload(event) {
  event.preventDefault();
  const alertDiv = document.getElementById("uploadAlert");
  alertDiv.style.display = "none";

  const datasetName = document.getElementById("datasetName").value;
  const fileInput = document.getElementById("csvFile");
  const file = fileInput.files[0];

  const formConfigFile = document.getElementById("formConfigFile").files[0];
  const summaryConfigFile = document.getElementById("summaryConfigFile").files[0];
  const studentListConfigFile = document.getElementById("studentListConfigFile").files[0];

  if (!datasetName.trim() || !file || !formConfigFile || !summaryConfigFile || !studentListConfigFile) {
    showAlert("uploadAlert", "Dataset name, CSV file, Form Layout, Report, and Student List configs are required.", "error");
    return;
  }

  const formData = new FormData();
  formData.append("datasetName", datasetName);
  formData.append("file", file);
  formData.append("formConfigFile", formConfigFile);
  formData.append("summaryConfigFile", summaryConfigFile);
  formData.append("studentListConfigFile", studentListConfigFile);



  const uploadBtn = document.getElementById("uploadBtn");
  uploadBtn.disabled = true;
  uploadBtn.innerHTML = '<span class="spinner"></span> Processing CSV...';

  fetch("api/upload", {
    method: "POST",
    body: formData
  })
  .then(resp => resp.json().then(data => ({ status: resp.status, body: data })))
  .then(res => {
    if (res.status !== 200) {
      throw new Error(res.body.error || "Failed to upload dataset.");
    }
    
    showAlert("uploadAlert", "Dataset uploaded and processed successfully!", "success");
    document.getElementById("uploadForm").reset();
    
    // Refresh the available datasets list without changing tabs or redirecting
    loadDatasetsList(true);
  })
  .catch(err => {
    showAlert("uploadAlert", err.message, "error");
  })
  .finally(() => {
    uploadBtn.disabled = false;
    uploadBtn.textContent = "Upload & Convert Dataset";
  });
}

/**
 * Activates a dataset, triggering configuration loads and rendering its summary report.
 * 
 * @param {number|string} id Dataset primary key ID.
 * @param {string} name Custom name label.
 */
function selectDataset(id, name) {
  activeDatasetId = id;
  activeDatasetName = name;

  // Update dropdown value in Report tab
  const selector = document.getElementById("activeDatasetSelector");
  selector.value = id;

  // Fire queries to load configuration parameters and build pivot grid
  loadFormConfig(id);
  loadStudentListConfig(id);
  loadReport(id);

  // Update URL parameters
  const url = new URL(window.location);
  url.searchParams.set("datasetId", id);
  url.searchParams.set("tab", "report-tab");
  // Clear old department filter when switching dataset
  url.searchParams.delete("department");
  // Clear old cell filter parameters as well
  url.searchParams.delete("rowField");
  url.searchParams.delete("rowValue");
  url.searchParams.delete("columnField");
  url.searchParams.delete("columnValue");
  window.history.replaceState({}, "", url);

  // Focus viewport on report tab
  switchTab("report-tab");
}

/**
 * Dispatches a delete request to remove a dataset catalog metadata and candidate rows.
 * 
 * @param {number|string} id Target dataset ID.
 */
function deleteDataset(id) {
  if (!confirm("Are you sure you want to delete this dataset? All associated records will be permanently removed.")) {
    return;
  }

  fetch(`api/datasets?action=delete&id=${id}`, {
    method: "POST"
  })
  .then(resp => resp.json())
  .then(res => {
    if (res.success) {
      // If the deleted dataset is currently active, clear global state pointers
      if (Number(activeDatasetId) === Number(id)) {
        activeDatasetId = null;
        activeDatasetName = "";
        activeFormConfig = null;
        document.getElementById("reportContainer").style.display = "none";
        document.getElementById("noReportSelected").style.display = "block";
        document.getElementById("noDrillDownSelected").style.display = "block";
        const formConfigCard = document.getElementById("formConfigCard");
        if (formConfigCard) {
          formConfigCard.style.display = "none";
        }
        document.getElementById("drillDownListCard").style.display = "none";

        // Clear URL parameters
        const url = new URL(window.location);
        url.searchParams.delete("datasetId");
        url.searchParams.delete("tab");
        url.searchParams.delete("department");
        window.history.replaceState({}, "", url);
      }
      loadDatasetsList(true);
    } else {
      alert("Error: " + res.error);
    }
  })
  .catch(err => alert("Connection error: " + err.message));
}

// ── Report & Pivot Engine (Tab 2) ──
/**
 * Loads the pivot report summary counts from ReportServlet.
 * 
 * @param {number|string} datasetId Target dataset ID.
 */
function loadReport(datasetId, forcedDept) {
  if (!datasetId) {
    document.getElementById("reportContainer").style.display = "none";
    document.getElementById("noReportSelected").style.display = "block";
    const deptSelect = document.getElementById("departmentFilterSelector");
    if (deptSelect) deptSelect.style.display = "none";
    // Removed old attendance button toggle

    // Clear URL parameters when dataset is deselected
    const url = new URL(window.location);
    url.searchParams.delete("datasetId");
    url.searchParams.delete("department");
    window.history.replaceState({}, "", url);
    return;
  }

  activeDatasetId = datasetId;
  const alertDiv = document.getElementById("reportAlert");
  alertDiv.style.display = "none";
  
  document.getElementById("noReportSelected").style.display = "none";
  document.getElementById("reportContainer").style.display = "block";

  // Update URL parameter without reloading page
  const url = new URL(window.location);
  url.searchParams.set("datasetId", datasetId);
  window.history.replaceState({}, "", url);

  fetch(`api/report?datasetId=${datasetId}`)
    .then(resp => {
      if (!resp.ok) throw new Error("Failed to load report.");
      return resp.json();
    })
    .then(data => {
      cachedReportData = data;
      document.getElementById("reportTitleName").textContent = data.reportName;
      
      // Populate department filter selector options dynamically
      const filterSelect = document.getElementById("departmentFilterSelector");
      filterSelect.innerHTML = '<option value="all">All Departments</option>';
      
      // Extract unique row values (departments) for filter selector
      const uniqueDepts = [...new Set(data.rows.map(row => row.rowName))].sort();
      uniqueDepts.forEach(dept => {
        if (dept && dept.trim() !== "") {
          const opt = document.createElement("option");
          opt.value = dept;
          opt.textContent = dept;
          filterSelect.appendChild(opt);
        }
      });
      
      let initialFilter = "all";
      const resolvedDept = forcedDept || (activeRole === "department" ? activeDeptFilter : "");
      if (activeRole === "department" && resolvedDept) {
        initialFilter = resolvedDept;
        filterSelect.style.display = "none";
      } else {
        // Check for pre-selected department in URL parameter
        const urlParams = new URLSearchParams(window.location.search);
        const urlDept = urlParams.get("department");
        
        // Show filter selector
        filterSelect.style.display = "inline-block";
        
        if (urlDept && (urlDept === "all" || uniqueDepts.includes(urlDept))) {
          initialFilter = urlDept;
        }
        filterSelect.value = initialFilter;
      }
      
      // Render report matrix
      renderPivotTable(data, initialFilter);
      
      // Cache form configs
      loadFormConfig(datasetId);
      loadStudentListConfig(datasetId);
    })
    .catch(err => {
      showAlert("reportAlert", err.message, "error");
    });
}

/**
 * Builds and renders the aggregate pivot matrix table dynamically.
 * Only the numeric count values are configured with click listeners to trigger drilldowns.
 * 
 * @param {Object} data Pivot report JSON structure.
 * @param {string} filterDept Filter value ("all" or department name).
 */
function renderPivotTable(data, filterDept = "all") {
  const headerTr = document.getElementById("pivotTableHeader");
  const tbody = document.getElementById("pivotTableBody");
  
  headerTr.innerHTML = "";
  tbody.innerHTML = "";

  const rowField = data.rowField;
  const columnField = data.columnField;
  const columns = data.columns;

  // 1. Generate Headers: [rowField, ...columns, "Total"]
  const thRowField = document.createElement("th");
  // Clean prefixes (e.g. "A2. ") from display label
  thRowField.textContent = rowField.replace(/^[A-Z][0-9]+(?:\.[0-9]+)?\.?\s*/i, "").trim();
  headerTr.appendChild(thRowField);

  columns.forEach(col => {
    const th = document.createElement("th");
    th.textContent = col;
    headerTr.appendChild(th);
  });

  const thTotal = document.createElement("th");
  thTotal.textContent = "Total";
  headerTr.appendChild(thTotal);

  const thAttendance = document.createElement("th");
  thAttendance.textContent = "Attendance Sheet";
  thAttendance.style.textAlign = "center";
  headerTr.appendChild(thAttendance);

  // Filter rows by department if specified
  const rowsToRender = filterDept === "all" 
    ? data.rows 
    : data.rows.filter(row => row.rowName === filterDept);

  // 2. Generate Rows
  rowsToRender.forEach(row => {
    const tr = document.createElement("tr");
    
    // Row header label (e.g. CSE)
    const tdRowName = document.createElement("td");
    tdRowName.innerHTML = `<strong>${escapeHtml(row.rowName)}</strong>`;
    tr.appendChild(tdRowName);

    // Column cell counts - numeric elements are clickable to view matching list
    columns.forEach(col => {
      const td = document.createElement("td");
      const count = row.values[col] || 0;
      td.textContent = count;
      td.classList.add("clickable-cell");
      td.onclick = () => handleCellClick(rowField, row.rowName, columnField, col);
      tr.appendChild(td);
    });

    // Row total column cell
    const tdRowTotal = document.createElement("td");
    tdRowTotal.textContent = row.values["Total"] || 0;
    tdRowTotal.classList.add("clickable-cell", "total-cell");
    tdRowTotal.onclick = () => handleCellClick(rowField, row.rowName, columnField, "Total");
    tr.appendChild(tdRowTotal);

    // Attendance Sheet column cell with department-specific filter
    const tdAttendance = document.createElement("td");
    tdAttendance.style.textAlign = "center";
    const btn = document.createElement("button");
    btn.className = "btn btn-outline btn-sm";
    btn.textContent = "Print";
    btn.onclick = () => {
      window.open(`attendance.html?datasetId=${activeDatasetId}&department=${encodeURIComponent(row.rowName)}&role=${activeRole}`, '_blank');
    };
    tdAttendance.appendChild(btn);
    tr.appendChild(tdAttendance);

    tbody.appendChild(tr);
  });

  // 3. Generate Grand Totals Row
  const trTotals = document.createElement("tr");
  trTotals.style.borderTop = "2px solid var(--primary)";
  
  const tdTotalLabel = document.createElement("td");
  tdTotalLabel.innerHTML = `<strong>Total</strong>`;
  tdTotalLabel.classList.add("total-cell");
  trTotals.appendChild(tdTotalLabel);

  // Compute column totals based on rendered rows
  const totalsMap = {};
  columns.forEach(col => {
    let sum = 0;
    rowsToRender.forEach(row => {
      sum += row.values[col] || 0;
    });
    totalsMap[col] = sum;
  });

  let grandSum = 0;
  rowsToRender.forEach(row => {
    grandSum += row.values["Total"] || 0;
  });
  totalsMap["Total"] = grandSum;

  columns.forEach(col => {
    const td = document.createElement("td");
    td.textContent = totalsMap[col];
    td.classList.add("clickable-cell", "total-cell");
    td.onclick = () => {
      if (filterDept === "all") {
        handleCellClick(rowField, "Total", columnField, col);
      } else {
        handleCellClick(rowField, filterDept, columnField, col);
      }
    };
    trTotals.appendChild(td);
  });

  const tdGrandTotal = document.createElement("td");
  tdGrandTotal.textContent = totalsMap["Total"];
  tdGrandTotal.classList.add("clickable-cell", "total-cell");
  tdGrandTotal.style.backgroundColor = "var(--primary-light)";
  tdGrandTotal.onclick = () => {
    if (filterDept === "all") {
      handleCellClick(rowField, "Total", columnField, "Total");
    } else {
      handleCellClick(rowField, filterDept, columnField, "Total");
    }
  };
  trTotals.appendChild(tdGrandTotal);

  // Blank total cell for Attendance column on the Grand Totals row
  const tdTotalsAttendance = document.createElement("td");
  tdTotalsAttendance.classList.add("total-cell");
  trTotals.appendChild(tdTotalsAttendance);

  tbody.appendChild(trTotals);
}

/**
 * Filter handler tied to department selection dropdown.
 * Reveals or hides the "Print Attendance Sheet" button.
 * 
 * @param {string} filterVal Selected department or "all".
 */
function applyDepartmentFilter(filterVal) {
  // Update URL parameter without reloading page
  const url = new URL(window.location);
  if (filterVal && filterVal !== "all") {
    url.searchParams.set("department", filterVal);
  } else {
    url.searchParams.delete("department");
  }
  window.history.replaceState({}, "", url);

  if (cachedReportData) {
    renderPivotTable(cachedReportData, filterVal);
  }
}

// ── Drill-down Support (Tab 3) ──
/**
 * Triggers drill-down request matching the row and column coordinate values.
 * Loads matching rows and updates view focus to Student List tab.
 * 
 * @param {string} rowField Field identifier used on rows.
 * @param {string} rowValue Target cell row value.
 * @param {string} columnField Field identifier used on columns.
 * @param {string} columnValue Target cell column value.
 */
function handleCellClick(rowField, rowValue, columnField, columnValue) {
  activeFilterInfo = { rowField, rowValue, columnField, columnValue };
  
  // Set badge and description layout content
  const badgeText = `${rowField.toUpperCase()}:${rowValue} | ${columnField.toUpperCase()}:${columnValue}`;
  document.getElementById("drillDownBadge").textContent = badgeText;
  document.getElementById("drillDownTitle").textContent = `Drill-Down: ${rowValue} × ${columnValue}`;
  
  document.getElementById("noDrillDownSelected").style.display = "none";
  document.getElementById("drillDownListCard").style.display = "block";

  // Update URL parameters to persist state
  const url = new URL(window.location);
  url.searchParams.set("rowField", rowField);
  url.searchParams.set("rowValue", rowValue);
  url.searchParams.set("columnField", columnField);
  url.searchParams.set("columnValue", columnValue);
  url.searchParams.set("tab", "drilldown-tab");
  window.history.replaceState({}, "", url);

  fetch(`api/drilldown?datasetId=${activeDatasetId}&rowField=${encodeURIComponent(rowField)}&rowValue=${encodeURIComponent(rowValue)}&columnField=${encodeURIComponent(columnField)}&columnValue=${encodeURIComponent(columnValue)}`)
    .then(resp => {
      if (!resp.ok) throw new Error("Failed to load drill-down records.");
      return resp.json();
    })
    .then(records => {
      currentDrillDownRecords = records;
      renderDrillDownTable(records);
      switchTab("drilldown-tab");
    })
    .catch(err => {
      alert("Error: " + err.message);
    });
}

/**
 * Renders the candidate details grid (Student List) matching filters.
 * 
 * @param {Array<Object>} records Filtered student candidate records.
 */
function renderDrillDownTable(records) {
  const headerTr = document.getElementById("drillDownTableHeader");
  const tbody = document.getElementById("drillDownTableBody");
  const emptyMsg = document.getElementById("drillDownEmptyMsg");

  headerTr.innerHTML = "";
  tbody.innerHTML = "";

  if (records.length === 0) {
    emptyMsg.style.display = "block";
    return;
  }
  emptyMsg.style.display = "none";

  /**
   * Helper function to resolve fields by prefix (e.g. "A1" matches "A1. Name of School")
   */
  function getValByPrefix(record, prefix) {
    if (!record || !prefix) return "";
    const lowerPrefix = prefix.toLowerCase().trim();
    for (let key in record) {
      const lowerKey = key.toLowerCase().trim();
      if (lowerKey === lowerPrefix || lowerKey.startsWith(lowerPrefix)) {
        return record[key] || "";
      }
    }
    return "";
  }

  /**
   * Tries to resolve candidate name using B1 prefix or fallbacks.
   */
  function getCandidateName(record) {
    const b1Val = getValByPrefix(record, "B1");
    if (b1Val) return b1Val;
    for (let key in record) {
      const kLower = key.toLowerCase();
      if (kLower.includes("name") && !kLower.includes("department") && !kLower.includes("school") && !kLower.includes("institute") && !kLower.includes("college")) {
        return record[key] || "";
      }
    }
    return "";
  }

  /**
   * Tries to resolve candidate email address.
   */
  function getCandidateEmail(record) {
    for (let key in record) {
      if (key.toLowerCase().includes("email")) {
        return record[key] || "";
      }
    }
    return "";
  }

  /**
   * Tries to resolve candidate department value.
   */
  function getCandidateDepartment(record) {
    const a2Val = getValByPrefix(record, "A2");
    if (a2Val) return a2Val;
    for (let key in record) {
      if (key.toLowerCase().includes("department")) {
        return record[key] || "";
      }
    }
    return "";
  }

  // Set headers and cell values dynamically based on student_list_config
  let columns = [];
  if (activeStudentListConfig && activeStudentListConfig.studentList && Array.isArray(activeStudentListConfig.studentList.columns)) {
    columns = activeStudentListConfig.studentList.columns;
  } else {
    columns = [
      { "label": "Candidate ID", "source": "id" },
      { "label": "Name", "source": "B1" },
      { "label": "Email Address", "source": "Email Address" },
      { "label": "Department", "source": "A2" }
    ];
  }

  columns.forEach(col => {
    const th = document.createElement("th");
    th.textContent = col.label;
    headerTr.appendChild(th);
  });

  const thActions = document.createElement("th");
  thActions.textContent = "Actions";
  thActions.style.textAlign = "center";
  headerTr.appendChild(thActions);

  function resolveDrillDownCellValue(entry, columnConfig) {
    const record = entry.record;
    const source = columnConfig.source || "";

    if (source.toLowerCase() === "id" || source.toLowerCase() === "candidateid") {
      const customId = getValByPrefix(record, "candidateId");
      return customId && customId.trim() !== "" ? customId : `#${entry.id}`;
    }

    const match = getValByPrefix(record, source);
    if (match && match.trim() !== "") return match;

    const keyword = (columnConfig.label || source).toLowerCase();
    for (let key in record) {
      const keyLower = key.toLowerCase();
      if (keyLower.includes(keyword)) {
        return record[key] || "";
      }
    }
    return "—";
  }

  records.forEach(entry => {
    const tr = document.createElement("tr");
    tr.style.cursor = "pointer";
    tr.onclick = () => window.open(`view.html?id=${entry.id}&role=${activeRole}`, '_blank');

    columns.forEach(col => {
      const td = document.createElement("td");
      td.textContent = resolveDrillDownCellValue(entry, col);
      tr.appendChild(td);
    });

    const tdActions = document.createElement("td");
    tdActions.innerHTML = `
      <div style="display: flex; gap: 0.5rem; justify-content: center;">
        <button class="btn btn-primary btn-sm" onclick="event.stopPropagation(); window.open('view.html?id=${entry.id}&role=${activeRole}', '_blank')">View Form</button>
        <button class="btn btn-green btn-sm" onclick="event.stopPropagation(); window.open('admitcard.html?id=${entry.id}&datasetId=${activeDatasetId}&role=${activeRole}', '_blank')">View Admit Card</button>
      </div>
    `;
    tr.appendChild(tdActions);

    tbody.appendChild(tr);
  });
}

/**
 * Configuration Manager Popup.
 * Queries all four active JSON configurations in parallel, opens a pop-up window,
 * writes HTML/CSS source containing a tabbed JSON editor workspace, validates syntax dynamically,
 * and posts updates back to DatasetServlet.
 */
function viewCurrentFormConfig(datasetId) {
  const targetId = datasetId || activeDatasetId;
  if (!targetId) return;

  // Open popup immediately and synchronously to prevent browser from blocking it
  const win = window.open("", "_blank", "width=750,height=700");
  if (!win) {
    alert("Popup blocked! Please allow popups for this site to edit configurations.");
    return;
  }

  // Write a loading state to the popup window
  win.document.write(`
    <html>
    <head>
      <title>Loading Configurations...</title>
      <style>
        body {
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          background-color: #f8fafc;
          display: flex;
          justify-content: center;
          align-items: center;
          height: 100vh;
          margin: 0;
        }
        .loader-card {
          background: white;
          padding: 2.5rem;
          border-radius: 12px;
          box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
          text-align: center;
          max-width: 400px;
        }
        h3 { color: #0d2d77; margin: 0 0 10px; }
        p { color: #64748b; font-size: 14px; margin: 0; }
      </style>
    </head>
    <body>
      <div class="loader-card">
        <h3>Loading Configurations</h3>
        <p>Fetching JSON schemas from the database...</p>
      </div>
    </body>
    </html>
  `);
  win.document.close();

  Promise.all([
    fetch(`api/datasets?action=getFormConfig&datasetId=${targetId}`).then(r => r.json()),
    fetch(`api/datasets?action=getSummaryConfig&datasetId=${targetId}`).then(r => r.json()),
    fetch(`api/datasets?action=getAttendanceConfig&datasetId=${targetId}`).then(r => r.json()),
    fetch(`api/datasets?action=getAdmitConfig&datasetId=${targetId}`).then(r => r.json()),
    fetch(`api/datasets?action=getStudentListConfig&datasetId=${targetId}`).then(r => r.json())
  ])
  .then(([formConfig, summaryConfig, attendanceConfig, admitConfig, studentListConfig]) => {
    // Stringify structures cleanly for text editors
    const isFormEmpty = !formConfig || Object.keys(formConfig).length === 0;
    const initialFormJson = isFormEmpty ? "{\n  \"sections\": {},\n  \"tables\": {}\n}" : JSON.stringify(formConfig, null, 2);
    
    const isSummaryEmpty = !summaryConfig || Object.keys(summaryConfig).length === 0;
    const initialSummaryJson = isSummaryEmpty ? "{\n}" : JSON.stringify(summaryConfig, null, 2);

    const isAttendanceEmpty = !attendanceConfig || Object.keys(attendanceConfig).length === 0;
    const initialAttendanceJson = isAttendanceEmpty ? "{\n}" : JSON.stringify(attendanceConfig, null, 2);

    const isAdmitEmpty = !admitConfig || Object.keys(admitConfig).length === 0;
    const initialAdmitJson = isAdmitEmpty ? "{\n}" : JSON.stringify(admitConfig, null, 2);

    const isStudentListEmpty = !studentListConfig || Object.keys(studentListConfig).length === 0;
    const initialStudentListJson = isStudentListEmpty ? "{\n}" : JSON.stringify(studentListConfig, null, 2);

    // Initialize child popup window layout
    win.document.open();
    win.document.write(`
      <html>
      <head>
        <title>Edit Dataset Configurations</title>
        <style>
          body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background-color: #f8fafc;
            margin: 0;
            padding: 20px;
            display: flex;
            flex-direction: column;
            height: calc(100vh - 40px);
            box-sizing: border-box;
          }
          h3 {
            color: #0d2d77;
            margin: 0 0 10px 0;
            font-size: 18px;
            font-weight: 700;
          }
          .subtitle {
            color: #64748b;
            font-size: 13px;
            margin-bottom: 15px;
          }
          .tab-bar {
            display: flex;
            border-bottom: 2px solid #e2e8f0;
            margin-bottom: 15px;
            gap: 5px;
          }
          .tab-btn {
            padding: 8px 16px;
            background: none;
            border: none;
            border-bottom: 2px solid transparent;
            font-size: 13px;
            font-weight: 600;
            color: #64748b;
            cursor: pointer;
            transition: all 0.2s;
            margin-bottom: -2px;
          }
          .tab-btn:hover {
            color: #0d2d77;
          }
          .tab-btn.active {
            color: #0d2d77;
            border-bottom-color: #0d2d77;
          }
          textarea {
            flex: 1;
            width: 100%;
            font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
            font-size: 13px;
            padding: 15px;
            border: 1px solid #cbd5e1;
            border-radius: 6px;
            background-color: #ffffff;
            resize: none;
            outline: none;
            box-sizing: border-box;
            line-height: 1.5;
          }
          textarea:focus {
            border-color: #0d2d77;
            box-shadow: 0 0 0 3px rgba(13, 45, 119, 0.1);
          }
          .btn-bar {
            display: flex;
            justify-content: flex-end;
            gap: 10px;
            margin-top: 15px;
          }
          .btn {
            padding: 8px 16px;
            border-radius: 6px;
            font-size: 13px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s ease;
            border: none;
          }
          .btn-primary {
            background-color: #0d2d77;
            color: #ffffff;
          }
          .btn-primary:hover {
            background-color: #081d4f;
          }
          .btn-outline {
            background-color: transparent;
            border: 1px solid #cbd5e1;
            color: #64748b;
          }
          .btn-outline:hover {
            background-color: #f1f5f9;
          }
          .alert {
            padding: 10px;
            border-radius: 6px;
            font-size: 13px;
            margin-bottom: 15px;
            display: none;
          }
          .alert-error {
            background-color: #fee2e2;
            color: #ef4444;
            border: 1px solid #fecaca;
          }
          .alert-success {
            background-color: #dcfce7;
            color: #10b981;
            border: 1px solid #bbf7d0;
          }
        </style>
      </head>
      <body>
        <h3>Edit Dataset Configurations</h3>
        <div class="subtitle">Select a tab below to modify its configuration JSON schema. Saving will update the database and refresh the dashboard.</div>
        
        <div class="tab-bar">
          <button class="tab-btn active" onclick="switchTab('form')">Form Layout</button>
          <button class="tab-btn" onclick="switchTab('summary')">Report</button>
          <button class="tab-btn" onclick="switchTab('attendance')">Attendance Sheet</button>
          <button class="tab-btn" onclick="switchTab('admit')">Admit Card</button>
          <button class="tab-btn" onclick="switchTab('studentList')">Student List</button>
        </div>

        <div id="alert" class="alert alert-error"></div>
        <textarea id="jsonTextarea" spellcheck="false"></textarea>
        
        <div class="btn-bar">
          <button class="btn btn-outline" onclick="window.close()">Cancel</button>
          <button class="btn btn-primary" id="saveBtn" onclick="saveAllConfigs()">Save & Apply</button>
        </div>

        <script>
          let currentTab = 'form';
          // Cache JSON strings in memory context
          const configs = {
            form: \`${escapeJs(initialFormJson)}\`,
            summary: \`${escapeJs(initialSummaryJson)}\`,
            attendance: \`${escapeJs(initialAttendanceJson)}\`,
            admit: \`${escapeJs(initialAdmitJson)}\`,
            studentList: \`${escapeJs(initialStudentListJson)}\`
          };

          // Initialize editor textarea
          document.getElementById("jsonTextarea").value = configs[currentTab];

          function switchTab(newTab) {
            const alertDiv = document.getElementById("alert");
            alertDiv.style.display = "none";
            const textarea = document.getElementById("jsonTextarea");
            
            // Validate active JSON structure before switching viewports
            const val = textarea.value.trim();
            try {
              JSON.parse(val);
              configs[currentTab] = val; // Commit edits to memory cache
            } catch (e) {
              alertDiv.textContent = "Cannot switch tabs. Current JSON is invalid: " + e.message;
              alertDiv.style.display = "block";
              alertDiv.className = "alert alert-error";
              return;
            }

            // Update CSS states on tab buttons
            document.querySelectorAll('.tab-btn').forEach(btn => {
              btn.classList.remove('active');
              if (btn.getAttribute('onclick').includes(newTab)) {
                btn.classList.add('active');
              }
            });

            // Set content of targeted tab
            currentTab = newTab;
            textarea.value = configs[newTab];
          }

          function saveAllConfigs() {
            const alertDiv = document.getElementById("alert");
            const textarea = document.getElementById("jsonTextarea");
            const saveBtn = document.getElementById("saveBtn");
            
            alertDiv.style.display = "none";

            // Commit active editor text to cached memory block
            configs[currentTab] = textarea.value.trim();

            // Validate all 4 JSON files prior to batch submission
            for (const [key, value] of Object.entries(configs)) {
              try {
                JSON.parse(value);
              } catch (e) {
                alertDiv.textContent = "Invalid JSON in " + key.toUpperCase() + " configuration: " + e.message;
                alertDiv.style.display = "block";
                alertDiv.className = "alert alert-error";
                return;
              }
            }

            saveBtn.disabled = true;
            saveBtn.textContent = "Saving...";

            // Send all updates in parallel to the DatasetServlet APIs
            Promise.all([
              fetch("api/datasets?action=uploadFormConfig&datasetId=${targetId}", {
                method: "POST",
                headers: { "Content-Type": "application/json; charset=UTF-8" },
                body: configs.form
              }).then(r => r.json()),
              
              fetch("api/datasets?action=uploadSummaryConfig&datasetId=${targetId}", {
                method: "POST",
                headers: { "Content-Type": "application/json; charset=UTF-8" },
                body: configs.summary
              }).then(r => r.json()),

              fetch("api/datasets?action=uploadAttendanceConfig&datasetId=${targetId}", {
                method: "POST",
                headers: { "Content-Type": "application/json; charset=UTF-8" },
                body: configs.attendance
              }).then(r => r.json()),

              fetch("api/datasets?action=uploadAdmitConfig&datasetId=${targetId}", {
                method: "POST",
                headers: { "Content-Type": "application/json; charset=UTF-8" },
                body: configs.admit
              }).then(r => r.json()),

              fetch("api/datasets?action=uploadStudentListConfig&datasetId=${targetId}", {
                method: "POST",
                headers: { "Content-Type": "application/json; charset=UTF-8" },
                body: configs.studentList
              }).then(r => r.json())
            ])
            .then(([resForm, resSummary, resAttendance, resAdmit, resStudentList]) => {
              if (resForm.success && resSummary.success && resAttendance.success && resAdmit.success && resStudentList.success) {
                alertDiv.textContent = "All configurations saved successfully!";
                alertDiv.style.display = "block";
                alertDiv.className = "alert alert-success";

                // Refresh parent dashboard only if the edited dataset is the active one
                if (window.opener && Number(window.opener.activeDatasetId) === Number(${targetId})) {
                  if (typeof window.opener.loadFormConfig === 'function') {
                    window.opener.loadFormConfig(${targetId});
                  }
                  if (typeof window.opener.loadStudentListConfig === 'function') {
                    window.opener.loadStudentListConfig(${targetId});
                  }
                  if (typeof window.opener.loadReport === 'function') {
                    window.opener.loadReport(${targetId});
                  }
                }

                setTimeout(() => {
                  window.close();
                }, 800);
              } else {
                let errors = [];
                if (!resForm.success) errors.push("Form Config: " + resForm.error);
                if (!resSummary.success) errors.push("Report Config: " + resSummary.error);
                if (!resAttendance.success) errors.push("Attendance Config: " + resAttendance.error);
                if (!resAdmit.success) errors.push("Admit Card Config: " + resAdmit.error);
                if (!resStudentList.success) errors.push("Student List Config: " + resStudentList.error);
                throw new Error(errors.join(" | "));
              }
            })
            .catch(err => {
              alertDiv.textContent = err.message;
              alertDiv.style.display = "block";
              alertDiv.className = "alert alert-error";
              saveBtn.disabled = false;
              saveBtn.textContent = "Save & Apply";
            });
          }
        </script>
      </body>
      </html>
    `);
    win.document.close();
  })
  .catch(err => {
    if (win) {
      win.document.open();
      win.document.write(`
        <html>
        <body style="font-family: sans-serif; padding: 20px; color: #ef4444;">
          <h3>Failed to load configurations</h3>
          <p>\${err.message}</p>
          <button onclick="window.close()">Close Window</button>
        </body>
        </html>
      `);
      win.document.close();
    } else {
      alert("Error fetching configurations: " + err.message);
    }
  });
}

/**
 * Searches the drill-down list dynamically using user-provided text query.
 */
function filterDrillDownList() {
  const searchVal = document.getElementById("drillDownSearchInput").value.toLowerCase();
  if (!searchVal.trim()) {
    renderDrillDownTable(currentDrillDownRecords);
    return;
  }

  // Linear scan across all candidate map values matching the search query
  const filtered = currentDrillDownRecords.filter(entry => {
    const record = entry.record;
    for (let key in record) {
      const val = String(record[key]).toLowerCase();
      if (val.includes(searchVal)) return true;
    }
    return false;
  });

  renderDrillDownTable(filtered);
}

// ── Form Configuration Management ──
/**
 * Loads the dataset's form configuration JSON structure.
 * 
 * @param {number|string} datasetId Target dataset ID.
 */
function loadFormConfig(datasetId) {
  fetch(`api/datasets?action=getFormConfig&datasetId=${datasetId}`)
    .then(resp => resp.json())
    .then(config => {
      activeFormConfig = config && config.sections ? config : null;
    })
    .catch(() => {
      activeFormConfig = null;
    });
}

/**
 * Loads the dataset's student list configuration JSON structure.
 * 
 * @param {number|string} datasetId Target dataset ID.
 */
function loadStudentListConfig(datasetId) {
  fetch(`api/datasets?action=getStudentListConfig&datasetId=${datasetId}`)
    .then(resp => resp.json())
    .then(config => {
      activeStudentListConfig = config && config.studentList ? config : null;
      if (currentDrillDownRecords && currentDrillDownRecords.length > 0) {
        renderDrillDownTable(currentDrillDownRecords);
      }
    })
    .catch(() => {
      activeStudentListConfig = null;
    });
}

/**
 * Reads local JSON configuration files and updates database configs.
 */
function uploadFormConfig() {
  const alertDiv = document.getElementById("formConfigAlert");
  alertDiv.style.display = "none";

  const fileInput = document.getElementById("formConfigFile");
  const file = fileInput.files[0];

  if (!file) {
    showAlert("formConfigAlert", "Please select a JSON form configuration file first.", "error");
    return;
  }

  const reader = new FileReader();
  reader.onload = function(e) {
    const jsonText = e.target.result;
    
    // Validate local JSON format
    try {
      JSON.parse(jsonText);
    } catch (err) {
      showAlert("formConfigAlert", "Invalid JSON format in file.", "error");
      return;
    }

    fetch(`api/datasets?action=uploadFormConfig&datasetId=${activeDatasetId}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json; charset=UTF-8"
      },
      body: jsonText
    })
    .then(resp => resp.json())
    .then(res => {
      if (res.success) {
        showAlert("formConfigAlert", "Form configuration uploaded successfully and saved!", "success");
        fileInput.value = "";
        loadFormConfig(activeDatasetId);
        loadStudentListConfig(activeDatasetId);
      } else {
        showAlert("formConfigAlert", res.error || "Failed to update form config.", "error");
      }
    })
    .catch(err => {
      showAlert("formConfigAlert", "Network error: " + err.message, "error");
    });
  };
  reader.readAsText(file);
}

// ── Individual Record View Modal & Form Generator ──
/**
 * Queries single record details and displays them in a modal workspace.
 * 
 * @param {number|string} recordId Target candidate record ID.
 */
function openRecordModal(recordId) {
  const modal = document.getElementById("recordModal");
  const modalTitleId = document.getElementById("modalRecordTitleId");
  const modalFormContent = document.getElementById("modalFormContent");

  modalTitleId.textContent = `Record Database ID: #${recordId}`;
  modalFormContent.innerHTML = '<div class="no-data-msg"><span class="spinner"></span> Loading record data...</div>';
  
  modal.classList.add("active");

  fetch(`api/record?id=${recordId}`)
    .then(resp => {
      if (!resp.ok) throw new Error("Failed to fetch record details.");
      return resp.json();
    })
    .then(data => {
      const record = data.record;
      generateDynamicForm(record, activeFormConfig, modalFormContent);
    })
    .catch(err => {
      modalFormContent.innerHTML = `<div class="alert alert-error show">Error: ${escapeHtml(err.message)}</div>`;
    });
}

/**
 * Closes the active record detail modal panel.
 */
function closeRecordModal() {
  document.getElementById("recordModal").classList.remove("active");
}

/**
 * Central HTML component generation engine.
 * Renders individual candidate details, academic tables, and signatures.
 * 
 * @param {Object} record Candidate map.
 * @param {Object} config UI layouts JSON schema.
 * @param {HTMLElement} container Parent DOM node.
 */
function generateDynamicForm(record, config, container) {
  container.innerHTML = "";

  /**
   * Helper function to resolve fields by prefix (e.g. "A1" matches "A1. Name of School")
   */
  function getRecordFieldValueByPrefix(record, prefix) {
    if (!record || !prefix) return null;
    const lowerPrefix = prefix.toLowerCase().trim();
    for (let key in record) {
      const lowerKey = key.toLowerCase().trim();
      if (lowerKey === lowerPrefix) {
        return { key: key, value: record[key] };
      }
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
   * Removes code prefix strings from labels.
   */
  function cleanFieldLabel(label, prefix) {
    if (!label) return "";
    let clean = label.trim();
    const prefixPattern = new RegExp(`^${prefix}\\.?\\s*`, 'i');
    return clean.replace(prefixPattern, '');
  }

  /**
   * Formats field values, parsing links into clickable anchor elements.
   */
  function formatFieldValue(val) {
    if (val === undefined || val === null || String(val).trim() === "") {
      return "—";
    }
    const strVal = String(val).trim();
    if (strVal.startsWith("http://") || strVal.startsWith("https://")) {
      return `<a href="${escapeHtml(strVal)}" target="_blank" class="btn btn-outline btn-sm" style="padding: 2px 8px; font-size: 0.8rem; font-weight: 600; text-decoration: none; display: inline-flex; align-items: center; gap: 4px;">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="margin-right:2px;"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path><polyline points="15 3 21 3 21 9"></polyline><line x1="10" y1="14" x2="21" y2="3"></line></svg>
        View Document
      </a>`;
    }
    return escapeHtml(strVal);
  }

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
   * Cleans internal column keys into capital spaced labels.
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
   * Maps column configurations to record prefix items.
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
   * Maps dataset grid entries to visual cells.
   */
  const mapTableRows = (tableDef, record) => {
    const rows = [];
    const columns = tableDef.columns || [];

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
    } else if (tableDef.source && tableDef.source.length > 0) {
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

  // Fallback to default flat list layout
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


// ── UI Helper Utilities ──
/**
 * Renders styled alerts dynamically in the dashboard view.
 * 
 * @param {string} id Targeted alert div ID.
 * @param {string} message Text string details.
 * @param {"success"|"error"|"info"} type Design type.
 */
function showAlert(id, message, type) {
  const alertDiv = document.getElementById(id);
  alertDiv.style.display = ""; // Reset inline display overrides
  alertDiv.className = `alert alert-${type} show`;
  alertDiv.textContent = message;
}

/**
 * Escapes characters for HTML output.
 * 
 * @param {string} s Raw string.
 * @returns {string} Safe string.
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
 * Escapes characters for dynamic JS script insertions (e.g. popups).
 * 
 * @param {string} s Raw string.
 * @returns {string} Safe string.
 */
function escapeJs(s) {
  if (!s) return "";
  return String(s)
    .replace(/\\/g, "\\\\")
    .replace(/'/g, "\\'")
    .replace(/"/g, '\\"')
    .replace(/`/g, "\\`")
    .replace(/\$/g, "\\$")
    .replace(/\n/g, "\\n")
    .replace(/\r/g, "\\r");
}

/**
 * Opens a modal dialog for the admin to upload a new CSV file for an existing dataset.
 * All JSON configs remain untouched — only the data records are replaced.
 *
 * @param {number|string} datasetId The dataset primary key.
 * @param {string} datasetName The dataset display name.
 */
function updateCsv(datasetId, datasetName) {
  // Remove any existing update-csv modal
  const existing = document.getElementById("updateCsvOverlay");
  if (existing) existing.remove();

  // Build modal overlay
  const overlay = document.createElement("div");
  overlay.id = "updateCsvOverlay";
  overlay.style.cssText = `
    position:fixed; inset:0; z-index:9999;
    background:rgba(15,23,42,0.55); backdrop-filter:blur(4px);
    display:flex; align-items:center; justify-content:center;
    animation: fadeIn 0.2s ease;
  `;

  overlay.innerHTML = `
    <div style="
      background:#fff; border-radius:12px; box-shadow:0 25px 50px -12px rgba(0,0,0,0.25);
      width:95%; max-width:520px; padding:0; overflow:hidden;
      animation: slideUp 0.25s cubic-bezier(0.16,1,0.3,1);
    ">
      <!-- Header -->
      <div style="
        background:var(--primary); color:#fff; padding:1rem 1.5rem;
        display:flex; justify-content:space-between; align-items:center;
      ">
        <div>
          <div style="font-family:'Outfit',sans-serif; font-weight:700; font-size:1.05rem;">Update CSV Data</div>
          <div style="font-size:0.82rem; opacity:0.8; margin-top:0.15rem;">Dataset: ${escapeHtml(datasetName)}</div>
        </div>
        <button onclick="document.getElementById('updateCsvOverlay').remove()" style="
          background:rgba(255,255,255,0.15); border:none; color:#fff; width:32px; height:32px;
          border-radius:50%; cursor:pointer; font-size:1.1rem; display:flex; align-items:center; justify-content:center;
        ">&times;</button>
      </div>

      <!-- Body -->
      <div style="padding:1.5rem;">
        <p style="font-size:0.88rem; color:#64748b; margin-bottom:1rem; line-height:1.5;">
          Upload a new CSV file to replace <strong>all existing records</strong> for this dataset.
          JSON configurations (Form Layout, Report, Student List, etc.) will <strong>not</strong> be changed.
        </p>

        <label style="display:block; font-size:0.78rem; font-weight:700; color:#64748b; text-transform:uppercase; letter-spacing:0.5px; margin-bottom:0.35rem;">
          Select New CSV File <span style="color:#ef4444;">*</span>
        </label>
        <input type="file" id="updateCsvFileInput" accept=".csv" style="
          width:100%; padding:0.6rem 0.75rem; border:1px solid #e2e8f0; border-radius:8px;
          font-size:0.9rem; background:#f8fafc; cursor:pointer;
        " />

        <div id="updateCsvAlert" style="display:none; margin-top:0.75rem; padding:0.65rem 0.85rem; border-radius:8px; font-size:0.85rem;"></div>
      </div>

      <!-- Footer -->
      <div style="
        padding:1rem 1.5rem; border-top:1px solid #e2e8f0;
        display:flex; justify-content:flex-end; gap:0.75rem; background:#f8fafc;
      ">
        <button onclick="document.getElementById('updateCsvOverlay').remove()" class="btn btn-outline btn-sm">Cancel</button>
        <button id="updateCsvUploadBtn" onclick="submitUpdateCsv(${datasetId}, '${escapeJs(datasetName)}')" class="btn btn-green btn-sm" style="padding:0.45rem 1.25rem;">
          ⬆ Upload &amp; Update
        </button>
      </div>
    </div>
  `;

  document.body.appendChild(overlay);

  // Close on backdrop click
  overlay.addEventListener("click", function(e) {
    if (e.target === overlay) overlay.remove();
  });
}

/**
 * Submits the selected CSV file from the update modal to the server.
 *
 * @param {number|string} datasetId The dataset primary key.
 * @param {string} datasetName The dataset display name.
 */
function submitUpdateCsv(datasetId, datasetName) {
  const fileInput = document.getElementById("updateCsvFileInput");
  const alertDiv = document.getElementById("updateCsvAlert");
  const uploadBtn = document.getElementById("updateCsvUploadBtn");

  if (!fileInput || !fileInput.files[0]) {
    alertDiv.style.display = "block";
    alertDiv.style.background = "#fee2e2";
    alertDiv.style.color = "#ef4444";
    alertDiv.style.border = "1px solid #fecaca";
    alertDiv.textContent = "Please select a CSV file first.";
    return;
  }

  const file = fileInput.files[0];
  const formData = new FormData();
  formData.append("datasetId", datasetId);
  formData.append("file", file);

  // Disable button and show loading
  uploadBtn.disabled = true;
  uploadBtn.innerHTML = '<span class="spinner"></span> Uploading...';
  alertDiv.style.display = "block";
  alertDiv.style.background = "#dbeafe";
  alertDiv.style.color = "#0d2d77";
  alertDiv.style.border = "1px solid #bfdbfe";
  alertDiv.innerHTML = '<span class="spinner"></span> Processing CSV file...';

  fetch("api/updateCsv", {
    method: "POST",
    body: formData
  })
  .then(resp => resp.json().then(data => ({ status: resp.status, body: data })))
  .then(res => {
    if (res.status !== 200) {
      throw new Error(res.body.error || "Failed to update CSV.");
    }

    // Show success in modal briefly, then close
    alertDiv.style.background = "#dcfce7";
    alertDiv.style.color = "#15803d";
    alertDiv.style.border = "1px solid #bbf7d0";
    alertDiv.textContent = `✓ Updated successfully! ${res.body.rowsInserted} records imported.`;

    // Also show success in the main list alert
    showAlert("listAlert", `CSV updated successfully! ${res.body.rowsInserted} records imported for "${escapeHtml(datasetName)}".`, "success");
    // refreshOnly=true: just refresh the table rows, do NOT redirect or switch tabs
    loadDatasetsList(true);

    // Close modal after short delay
    setTimeout(() => {
      const overlay = document.getElementById("updateCsvOverlay");
      if (overlay) overlay.remove();
    }, 1200);
  })
  .catch(err => {
    alertDiv.style.background = "#fee2e2";
    alertDiv.style.color = "#ef4444";
    alertDiv.style.border = "1px solid #fecaca";
    alertDiv.textContent = "Error: " + err.message;
  })
  .finally(() => {
    uploadBtn.disabled = false;
    uploadBtn.innerHTML = '⬆ Upload &amp; Update';
  });
}