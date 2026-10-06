package com.notes.notes.controller.hrController;

import com.notes.notes.entity.hrEntities.Employee;
import com.notes.notes.entity.hrEntities.InOutEntry;
import com.notes.notes.service.hrServices.EmployeeService;
import com.notes.notes.service.hrServices.InOutService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/hr/in-out")
public class InOutController {

    @Autowired
    private InOutService inOutService;

    @Autowired
    private EmployeeService employeeService;

    @GetMapping
    public String showInOutLogs(Model model) {
        List<InOutEntry> activeEntries = inOutService.getActiveEntries();
        List<InOutEntry> historicalEntries = inOutService.getHistoricalEntries();
        List<Employee> employees = employeeService.getAllEmployees();

        model.addAttribute("activeEntries", activeEntries);
        model.addAttribute("historicalEntries", historicalEntries);
        model.addAttribute("employees", employees);

        return "hr/in-out-logs";
    }

    @PostMapping("/out")
    public String logEmployeeOut(
            @RequestParam("employeeId") Long employeeId,
            @RequestParam(value = "outTimeStr", required = false) String outTimeStr,
            @RequestParam(value = "remarks", required = false) String remarks,
            RedirectAttributes redirectAttributes) {

        try {
            LocalDateTime outTime = parseDateTime(outTimeStr);
            inOutService.logOut(employeeId, outTime, remarks);
            redirectAttributes.addFlashAttribute("successMessage", "Employee outward movement logged successfully.");
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("errorMessage", "Error: " + e.getMessage());
        }

        return "redirect:/hr/in-out";
    }

    @PostMapping("/{id}/in")
    public String logEmployeeIn(
            @PathVariable("id") Long entryId,
            @RequestParam(value = "inTimeStr", required = false) String inTimeStr,
            RedirectAttributes redirectAttributes) {

        try {
            LocalDateTime inTime = parseDateTime(inTimeStr);
            inOutService.logIn(entryId, inTime);
            redirectAttributes.addFlashAttribute("successMessage", "Employee check-in logged successfully.");
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("errorMessage", "Error: " + e.getMessage());
        }

        return "redirect:/hr/in-out";
    }

    @PostMapping("/{id}/delete")
    public String deleteLog(
            @PathVariable("id") Long id,
            RedirectAttributes redirectAttributes) {
        try {
            inOutService.deleteEntry(id);
            redirectAttributes.addFlashAttribute("successMessage", "Movement log entry deleted successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Delete failed: " + e.getMessage());
        }
        return "redirect:/hr/in-out";
    }

    @GetMapping("/monthly-summary")
    public String showMonthlySummary(
            @RequestParam(value = "month", required = false) Integer month,
            @RequestParam(value = "year", required = false) Integer year,
            Model model) {

        LocalDate now = LocalDate.now();
        int selectedMonth = (month != null) ? month : now.getMonthValue();
        int selectedYear = (year != null) ? year : now.getYear();

        List<Map<String, Object>> summary = inOutService.getMonthlySummary(selectedMonth, selectedYear);

        model.addAttribute("summary", summary);
        model.addAttribute("selectedMonth", selectedMonth);
        model.addAttribute("selectedYear", selectedYear);

        return "hr/monthly-summary";
    }

    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateTimeStr, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception ex) {
                return LocalDateTime.now();
            }
        }
    }
}
