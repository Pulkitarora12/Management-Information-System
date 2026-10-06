package com.notes.notes.controller.moduleController;

import com.notes.notes.dto.moduleDTOS.RMIRRequestDTO;
import com.notes.notes.entity.moduleEntities.Observation;
import com.notes.notes.entity.moduleEntities.RMIR;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.repository.moduleRepo.RMIRRepository;
import com.notes.notes.service.moduleServices.MasterService;
import com.notes.notes.service.moduleServices.RMIRService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/rmir-entry")
public class RMIRController {

    @Autowired
    private RMIRService service;

    @Autowired
    private RMIRRepository repository;
    
    @Autowired
    private MasterService masterService;
    
    @GetMapping
    public String showForm(Model model, @ModelAttribute("loggedInUser") User loggedInUser,
                           @AuthenticationPrincipal UserDetails userDetails) {

        List<String> grades = masterService.getGrades();
        List<String> suppliers = masterService.getSuppliers();
        List<String> inspectors = masterService.getInspectors();
        List<Map<String, String>> partDetails = masterService.getPartDetailsList();

        RMIRRequestDTO dto = new RMIRRequestDTO();
        String username = userDetails.getUsername();
        dto.setInspector(username);
        dto.setCreatedDate(LocalDate.now());
        dto.setCreatedTime(LocalTime.now());


        model.addAttribute("dto", dto);
        model.addAttribute("grades", grades);
        model.addAttribute("suppliers", suppliers);
        model.addAttribute("inspectors", inspectors);
        model.addAttribute("partDetails", partDetails);

        return "user/rmir_entry";
    }


    @PostMapping
    public String submitRMIR(@ModelAttribute RMIRRequestDTO dto,
                             RedirectAttributes redirectAttributes) {
        try {
            RMIR savedEntry = service.saveRMIR(dto);
            redirectAttributes.addFlashAttribute(
                    "success",
                    "RMIR submitted successfully!"
            );
            redirectAttributes.addFlashAttribute("savedId", savedEntry.getId());
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    e.getMessage()
            );
        }

        return "redirect:/rmir-entry";
    }

    @GetMapping("/getAll")
    public String getRMIRHistory(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) LocalDate createdDate,
            @RequestParam(required = false) String part,
            @RequestParam(required = false) String supplier,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String inspector,
            @RequestParam(defaultValue = "false") boolean overweight,
            Model model
    ) {

        List<RMIR> entries = service.getFilteredEntries(
                month, createdDate, part, supplier, grade, inspector, overweight
        );

        model.addAttribute("entries", entries);

        // retain filter values
        model.addAttribute("month", month);
        model.addAttribute("createdDate", createdDate);
        model.addAttribute("part", part);
        model.addAttribute("supplier", supplier);
        model.addAttribute("grade", grade);
        model.addAttribute("inspector", inspector);
        model.addAttribute("overweight", overweight);

        // dropdown data
        model.addAttribute("suppliers", masterService.getSuppliers());
        model.addAttribute("grades", masterService.getPartGrades());
        model.addAttribute("inspectors", masterService.getInspectors());

        return "user/rmir_history";
    }


    @GetMapping("/export")
    public ResponseEntity<String> exportToCSV() {

        List<RMIR> entries = service.getAllEntries();
        StringBuilder csvData = new StringBuilder();

        // 1️⃣ Header
        csvData.append(
                "RMIR ID,Part No,Part Name,RM Size,Grade,Std GW,Bundle Grade,Bundle No," +
                        "Bundle GW,Bundle NW,Bundle Size,Supplier,Inspector,Remarks," +
                        "Created Date,Created Time," +
                        "Observation ID,L,B,H,GW Sheet\n"
        );

        // 2️⃣ Data rows
        for (RMIR entry : entries) {

            // Case A: No observations
            if (entry.getObservations() == null || entry.getObservations().isEmpty()) {

                csvData.append(entry.getId()).append(",")
                        .append(entry.getPartNo()).append(",")
                        .append(entry.getPartName()).append(",")
                        .append(entry.getRmSize()).append(",")
                        .append(entry.getGrade()).append(",")
                        .append(entry.getStdGW()).append(",")
                        .append(entry.getBundleGrade()).append(",")
                        .append(entry.getBundleNo()).append(",")
                        .append(entry.getBundleGW()).append(",")
                        .append(entry.getBundleNW()).append(",")
                        .append(entry.getBundleSize()).append(",")
                        .append(entry.getSupplier()).append(",")
                        .append(entry.getInspector()).append(",")
                        .append(entry.getRemarks()).append(",")
                        .append(entry.getCreatedDate()).append(",")
                        .append(entry.getCreatedTime()).append(",")
                        // Observation columns empty
                        .append(",,,,")
                        .append("\n");

            }
            // Case B: One or more observations
            else {
                for (Observation obs : entry.getObservations()) {

                    csvData.append(entry.getId()).append(",")
                            .append(entry.getPartNo()).append(",")
                            .append(entry.getPartName()).append(",")
                            .append(entry.getRmSize()).append(",")
                            .append(entry.getGrade()).append(",")
                            .append(entry.getStdGW()).append(",")
                            .append(entry.getBundleGrade()).append(",")
                            .append(entry.getBundleNo()).append(",")
                            .append(entry.getBundleGW()).append(",")
                            .append(entry.getBundleNW()).append(",")
                            .append(entry.getBundleSize()).append(",")
                            .append(entry.getSupplier()).append(",")
                            .append(entry.getInspector()).append(",")
                            .append(entry.getRemarks()).append(",")
                            .append(entry.getCreatedDate()).append(",")
                            .append(entry.getCreatedTime()).append(",")

                            // Observation data
                            .append(obs.getId()).append(",")
                            .append(obs.getL()).append(",")
                            .append(obs.getB()).append(",")
                            .append(obs.getH()).append(",")
                            .append(obs.getGwSheet())
                            .append("\n");
                }
            }
        }

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=rmir_history.csv")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(csvData.toString());
    }


    @GetMapping("/{id}/observations")
    public String viewObservations(@PathVariable Long id, Model model) {
        RMIR rmir = repository.findById(id).orElse(null);
        if (rmir != null) {
            model.addAttribute("observations", rmir.getObservations());
            model.addAttribute("entryId", id);
            model.addAttribute("stdGW", rmir.getStdGW());
        }
        return "user/rmir_observations"; // Make this page
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}/delete")
    public String deleteRMIR(@PathVariable Long id, RedirectAttributes redirectAttributes) {

        try {
            service.deleteRMIR(id);
            redirectAttributes.addFlashAttribute(
                    "success",
                    "RMIR entry deleted successfully!"
            );
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    e.getMessage()
            );
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Failed to delete RMIR entry."
            );
        }

        return "redirect:/rmir-entry/getAll";  // Adjust if needed
    }

}

