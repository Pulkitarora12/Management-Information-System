package com.notes.notes.service.googleSheetServices;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.*;
import com.notes.notes.entity.authEntities.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class GoogleSheetsService {

    @Autowired
    private Sheets sheetsClient;

    @Value("${google.sheets.spreadsheet.id}")
    private String spreadsheetId;

    private static final String SHEET_NAME = "Users"; // Change if your sheet has a different name

    // CREATE: Add new user to Google Sheets
    public void addUserToSheet(User user) {
        try {
            List<Object> row = convertUserToRow(user);
            ValueRange body = new ValueRange().setValues(Arrays.asList(row));

            sheetsClient.spreadsheets().values()
                    .append(spreadsheetId, SHEET_NAME, body)
                    .setValueInputOption("RAW")
                    .execute();

            System.out.println("✅ User added to Google Sheets: " + user.getUserName());
        } catch (IOException e) {
            System.err.println("❌ Error adding user to Google Sheets: " + e.getMessage());
        }
    }

    // UPDATE: Update existing user in Google Sheets
    public void updateUserInSheet(User user) {
        try {
            // First, find the row number of this user by their ID
            int rowNumber = findRowByUserId(user.getUserId());

            if (rowNumber == -1) {
                System.out.println("⚠️ User not found in sheet, adding new row");
                addUserToSheet(user);
                return;
            }

            // Update the row
            List<Object> row = convertUserToRow(user);
            ValueRange body = new ValueRange().setValues(Arrays.asList(row));
            String range = SHEET_NAME + "!A" + rowNumber + ":S" + rowNumber; // A to S columns

            sheetsClient.spreadsheets().values()
                    .update(spreadsheetId, range, body)
                    .setValueInputOption("RAW")
                    .execute();

            System.out.println("✅ User updated in Google Sheets: " + user.getUserName());
        } catch (IOException e) {
            System.err.println("❌ Error updating user in Google Sheets: " + e.getMessage());
        }
    }

    // DELETE: Delete user from Google Sheets
    public void deleteUserFromSheet(Long userId) {
        try {
            int rowNumber = findRowByUserId(userId);

            if (rowNumber == -1) {
                System.out.println("⚠️ User not found in sheet");
                return;
            }

            // Delete the row
            DeleteDimensionRequest deleteRequest = new DeleteDimensionRequest()
                    .setRange(new DimensionRange()
                            .setSheetId(0) // 0 is the default sheet ID
                            .setDimension("ROWS")
                            .setStartIndex(rowNumber - 1) // 0-indexed
                            .setEndIndex(rowNumber));

            List<Request> requests = new ArrayList<>();
            requests.add(new Request().setDeleteDimension(deleteRequest));

            BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest()
                    .setRequests(requests);

            sheetsClient.spreadsheets().batchUpdate(spreadsheetId, body).execute();

            System.out.println("✅ User deleted from Google Sheets: ID " + userId);
        } catch (IOException e) {
            System.err.println("❌ Error deleting user from Google Sheets: " + e.getMessage());
        }
    }

    // Helper method: Convert User object to a row of values
    // Helper method: Convert User object to a row of values
    private List<Object> convertUserToRow(User user) {
        List<Object> row = new ArrayList<>();
        row.add(user.getUserId());
        row.add(user.getUserName());
        row.add(user.getEmail());
        row.add(user.isVerified());
        row.add(user.isEnabled());
        row.add(user.isAccountNonLocked());
        row.add(user.isAccountNonExpired());
        row.add(user.isCredentialsNonExpired());
        row.add(user.getCredentialsExpiryDate() != null ? user.getCredentialsExpiryDate().toString() : "");
        row.add(user.getAccountExpiryDate() != null ? user.getAccountExpiryDate().toString() : "");
        row.add(user.getTwoFactorSecret() != null ? user.getTwoFactorSecret() : "");
        row.add(user.isTwoFactorEnabled());
        row.add(user.getSignUpMethod() != null ? user.getSignUpMethod() : "");
        row.add(user.getRole() != null ? user.getRole().getRoleName().name() : ""); // ✅ FIXED
        row.add(user.getCreatedDate() != null ? user.getCreatedDate().toString() : "");
        row.add(user.getUpdatedDate() != null ? user.getUpdatedDate().toString() : "");
        row.add(user.getEmployeeFullName() != null ? user.getEmployeeFullName() : "");
        row.add(user.getEmployeeDepartment() != null ? user.getEmployeeDepartment() : "");
        row.add(user.getEmployeePhone() != null ? user.getEmployeePhone() : "");
        return row;
    }

    // Helper method: Find row number by User ID
    private int findRowByUserId(Long userId) throws IOException {
        String range = SHEET_NAME + "!A:A"; // Column A contains userId
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
                if (cellValue.equals(userId.toString())) {
                    return i + 1; // Sheet rows are 1-indexed
                }
            }
        }
        return -1;
    }
}