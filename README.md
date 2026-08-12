# 🎓 Admission & Academic Management Portal (admAll)

[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg?style=for-the-badge&logo=openjdk)](https://www.oracle.com/java/)
[![Tomcat](https://img.shields.io/badge/Apache_Tomcat-9.0-blue.svg?style=for-the-badge&logo=apache-tomcat)](https://tomcat.apache.org/)
[![Bootstrap](https://img.shields.io/badge/Bootstrap-5.0-purple.svg?style=for-the-badge&logo=bootstrap)](https://getbootstrap.com/)
[![License](https://img.shields.io/badge/License-MIT-green.svg?style=for-the-badge)](LICENSE)

## ℹ️ About Project

The **Admission & Academic Management Portal (admAll)** is an integrated academic portal serving students and administrators. It provides end-to-end workflows for managing student admissions, course departments, attendance tracking, and dynamic digital admit card generation complete with photo and signature verification layouts.

---

## 🌟 Features

- 🔐 **Dual Portal Access**: Authenticated login interfaces for administrators and enrolled students.
- 🪪 **Admit Card Generator**: Automated admit card rendering with student photographs, digital signatures, examination schedule, and roll number details.
- 🏛️ **Department Directory**: Interactive department catalog, course list, faculty details, and academic session tracking.
- 📋 **Attendance Tracking**: Real-time attendance monitoring, student roll-call verification, and updates.
- 📊 **CSV Data Integration**: Automated CSV parsing and data update service (`CSVHelper.java`).
- 🔒 **Security & Cryptography**: Encrypted authentication pipelines and credential protection (`CryptoHelper.java`).

---

## 📐 Use Case Diagram

```mermaid
gantt
    title User Workflow
    dateFormat  YYYY-MM-DD
    section Student Portal
    Login Authentication    :active, a1, 2026-08-01, 1d
    View Academic Record   :a2, after a1, 1d
    Download Admit Card    :a3, after a2, 1d
    section Admin Portal
    Upload CSV Records     :b1, 2026-08-01, 1d
    Manage Attendance      :b2, after b1, 2d
    Generate Admit Cards   :b3, after b2, 1d
```

---

## 🛠️ Tech Stack & Components

| Layer | Component | Details |
| :--- | :--- | :--- |
| **Frontend** | HTML5, CSS3, JavaScript, Bootstrap 5 | Single-page UI components, responsive tables, admit card print layouts. |
| **Backend** | Java Servlets (`HttpServlet`) | Controllers for login, sessions, record updates, and department information. |
| **Utilities** | `CSVHelper`, `CryptoHelper`, `DBHelper` | Data parsing, SHA/AES encryption, database query builders. |
| **Database** | MySQL + HikariCP | Connection pooled database backend (`DbConnection.java`). |

---

## 📁 Repository Structure

```text
admall/
├── src/
│   └── main/
│       ├── java/
│       │   ├── apps/
│       │   │   └── admall/
│       │   │       ├── servlet/             # Servlets (Login, AdmitCard, Attendance, Department)
│       │   │       └── util/                # Utilities (CSV, Crypto, DB helpers)
│       │   └── apps/
│       │       └── dbservice/               # HikariCP connection pool
│       ├── resources/
│       │   └── db.properties                 # Database connection settings
│       └── webapp/
│           └── apps/
│               └── admall/                  # Web pages, styles, and scripts
└── README.md
```

---

## 🚀 Setup & Launch

1. Configure `src/main/resources/db.properties` with your MySQL credentials.
2. Deploy the application onto Apache Tomcat 9.0+.
3. Open in browser:
   ```text
   http://localhost:8080/apps/apps/admall/index.html
   ```

---

## 📄 License
Licensed under the **MIT License**.
