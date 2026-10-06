package com.notes.notes.service.hrServices;

import com.notes.notes.entity.hrEntities.Employee;
import com.notes.notes.repository.hrRepo.EmployeeRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Service
public class EmployeeService {

    @Autowired
    private EmployeeRepository employeeRepository;

    public static final String UPLOAD_DIR = "C:/apps/MIS/uploads/employee/";

    public List<Employee> getAllEmployees() {
        return employeeRepository.findAll();
    }

    public Optional<Employee> getEmployeeById(Long id) {
        return employeeRepository.findById(id);
    }

    public Employee saveEmployee(Employee employee) {
        return employeeRepository.save(employee);
    }

    public void deleteEmployee(Long id) {
        employeeRepository.deleteById(id);
    }

    /**
     * Imports employees from a CSV or Excel file.
     * Returns a summary message.
     */
    public String importEmployees(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new IllegalArgumentException("Invalid file name");
        }

        int importedCount = 0;
        int skippedCount = 0;
        List<String> errors = new ArrayList<>();

        if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
            // Excel Parsing
            try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
                Sheet sheet = workbook.getSheetAt(0);
                if (sheet.getPhysicalNumberOfRows() < 2) {
                    return "Excel file is empty or missing headers.";
                }

                Row headerRow = sheet.getRow(0);
                Map<String, Integer> headerMap = getExcelHeaderMap(headerRow);

                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;

                    try {
                        Employee employee = parseExcelRow(row, headerMap);
                        if (validateAndSaveEmployee(employee)) {
                            importedCount++;
                        } else {
                            skippedCount++;
                            errors.add("Row " + (i + 1) + ": Missing mandatory fields (Name, Phone, Gender, or Address)");
                        }
                    } catch (Exception e) {
                        skippedCount++;
                        errors.add("Row " + (i + 1) + ": " + e.getMessage());
                    }
                }
            }
        } else {
            // CSV Parsing
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    return "CSV file is empty.";
                }

                List<String> headers = parseCsvLine(headerLine);
                Map<String, Integer> headerMap = getCsvHeaderMap(headers);

                String line;
                int lineNum = 1;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    if (line.trim().isEmpty()) continue;

                    try {
                        List<String> values = parseCsvLine(line);
                        Employee employee = parseCsvRow(values, headerMap);
                        if (validateAndSaveEmployee(employee)) {
                            importedCount++;
                        } else {
                            skippedCount++;
                            errors.add("Row " + lineNum + ": Missing mandatory fields (Name, Phone, Gender, or Address)");
                        }
                    } catch (Exception e) {
                        skippedCount++;
                        errors.add("Row " + lineNum + ": " + e.getMessage());
                    }
                }
            }
        }

        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Successfully imported %d employees. ", importedCount));
        if (skippedCount > 0) {
            summary.append(String.format("Skipped %d rows. ", skippedCount));
            if (errors.size() <= 5) {
                summary.append("Errors: ").append(String.join("; ", errors));
            } else {
                summary.append("Errors: ").append(String.join("; ", errors.subList(0, 5))).append("... and more.");
            }
        }
        return summary.toString();
    }

    private boolean validateAndSaveEmployee(Employee emp) {
        if (emp.getName() == null || emp.getName().trim().isEmpty() ||
            emp.getPhoneNumber() == null || emp.getPhoneNumber().trim().isEmpty() ||
            emp.getGender() == null || emp.getGender().trim().isEmpty() ||
            emp.getAddress() == null || emp.getAddress().trim().isEmpty()) {
            return false;
        }
        
        // Check if phone number already exists to update or insert
        Optional<Employee> existing = employeeRepository.findByPhoneNumber(emp.getPhoneNumber().trim());
        if (existing.isPresent()) {
            Employee old = existing.get();
            emp.setId(old.getId());
            // Preserve files if not specified
            if (emp.getChequePath() == null) emp.setChequePath(old.getChequePath());
            if (emp.getPhotoPath() == null) emp.setPhotoPath(old.getPhotoPath());
        }
        
        employeeRepository.save(emp);
        return true;
    }

    public void saveEmployeeFile(Employee employee, MultipartFile file, String fileType) throws Exception {
        if (file.isEmpty()) return;

        Path uploadPath = Paths.get(UPLOAD_DIR);
        Files.createDirectories(uploadPath);

        String originalName = file.getOriginalFilename();
        String storedName = System.currentTimeMillis() + "_" + fileType + "_" + (originalName != null ? originalName.replaceAll("\\s+", "_") : "file");
        Path filePath = uploadPath.resolve(storedName);

        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        if ("photo".equalsIgnoreCase(fileType)) {
            employee.setPhotoPath(storedName);
        } else if ("cheque".equalsIgnoreCase(fileType)) {
            employee.setChequePath(storedName);
        }
        employeeRepository.save(employee);
    }

    private String normalizeHeader(String header) {
        if (header == null) return "";
        return header.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private Map<String, Integer> getExcelHeaderMap(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (Cell cell : headerRow) {
            String header = getCellValueAsString(cell);
            if (header != null && !header.trim().isEmpty()) {
                map.put(normalizeHeader(header), cell.getColumnIndex());
            }
        }
        return map;
    }

    private Map<String, Integer> getCsvHeaderMap(List<String> headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (header != null && !header.trim().isEmpty()) {
                map.put(normalizeHeader(header), i);
            }
        }
        return map;
    }

    private Employee parseExcelRow(Row row, Map<String, Integer> headerMap) {
        Employee emp = new Employee();
        emp.setName(getValueFromExcel(row, headerMap, "name"));
        emp.setFatherName(getValueFromExcel(row, headerMap, "fathername", "father"));
        emp.setEmployeeCode(getValueFromExcel(row, headerMap, "employeecode", "empcode", "code"));
        emp.setContractorName(getValueFromExcel(row, headerMap, "contractorname", "contractor"));
        emp.setPhoneNumber(getValueFromExcel(row, headerMap, "phonenumber", "phone", "number", "mobile"));
        emp.setGender(getValueFromExcel(row, headerMap, "gender", "sex"));
        emp.setEmail(getValueFromExcel(row, headerMap, "email", "emailaddress"));
        emp.setAddress(getValueFromExcel(row, headerMap, "address", "location"));
        emp.setRoles(getValueFromExcel(row, headerMap, "roles", "role"));
        
        String salStr = getValueFromExcel(row, headerMap, "salary", "pay", "wage");
        if (salStr != null && !salStr.isEmpty()) {
            try {
                emp.setSalary(Double.parseDouble(salStr));
            } catch (NumberFormatException ignored) {}
        }

        emp.setBankAccount(getValueFromExcel(row, headerMap, "bankaccount", "bankaccountnumber", "bank", "account"));
        emp.setEsiNumber(getValueFromExcel(row, headerMap, "esinumber", "esi"));
        emp.setPsiNumber(getValueFromExcel(row, headerMap, "psinumber", "psi", "pf", "pfnumber"));

        return emp;
    }

    private Employee parseCsvRow(List<String> values, Map<String, Integer> headerMap) {
        Employee emp = new Employee();
        emp.setName(getValueFromCsv(values, headerMap, "name"));
        emp.setFatherName(getValueFromCsv(values, headerMap, "fathername", "father"));
        emp.setEmployeeCode(getValueFromCsv(values, headerMap, "employeecode", "empcode", "code"));
        emp.setContractorName(getValueFromCsv(values, headerMap, "contractorname", "contractor"));
        emp.setPhoneNumber(getValueFromCsv(values, headerMap, "phonenumber", "phone", "number", "mobile"));
        emp.setGender(getValueFromCsv(values, headerMap, "gender", "sex"));
        emp.setEmail(getValueFromCsv(values, headerMap, "email", "emailaddress"));
        emp.setAddress(getValueFromCsv(values, headerMap, "address", "location"));
        emp.setRoles(getValueFromCsv(values, headerMap, "roles", "role"));

        String salStr = getValueFromCsv(values, headerMap, "salary", "pay", "wage");
        if (salStr != null && !salStr.isEmpty()) {
            try {
                emp.setSalary(Double.parseDouble(salStr));
            } catch (NumberFormatException ignored) {}
        }

        emp.setBankAccount(getValueFromCsv(values, headerMap, "bankaccount", "bankaccountnumber", "bank", "account"));
        emp.setEsiNumber(getValueFromCsv(values, headerMap, "esinumber", "esi"));
        emp.setPsiNumber(getValueFromCsv(values, headerMap, "psinumber", "psi", "pf", "pfnumber"));

        return emp;
    }

    private String getValueFromExcel(Row row, Map<String, Integer> headerMap, String... keys) {
        for (String key : keys) {
            Integer colIdx = headerMap.get(key);
            if (colIdx != null) {
                Cell cell = row.getCell(colIdx);
                if (cell != null) {
                    return getCellValueAsString(cell).trim();
                }
            }
        }
        return "";
    }

    private String getValueFromCsv(List<String> values, Map<String, Integer> headerMap, String... keys) {
        for (String key : keys) {
            Integer colIdx = headerMap.get(key);
            if (colIdx != null && colIdx < values.size()) {
                return values.get(colIdx).trim();
            }
        }
        return "";
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double numVal = cell.getNumericCellValue();
                if (numVal == Math.floor(numVal)) {
                    return String.valueOf((long) numVal);
                }
                return String.valueOf(numVal);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    double val = cell.getNumericCellValue();
                    if (val == Math.floor(val)) {
                        return String.valueOf((long) val);
                    }
                    return String.valueOf(val);
                }
            default:
                return "";
        }
    }

    public static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return values;
        }
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                values.add(sb.toString().trim().replaceAll("^\"|\"$", ""));
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        values.add(sb.toString().trim().replaceAll("^\"|\"$", ""));
        return values;
    }
}
