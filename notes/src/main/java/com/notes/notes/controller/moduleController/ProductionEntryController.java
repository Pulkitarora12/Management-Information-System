package com.notes.notes.controller.moduleController;

import com.notes.notes.dto.moduleDTOS.ProductionEntryRequestDTO;
import com.notes.notes.entity.moduleEntities.DowntimeEntry;
import com.notes.notes.entity.moduleEntities.ProductionEntry;
import com.notes.notes.entity.moduleEntities.TimeSlot;
import com.notes.notes.repository.moduleRepo.MasterRepository;
import com.notes.notes.service.moduleServices.MasterService;
import com.notes.notes.service.moduleServices.impl.ProductionEntryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/production-entry")
public class ProductionEntryController {

    @Autowired
    private ProductionEntryService service;

    @Autowired
    private MasterRepository  masterRepository;

    @Autowired
    private MasterService masterService;

    @GetMapping
    public String showProductionEntryForm(@AuthenticationPrincipal UserDetails userDetails,
                                          Model model) {

        List<String> machineNames = masterService.getMachineNames();
        List<String> rejectionReasons = masterService.getRejectionReasons();
        List<Map<String, String>> partDetails = masterService.getPartDetailsList();
        List<String> operations = masterService.getOperations();
        List<String> operators = masterService.getOperators();
        List<String> downtimeReasons = masterService.getDowntimeReasons();
        String username = userDetails.getUsername();

        ProductionEntryRequestDTO dto = new ProductionEntryRequestDTO();
        dto.setInspector(username);

        // Send to frontend
        model.addAttribute("machineNames", machineNames);
        model.addAttribute("rejectionReasons", rejectionReasons);
        model.addAttribute("partDetails", partDetails);
        model.addAttribute("operations", operations);
        model.addAttribute("operators", operators);
        model.addAttribute("downtimeReasons", downtimeReasons);
        model.addAttribute("username", username);

        model.addAttribute("dto", dto);

        return "user/production_entry";
    }


    @PostMapping
    public String saveProduction(@ModelAttribute ProductionEntryRequestDTO dto,
                                 RedirectAttributes redirectAttributes) {
        try {
            ProductionEntry savedEntry = service.saveProduction(dto);
            redirectAttributes.addFlashAttribute(
                    "success",
                    "Production entry saved successfully!"
            );
            redirectAttributes.addFlashAttribute("savedId", savedEntry.getId());
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    e.getMessage()
            );
        }

        return "redirect:/production-entry";
    }

    @GetMapping("/getAll")
    public String getProductionHistory(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) String line,
            @RequestParam(required = false) String part,
            @RequestParam(required = false) String machine,
            @RequestParam(required = false) String operation,
            Model model) {

        List<ProductionEntry> entries = service.getFilteredEntries(
                month, date, line, part, machine, operation
        );

        model.addAttribute("entries", entries);

        // send filter values back (for retaining UI state)
        model.addAttribute("month", month);
        model.addAttribute("date", date);
        model.addAttribute("line", line);
        model.addAttribute("part", part);
        model.addAttribute("machine", machine);
        model.addAttribute("operation", operation);

        // dropdown data
        model.addAttribute("machines", masterService.getMachineNames());
        model.addAttribute("operations", masterService.getOperations());

        return "user/production_history";
    }


    @GetMapping("/export")
    public ResponseEntity<String> exportProductionCSV() {
        List<ProductionEntry> entries = service.getAllEntries();

        StringBuilder csvData = new StringBuilder();

        // Header
        csvData.append(
                "ID,Date,Shift,Line,Machine,Operation,Operator 1,Operator 2,Part No,Part Name,Remarks," +
                        "From Time,To Time,Produced,Segregated,Rejected,Slot Reason,Slot Remarks\n"
        );

        for (ProductionEntry entry : entries) {

            TimeSlot t = entry.getTimeSlot();

            csvData.append(entry.getId()).append(",")
                    .append(entry.getDate()).append(",")
                    .append(entry.getShift()).append(",")
                    .append(entry.getLine()).append(",")
                    .append(entry.getMachine()).append(",")
                    .append(entry.getOperation()).append(",")
                    .append(entry.getOperator1()).append(",")
                    .append(entry.getOperator2()).append(",")
                    .append(entry.getPartNo()).append(",")
                    .append(entry.getPartName()).append(",")
                    .append(entry.getRemarks()).append(",");

            // TimeSlot data
            if (t != null) {
                csvData.append(t.getFromTime()).append(",")
                        .append(t.getToTime()).append(",")
                        .append(t.getProduced()).append(",")
                        .append(t.getSegregated()).append(",")
                        .append(t.getRejected()).append(",")
                        .append(t.getReason()).append(",")
                        .append(t.getRemarks());
            } else {
                // No timeslot → empty columns
                csvData.append(",,,,,,");
            }

            csvData.append("\n");
        }

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=production_history.csv")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(csvData.toString());
    }


    @GetMapping("/{id}/timeslot")
    public String viewTimeSlot(@PathVariable Long id, Model model) {
        TimeSlot timeSlot = service.getTimeSlotByEntry(id);
        model.addAttribute("timeSlot", timeSlot);
        model.addAttribute("entryId", id);
        return "user/timeslots";
    }

    @GetMapping("/{id}/downtime")
    public String viewDowntime(@PathVariable Long id, Model model) {
        List<DowntimeEntry> downtimes = service.getDowntimeByEntry(id);

        // Ensure no null minutes
        downtimes.forEach(dt -> {
            if (dt.getMinutes() == null) {
                dt.setMinutes(0);
            }
        });

        model.addAttribute("downtimes", downtimes);
        model.addAttribute("entryId", id);
        return "user/downtime";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}/delete")
    public String deleteEntry(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            service.deleteEntry(id);
            redirectAttributes.addFlashAttribute(
                    "success",
                    "Production entry deleted successfully!"
            );
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    e.getMessage()
            );
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Failed to delete entry."
            );
        }
        return "redirect:/production-entry/getAll";
    }

}