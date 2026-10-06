package com.notes.notes.controller.moduleController;

import com.notes.notes.dto.moduleDTOS.FieldDataCreateDTO;
import com.notes.notes.dto.moduleDTOS.MasterCreateDTO;
import com.notes.notes.dto.moduleDTOS.MasterFieldCreateDTO;
import com.notes.notes.entity.authEntities.DataType;
import com.notes.notes.entity.moduleEntities.FieldData;
import com.notes.notes.entity.moduleEntities.Master;
import com.notes.notes.entity.moduleEntities.MasterField;
import com.notes.notes.repository.moduleRepo.MasterRepository;
import com.notes.notes.service.moduleServices.MasterService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/masters")
public class MasterController {

    @Autowired
    private MasterService masterService;

    @Autowired
    private MasterRepository masterRepository;

    @GetMapping("/new")
    public String showCreateMasterForm(Model model) {
        MasterCreateDTO dto = new MasterCreateDTO();
        MasterFieldCreateDTO fieldDTO = new MasterFieldCreateDTO();
        fieldDTO.setFieldData(List.of(new FieldDataCreateDTO()));
        dto.setFields(List.of(fieldDTO));
        model.addAttribute("master", dto);
        return "user/master-create-form";
    }

    @PostMapping("/create")
    public String createMaster(@ModelAttribute("master") MasterCreateDTO masterDTO) {
        Master savedMaster = masterService.createMaster(masterDTO);
        return "redirect:/masters/" + savedMaster.getId();
    }

    @GetMapping("/{id}")
    public String viewMaster(@PathVariable Long id, Model model) {
        Master master = masterService.getMasterById(id);
        if (master == null) {
            model.addAttribute("error", "Master not found!");
            return "user/master-details";
        }
        model.addAttribute("master", master);
        return "user/master-details";
    }

    @GetMapping
    public String listAllMasters(Model model) {
        List<Master> masters = masterService.getAllMasters();
        model.addAttribute("masters", masters);
        return "user/master-list";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public String deleteMaster(@PathVariable Long id) {
        masterService.deleteMaster(id);
        return "redirect:/masters";
    }

    @GetMapping("/upload")
    public String showUploadPage() {
        return "user/upload-master";
    }

    @PostMapping("/upload")
    public String uploadMasterFile(
            @RequestParam("file") MultipartFile file,
            RedirectAttributes redirectAttributes) {

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Please upload a file.");
            return "redirect:/masters/upload";
        }

        try {
            String filename = file.getOriginalFilename();

            // Check if Excel file
            if (filename != null && filename.endsWith(".xlsx")) {
                return uploadExcel(file, redirectAttributes);
            } else {
                return uploadCSV(file, redirectAttributes);
            }

        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Error: " + e.getMessage());
            return "redirect:/masters/upload";
        }
    }

    // Handle Excel files
    private String uploadExcel(MultipartFile file, RedirectAttributes redirectAttributes) throws Exception {
        Workbook workbook = new XSSFWorkbook(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);

        // Row 1: Master name
        String masterName = sheet.getRow(0).getCell(0).getStringCellValue().trim();
        masterName = masterName.substring(0, Math.min(masterName.length(), 100));

        Master master = new Master();
        master.setName(masterName);

        // Row 2: Field names
        Row fieldRow = sheet.getRow(1);
        List<MasterField> masterFields = new ArrayList<>();

        for (Cell cell : fieldRow) {
            String fieldName = getCellValue(cell).trim();
            if (!fieldName.isEmpty()) {
                MasterField mf = new MasterField();
                mf.setFieldName(fieldName);
                mf.setDataType(DataType.STRING);
                mf.setMaster(master);
                masterFields.add(mf);
            }
        }

        master.setFields(masterFields);

        // Row 3+: Data
        for (int i = 2; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            for (int j = 0; j < masterFields.size(); j++) {
                Cell cell = row.getCell(j);
                String value = getCellValue(cell).trim();

                if (value.length() > 255) value = value.substring(0, 255);

                if (!value.isEmpty()) {
                    FieldData data = new FieldData();
                    data.setValue(value);
                    data.setField(masterFields.get(j));
                    masterFields.get(j).getFieldData().add(data);
                }
            }
        }

        workbook.close();
        masterRepository.save(master);

        redirectAttributes.addFlashAttribute("success", "Master created successfully!");
        return "redirect:/masters/" + master.getId();
    }

    // Handle CSV files (your original code)
    private String uploadCSV(MultipartFile file, RedirectAttributes redirectAttributes) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));

        String masterNameRaw = reader.readLine();
        String fieldLine = reader.readLine();

        if (masterNameRaw == null || fieldLine == null) {
            redirectAttributes.addFlashAttribute("error", "CSV must have master name in row 1 and fields in row 2.");
            return "redirect:/masters/upload";
        }

        String masterName = masterNameRaw.split(",")[0].trim().replaceAll(",+$", "");
        masterName = masterName.substring(0, Math.min(masterName.length(), 100));

        Master master = new Master();
        master.setName(masterName);

        String[] fields = fieldLine.split(",");
        List<MasterField> masterFields = new ArrayList<>();

        for (String field : fields) {
            field = field.trim();
            if (field.isEmpty()) continue;
            MasterField mf = new MasterField();
            mf.setFieldName(field);
            mf.setDataType(DataType.STRING);
            mf.setMaster(master);
            masterFields.add(mf);
        }

        master.setFields(masterFields);

        String row;
        while ((row = reader.readLine()) != null) {
            String[] values = row.split(",");
            for (int i = 0; i < masterFields.size(); i++) {
                if (i < values.length) {
                    String value = values[i].trim().replaceAll("\\s+", " ").trim();

                    if (value.length() > 255) {
                        value = value.substring(0, 255);
                    }

                    if (!value.isEmpty()) {
                        FieldData data = new FieldData();
                        data.setValue(value);
                        data.setField(masterFields.get(i));
                        masterFields.get(i).getFieldData().add(data);
                    }
                }
            }
        }

        masterRepository.save(master);

        redirectAttributes.addFlashAttribute("success", "Master created successfully!");
        return "redirect:/masters/" + master.getId();
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();

            case NUMERIC:
                double numericValue = cell.getNumericCellValue();
                // If it's an integer (like 1.0), avoid trailing .0
                if (numericValue == Math.floor(numericValue)) {
                    return String.valueOf((long) numericValue);
                } else {
                    return String.valueOf(numericValue);
                }

            case FORMULA:
                // Evaluate formula if needed
                try {
                    return cell.getStringCellValue();
                } catch (IllegalStateException e) {
                    double val = cell.getNumericCellValue();
                    return (val == Math.floor(val)) ? String.valueOf((long) val) : String.valueOf(val);
                }

            default:
                return "";
        }
    }

}