package com.notes.notes.service.googleSheetServices;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.*;
import com.notes.notes.entity.moduleEntities.DowntimeEntry;
import com.notes.notes.entity.moduleEntities.ProductionEntry;
import com.notes.notes.entity.moduleEntities.TimeSlot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class ProductionGoogleSheetsService {

    @Autowired
    private Sheets sheetsClient;

    @Value("${google.sheets.spreadsheet.id}")
    private String spreadsheetId;

    private static final String PRODUCTION_SHEET = "ProductionEntry";
    private static final String DOWNTIME_SHEET = "DowntimeEntries";

    // ==================== PRODUCTION ENTRY OPERATIONS ====================

    /**
     * CREATE: Add new production entry to Google Sheets
     * Also adds all associated timeslots and downtime entries
     */
    public void addProductionEntryToSheet(ProductionEntry entry) {
        try {
            List<Object> row = convertProductionEntryToRow(entry);
            ValueRange body = new ValueRange().setValues(Arrays.asList(row));

            sheetsClient.spreadsheets().values()
                    .append(spreadsheetId, PRODUCTION_SHEET, body)
                    .setValueInputOption("RAW")
                    .execute();

            System.out.println("✅ ProductionEntry added: " + entry.getId());

            // Downtime entries still added separately
            if (entry.getDowntimeEntries() != null) {
                for (DowntimeEntry d : entry.getDowntimeEntries()) {
                    addDowntimeEntryToSheet(d);
                }
            }

        } catch (IOException e) {
            System.err.println("❌ Error: " + e.getMessage());
        }
    }


    /**
     * UPDATE: Update existing production entry in Google Sheets
     * Also updates all associated timeslots and downtime entries
     */
    public void updateProductionEntryInSheet(ProductionEntry entry) {
        try {
            int rowNumber = findRowByProductionEntryId(entry.getId());

            if (rowNumber == -1) {
                addProductionEntryToSheet(entry);
                return;
            }

            List<Object> row = convertProductionEntryToRow(entry);
            ValueRange body = new ValueRange().setValues(Arrays.asList(row));

            String range = PRODUCTION_SHEET + "!A" + rowNumber + ":T" + rowNumber;

            sheetsClient.spreadsheets().values()
                    .update(spreadsheetId, range, body)
                    .setValueInputOption("RAW")
                    .execute();

            System.out.println("✅ ProductionEntry updated: " + entry.getId());

            // remove old downtime and re-add
            deleteAllDowntimeEntriesForProductionEntry(entry.getId());
            if (entry.getDowntimeEntries() != null) {
                for (DowntimeEntry d : entry.getDowntimeEntries()) {
                    addDowntimeEntryToSheet(d);
                }
            }

        } catch (IOException e) {
            System.err.println("❌ Error updating: " + e.getMessage());
        }
    }


    /**
     * DELETE: Delete production entry from Google Sheets
     * Also deletes all associated timeslots and downtime entries
     */
    public void deleteProductionEntryFromSheet(Long productionEntryId) {
        try {

            // Delete all downtime entries
            deleteAllDowntimeEntriesForProductionEntry(productionEntryId);

            // Delete main production entry
            int rowNumber = findRowByProductionEntryId(productionEntryId);

            if (rowNumber == -1) {
                System.out.println("⚠️ ProductionEntry not found in sheet");
                return;
            }

            DeleteDimensionRequest deleteRequest = new DeleteDimensionRequest()
                    .setRange(new DimensionRange()
                            .setSheetId(getSheetId(PRODUCTION_SHEET))
                            .setDimension("ROWS")
                            .setStartIndex(rowNumber - 1)
                            .setEndIndex(rowNumber));

            List<Request> requests = new ArrayList<>();
            requests.add(new Request().setDeleteDimension(deleteRequest));

            BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest()
                    .setRequests(requests);

            sheetsClient.spreadsheets().batchUpdate(spreadsheetId, body).execute();

            System.out.println("✅ ProductionEntry deleted from Google Sheets: ID " + productionEntryId);

        } catch (IOException e) {
            System.err.println("❌ Error deleting ProductionEntry from Google Sheets: " + e.getMessage());
        }
    }

    // ==================== DOWNTIME ENTRY OPERATIONS ====================

    /**
     * Add a single downtime entry to Google Sheets
     */
    private void addDowntimeEntryToSheet(DowntimeEntry downtime) {
        try {
            List<Object> row = convertDowntimeEntryToRow(downtime);
            ValueRange body = new ValueRange().setValues(Arrays.asList(row));

            sheetsClient.spreadsheets().values()
                    .append(spreadsheetId, DOWNTIME_SHEET, body)
                    .setValueInputOption("RAW")
                    .execute();

            System.out.println("  ✅ DowntimeEntry added: ID " + downtime.getId());

        } catch (IOException e) {
            System.err.println("  ❌ Error adding DowntimeEntry: " + e.getMessage());
        }
    }

    /**
     * Delete all downtime entries for a specific production entry
     */
    private void deleteAllDowntimeEntriesForProductionEntry(Long productionEntryId) throws IOException {
        String range = DOWNTIME_SHEET + "!B:B"; // Column B contains productionEntryId
        ValueRange response = sheetsClient.spreadsheets().values()
                .get(spreadsheetId, range)
                .execute();

        List<List<Object>> values = response.getValues();
        if (values == null || values.isEmpty()) {
            return;
        }

        List<Request> deleteRequests = new ArrayList<>();

        // Collect all rows to delete (iterate backwards)
        for (int i = values.size() - 1; i >= 0; i--) {
            List<Object> row = values.get(i);
            if (row != null && !row.isEmpty()) {
                String cellValue = row.get(0).toString();
                if (cellValue.equals(productionEntryId.toString())) {
                    DeleteDimensionRequest deleteRequest = new DeleteDimensionRequest()
                            .setRange(new DimensionRange()
                                    .setSheetId(getSheetId(DOWNTIME_SHEET))
                                    .setDimension("ROWS")
                                    .setStartIndex(i)
                                    .setEndIndex(i + 1));

                    deleteRequests.add(new Request().setDeleteDimension(deleteRequest));
                }
            }
        }

        if (!deleteRequests.isEmpty()) {
            BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest()
                    .setRequests(deleteRequests);
            sheetsClient.spreadsheets().batchUpdate(spreadsheetId, body).execute();
            System.out.println("  ✅ Deleted " + deleteRequests.size() + " DowntimeEntries for ProductionEntry: " + productionEntryId);
        }
    }

    // ==================== CONVERTER METHODS ====================

    /**
     * Convert ProductionEntry to row of values (13 columns: A-M)
     */
    private List<Object> convertProductionEntryToRow(ProductionEntry entry) {
        List<Object> row = new ArrayList<>();

        row.add(entry.getId());
        row.add(entry.getDate() != null ? entry.getDate().toString() : "");
        row.add(entry.getShift());
        row.add(entry.getLine());
        row.add(entry.getMachine());
        row.add(entry.getOperation());
        row.add(entry.getOperator1());
        row.add(entry.getOperator2());
        row.add(entry.getPartNo());
        row.add(entry.getPartName());
        row.add(entry.getRemarks());
        row.add(entry.getSheetSize());
        row.add(entry.getInspector());

        // ===== TimeSlot Fields (N – T) =====
        TimeSlot ts = entry.getTimeSlot();

        if (ts != null) {
            row.add(ts.getFromTime() != null ? ts.getFromTime().toString() : "");
            row.add(ts.getToTime() != null ? ts.getToTime().toString() : "");
            row.add(ts.getProduced());
            row.add(ts.getSegregated());
            row.add(ts.getRejected());
            row.add(ts.getReason());
            row.add(ts.getRemarks());
        } else {
            // Add 7 empty columns if no timeslot
            row.add(""); row.add(""); row.add("");
            row.add(""); row.add(""); row.add(""); row.add("");
        }

        return row;
    }

    /**
     * Convert DowntimeEntry to row of values (4 columns: A-D)
     */
    private List<Object> convertDowntimeEntryToRow(DowntimeEntry downtime) {
        List<Object> row = new ArrayList<>();
        row.add(downtime.getId());
        row.add(downtime.getProductionEntry() != null ? downtime.getProductionEntry().getId() : "");
        row.add(downtime.getReason() != null ? downtime.getReason() : "");
        row.add(downtime.getMinutes() != null ? downtime.getMinutes() : "");
        return row;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Find row number by ProductionEntry ID
     */
    private int findRowByProductionEntryId(Long id) throws IOException {
        String range = PRODUCTION_SHEET + "!A:A";
        ValueRange response = sheetsClient.spreadsheets().values()
                .get(spreadsheetId, range)
                .execute();

        List<List<Object>> values = response.getValues();
        if (values == null || values.isEmpty()) {
            return -1;
        }

        for (int i = 0; i < values.size(); i++) {
            List<Object> row = values.get(i);
            if (row != null && !row.isEmpty()) {
                String cellValue = row.get(0).toString();
                if (cellValue.equals(id.toString())) {
                    return i + 1; // Sheet rows are 1-indexed
                }
            }
        }
        return -1;
    }

    /**
     * Get sheet ID by sheet name (needed for delete operations)
     */
    private Integer getSheetId(String sheetName) throws IOException {
        Spreadsheet spreadsheet = sheetsClient.spreadsheets()
                .get(spreadsheetId)
                .execute();

        for (Sheet sheet : spreadsheet.getSheets()) {
            if (sheet.getProperties().getTitle().equals(sheetName)) {
                return sheet.getProperties().getSheetId();
            }
        }

        // Default to 0 if not found (for backwards compatibility)
        return 0;
    }
}