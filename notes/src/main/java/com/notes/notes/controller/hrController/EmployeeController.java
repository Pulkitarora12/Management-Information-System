package com.notes.notes.controller.hrController;

import com.notes.notes.entity.hrEntities.Employee;
import com.notes.notes.service.hrServices.EmployeeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Controller
@RequestMapping("/hr/employee-master")
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;

    @GetMapping
    public String showEmployeeMaster(Model model) {
        List<Employee> employees = employeeService.getAllEmployees();
        model.addAttribute("employees", employees);
        return "hr/employee-master";
    }

    @PostMapping("/upload")
    public String uploadEmployeeFile(
            @RequestParam("file") MultipartFile file,
            RedirectAttributes redirectAttributes) {

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid CSV or Excel file to upload.");
            return "redirect:/hr/employee-master";
        }

        try {
            String resultSummary = employeeService.importEmployees(file);
            redirectAttributes.addFlashAttribute("successMessage", resultSummary);
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to import employee master: " + e.getMessage());
        }

        return "redirect:/hr/employee-master";
    }

    @GetMapping("/{id}")
    public String viewEmployeeDetails(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        Employee employee = employeeService.getEmployeeById(id).orElse(null);
        if (employee == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Employee not found");
            return "redirect:/hr/employee-master";
        }
        model.addAttribute("employee", employee);
        return "hr/employee-detail";
    }

    @PostMapping("/{id}/upload-docs")
    public String uploadEmployeeDocs(
            @PathVariable Long id,
            @RequestParam(value = "photo", required = false) MultipartFile photo,
            @RequestParam(value = "cheque", required = false) MultipartFile cheque,
            RedirectAttributes redirectAttributes) {

        Employee employee = employeeService.getEmployeeById(id).orElse(null);
        if (employee == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Employee not found");
            return "redirect:/hr/employee-master";
        }

        try {
            boolean uploadedAny = false;
            if (photo != null && !photo.isEmpty()) {
                employeeService.saveEmployeeFile(employee, photo, "photo");
                uploadedAny = true;
            }
            if (cheque != null && !cheque.isEmpty()) {
                employeeService.saveEmployeeFile(employee, cheque, "cheque");
                uploadedAny = true;
            }

            if (uploadedAny) {
                redirectAttributes.addFlashAttribute("successMessage", "Employee documents uploaded successfully!");
            } else {
                redirectAttributes.addFlashAttribute("errorMessage", "No files selected for upload.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("errorMessage", "Document upload failed: " + e.getMessage());
        }

        return "redirect:/hr/employee-master/" + id;
    }

    @GetMapping("/files/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) {
        try {
            Path fileDir = Paths.get(EmployeeService.UPLOAD_DIR).normalize();
            Path filePath = fileDir.resolve(filename).normalize();

            // Prevent path traversal attacks
            if (!filePath.startsWith(fileDir)) {
                return ResponseEntity.badRequest().build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() || resource.isReadable()) {
                String contentType = Files.probeContentType(filePath);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
