# MIS (Management Information System) — Super Metal Unit II

A comprehensive enterprise management system built with Spring Boot, custom-tailored for **Super Metal Unit II**. It handles task management, production tracking, quality inspection, raw material logs, master data management, and HR employee movement records with real-time updates.

---

## 🎯 Overview

MIS is an enterprise-grade management system designed for manufacturing organizations. It provides end-to-end solutions for team collaboration through task management, production monitoring with real-time data entry, quality control via inspection records, and automated workflows using task templates.

---

## 📦 Modules

### 1. **Authentication & User Management**
- User registration with email verification
- Role-based access control (ADMIN/USER)
- Password reset functionality
- Admin-managed employee registration
- User profile management with department and contact info

---

### 2. **Task Management System**
A comprehensive task tracking system with real-time collaboration features.

**Features:**
- Task creation with priority levels (LOW, MEDIUM, HIGH, CRITICAL)
- Task status workflow (CREATED → IN_PROGRESS → REVIEW → SUBMITTED → CLOSED)
- Main assignee and supporting assignees
- Real-time comments with WebSocket updates
- File attachments (images/documents up to 5MB)
- Status history tracking
- Performance points system based on completion time
- Search and filter (by priority, status, progress)

---

### 3. **Task Templates & Automation**
Automated recurring task creation with flexible scheduling.

**Features:**
- Create reusable task templates
- Multiple frequencies: DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY
- Flash time (advance notice before due date)
- Auto-activation based on start date
- Pre-assigned main and supporting assignees
- Hourly scheduler checks and creates due tasks

**Use Cases:**
- Monthly safety audits
- Weekly inventory checks
- Quarterly financial reviews
- Annual compliance reports

---

### 4. **Production Entry Module**
Real-time production tracking for manufacturing operations.

**Features:**
- Date, shift (A/B), line, and machine tracking
- Operator assignment (dual operator support)
- Part details (part number, name, sheet size)
- Time slot tracking (from time, to time, produced, segregated, rejected)
- Multiple downtime entries per production entry
- Rejection reasons tracking
- Real-time filtering (by month, date, line, part, machine, operation)
- CSV export functionality

---

### 5. **RMIR (Raw Material Inspection Report) Module**
Quality control system for incoming raw materials.

**Features:**
- Bundle tracking (grade, number, gross/net weight, size)
- Part and supplier information
- Multiple observations per RMIR (L, B, H, GW per sheet)
- Overweight detection filter
- Inspector assignment
- Real-time filtering (month, date, part, supplier, grade, inspector)
- CSV export with flattened observations

---

### 6. **Master Data Management**
Flexible master data system with CSV/Excel import.

**Supported Masters:**
- Machine Master
- Rejection Master
- Grade Master
- Supplier Master
- Inspector Master
- Part Master (model, part no, part name, RM size, grade, GW)
- Operation Master
- Operator Master
- Downtime Master

**Features:**
- Dynamic field creation
- CSV/Excel upload with auto-detection
- CRUD operations on masters

---

### 7. **HR & Employee Movement Module**
A specialized human resource management module designed to maintain employee details and track physical gate entry/exit movements.

**Features:**
- **Employee Master:** Stores comprehensive profile details including contact info, salary, bank account details, and statutory identifiers (ESI, PF/PSI numbers).
- **Compliance Document Upload:** Allows storing and serving key scans such as employee photo and cancelled cheque.
- **Bulk Data Import:** Import employees list using custom CSV or Excel files (`.xlsx` or `.xls`) with automated mapping, column name normalizer, and phone-number-based update/insert checks.
- **Real-Time In/Out movement logs (Gate Log):** Monitor check-out/check-in logs, calculate movement duration in minutes, and store customizable log remarks.
- **Monthly Summary & Reporting:** Generate aggregate reports of total check-outs and duration (hours & minutes) for each employee by selecting any month and year.

---

## 🛠 Tech Stack

### Backend
- **Framework:** Spring Boot 3.x
- **Security:** Spring Security (Form-based authentication)
- **ORM:** Spring Data JPA / Hibernate
- **Database:** MySQL
- **Validation:** Jakarta Validation
- **Scheduler:** Spring @Scheduled
- **WebSocket:** Spring WebSocket with STOMP protocol
- **Email:** JavaMailSender (SMTP)

### Frontend
- **Template Engine:** Thymeleaf
- **JavaScript:** Vanilla JS + SockJS + Stomp.js (WebSocket)

### Tools & Libraries
- **File Processing:** Apache POI (Excel), OpenCSV (CSV)
- **Build Tool:** Maven
- **Java Version:** 17+

---

## ✨ Key Features

### Real-Time Updates
- WebSocket-powered task comments and status updates
- Live notifications without page refresh

### Email Notifications
- Admin verification emails for new users
- Task assignment notifications
- Comment notifications to task participants
- Password reset emails

### Performance System
- Points awarded to main assignees on task completion
- On-time completion: +10 points
- Late completion: -5 points per overdue day

### Security
- Password encryption with BCrypt
- Role-based access control
- Session management

---

## 🚀 Setup & Installation

### Prerequisites

1. **Java 17+**
   ```bash
   java -version
   ```

2. **MySQL Server**
   ```bash
   mysql --version
   ```

3. **Maven**
   ```bash
   mvn -version
   ```

---

## ⚙️ Environment Variables

### Create `application.properties`

**Location:** `src/main/resources/application.properties`

```properties
# ============================================
# SERVER CONFIGURATION
# ============================================
server.port=8080

# ============================================
# DATABASE CONFIGURATION
# ============================================
spring.datasource.url=jdbc:mysql://localhost:3306/mis_database?createDatabaseIfNotExist=true
spring.datasource.username=YOUR_DB_USERNAME
spring.datasource.password=YOUR_DB_PASSWORD
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# ============================================
# JPA/HIBERNATE CONFIGURATION
# ============================================
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQL8Dialect

# ============================================
# EMAIL CONFIGURATION (SMTP)
# ============================================
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=YOUR_EMAIL@gmail.com
spring.mail.password=YOUR_APP_PASSWORD
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true

# ============================================
# FILE UPLOAD CONFIGURATION
# ============================================
spring.servlet.multipart.max-file-size=5MB
spring.servlet.multipart.max-request-size=5MB
```

### Gmail App Password Setup

1. Go to [Google Account Security](https://myaccount.google.com/security)
2. Enable **2-Step Verification**
3. Go to **App Passwords**
4. Generate password for "Mail"
5. Copy 16-character password
6. Use in `spring.mail.password`

### File Upload Directory

Create the upload directory:

```bash
# Windows
mkdir C:\apps\MIS\uploads\images

# Linux/Mac
mkdir -p /home/user/MIS/uploads/images
```

**To change path:** Edit `FileStorageConfig.java`:
```java
public static final String IMAGE_UPLOAD_DIR = "YOUR_CUSTOM_PATH";
```

---

## 🏃 Running the Application

### Method 1: Using Maven

```bash
# Navigate to project directory
cd notes

# Clean and build
mvn clean install

# Run application
mvn spring-boot:run
```

### Method 2: Using JAR

```bash
# Build JAR
mvn clean package

# Run JAR
java -jar target/notes-0.0.1-SNAPSHOT.jar
```

### Method 3: Using IDE

1. Open project in IntelliJ IDEA / Eclipse / VS Code
2. Locate `NotesApplication.java`
3. Right-click → **Run**
4. 
---

## 🌐 Accessing the Application

Once running, access at: **http://localhost:8080**

**Main Routes:**
- `/` - Login page
- `/auth/dashboard` - Main dashboard
- `/tasks` - Task management
- `/template` - Task templates
- `/production-entry` - Production tracking
- `/rmir-entry` - Quality inspection
- `/masters` - Master data management
- `/hr/employee-master` - Employee database (HR)
- `/hr/in-out` - Employee gate logs / movement tracking (HR)
- `/hr/in-out/monthly-summary` - Monthly employee movement summaries (HR)
- `/admin/users` - User management (ADMIN only)

---

## 🐛 Troubleshooting

### Database Connection Issues
**Solution:** Ensure MySQL is running and credentials are correct in `application.properties`.

### Email Not Sending
**Solution:** 
1. Use Gmail App Password (not regular password)
2. Verify SMTP settings
3. Check firewall/antivirus blocking port 587

### File Upload Fails
**Solution:** Create upload directory manually and ensure write permissions.

### Scheduler Not Running
**Solution:** Verify `@EnableScheduling` is present in `NotesApplication.java` (already configured).

---

## 📝 Production Deployment Tips

1. **Change Admin Password** immediately after first login
2. **Enable CSRF Protection** (currently disabled in `SecurityConfig.java`)
3. **Set** `spring.jpa.hibernate.ddl-auto=validate` for production
4. **Configure HTTPS/SSL** for secure communication
5. **Use Environment Variables** instead of hardcoded credentials
6. **Set appropriate log levels** (WARN/ERROR in production)

---

**Built with ❤️ using Spring Boot and Thymeleaf**


