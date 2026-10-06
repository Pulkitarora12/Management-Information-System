package com.notes.notes.service.googleSheetServices;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.*;
import com.notes.notes.entity.moduleEntities.Observation;
import com.notes.notes.entity.moduleEntities.RMIR;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class RMIRGoogleSheetsService {

    @Autowired
    private Sheets sheetsClient;

    @Value("${google.sheets.spreadsheet.id}")
    private String spreadsheetId;

    private static final String RMIR_SHEET = "RMIREntries";

    // ===========================================
    // CREATE — Add RMIR with multiple observations
    // ===========================================
    public void addRMIRToSheet(RMIR rmir) {
        try {
            List<List<Object>> rows = convertRMIRToRows(rmir);

            ValueRange body = new ValueRange().setValues(rows);

            sheetsClient.spreadsheets().values()
                    .append(spreadsheetId, RMIR_SHEET + "!A:T", body)
                    .setValueInputOption("USER_ENTERED")  // Changed from RAW
                    .setInsertDataOption("INSERT_ROWS")   // Add this
                    .execute();

            System.out.println("✅ RMIR added to Google Sheet: " + rmir.getId());

        } catch (Exception e) {
            System.err.println("❌ Error adding RMIR to Google Sheets: " + e.getMessage());
        }
    }

    // ===========================================
    // UPDATE — Remove old rows and reinsert
    // ===========================================
    public void updateRMIRInSheet(RMIR rmir) {
        try {
            deleteRMIRFromSheet(rmir.getId());
            addRMIRToSheet(rmir);
            System.out.println("♻️ RMIR updated in Google Sheets: " + rmir.getId());

        } catch (Exception e) {
            System.err.println("❌ Error updating RMIR in Google Sheets: " + e.getMessage());
        }
    }

    // ===========================================
    // DELETE — Remove ALL rows with this RMIR ID
    // ===========================================
    public void deleteRMIRFromSheet(Long rmirId) {
        try {
            String range = RMIR_SHEET + "!A:A"; // ID column
            ValueRange response = sheetsClient.spreadsheets().values()
                    .get(spreadsheetId, range)
                    .execute();

            List<List<Object>> values = response.getValues();
            if (values == null) return;

            List<Request> deleteRequests = new ArrayList<>();

            // Loop backwards so row shifting doesn't affect indexes
            for (int i = values.size() - 1; i >= 0; i--) {
                if (values.get(i).isEmpty()) continue;

                String cellValue = values.get(i).get(0).toString();
                if (cellValue.equals(rmirId.toString())) {
                    deleteRequests.add(new Request().setDeleteDimension(
                            new DeleteDimensionRequest().setRange(
                                    new DimensionRange()
                                            .setSheetId(getSheetId(RMIR_SHEET))
                                            .setDimension("ROWS")
                                            .setStartIndex(i)
                                            .setEndIndex(i + 1)
                            )
                    ));
                }
            }

            if (!deleteRequests.isEmpty()) {
                sheetsClient.spreadsheets().batchUpdate(
                        spreadsheetId,
                        new BatchUpdateSpreadsheetRequest().setRequests(deleteRequests)
                ).execute();

                System.out.println("🗑 Deleted RMIR rows for ID: " + rmirId);
            }

        } catch (Exception e) {
            System.err.println("❌ Failed to delete RMIR rows from Google Sheets: " + e.getMessage());
        }
    }

    // ===========================================
    // Helper: Convert RMIR + Multiple Observations to Rows
    // ===========================================
    private List<List<Object>> convertRMIRToRows(RMIR rmir) {
        List<List<Object>> rows = new ArrayList<>();

        if (rmir.getObservations().isEmpty()) {
            // even with no observations, still create one row
            rows.add(buildRow(rmir, null));
        } else {
            for (Observation obs : rmir.getObservations()) {
                rows.add(buildRow(rmir, obs));
            }
        }

        return rows;
    }

    // ===========================================
    // Build a single row (RMIR fields + Observation)
    // ===========================================
    private List<Object> buildRow(RMIR rmir, Observation obs) {
        List<Object> row = new ArrayList<>();

        row.add(rmir.getId() != null ? rmir.getId() : "");
        row.add(rmir.getPartNo() != null ? rmir.getPartNo() : "");
        row.add(rmir.getPartName() != null ? rmir.getPartName() : "");
        row.add(rmir.getRmSize() != null ? rmir.getRmSize() : "");
        row.add(rmir.getGrade() != null ? rmir.getGrade() : "");
        row.add(rmir.getStdGW() != null ? rmir.getStdGW() : "");
        row.add(rmir.getBundleGrade() != null ? rmir.getBundleGrade() : "");
        row.add(rmir.getBundleNo() != null ? rmir.getBundleNo() : "");
        row.add(rmir.getBundleGW() != null ? rmir.getBundleGW() : "");
        row.add(rmir.getBundleNW() != null ? rmir.getBundleNW() : "");
        row.add(rmir.getBundleSize() != null ? rmir.getBundleSize() : "");
        row.add(rmir.getSupplier() != null ? rmir.getSupplier() : "");
        row.add(rmir.getInspector() != null ? rmir.getInspector() : "");
        row.add(rmir.getRemarks() != null ? rmir.getRemarks() : "");
        row.add(rmir.getCreatedDate() != null ? rmir.getCreatedDate().toString() : "");
        row.add(rmir.getCreatedTime() != null ? rmir.getCreatedTime().toString() : "");

        // Observation fields (nullable)
        if (obs != null) {
            row.add(obs.getL() != null ? obs.getL() : "");
            row.add(obs.getB() != null ? obs.getB() : "");
            row.add(obs.getH() != null ? obs.getH() : "");
            row.add(obs.getGwSheet() != null ? obs.getGwSheet() : "");
        } else {
            row.add("");
            row.add("");
            row.add("");
            row.add("");
        }

        return row;
    }

    // ===========================================
    // Get Sheet ID
    // ===========================================
    private Integer getSheetId(String sheetName) throws IOException {
        Spreadsheet spreadsheet = sheetsClient.spreadsheets()
                .get(spreadsheetId)
                .execute();

        for (Sheet sheet : spreadsheet.getSheets()) {
            if (sheet.getProperties().getTitle().equals(sheetName)) {
                return sheet.getProperties().getSheetId();
            }
        }
        return 0;
    }
}
