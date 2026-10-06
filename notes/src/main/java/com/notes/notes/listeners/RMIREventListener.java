package com.notes.notes.listeners;

import com.notes.notes.events.RMIREvent;
import com.notes.notes.service.googleSheetServices.RMIRGoogleSheetsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class RMIREventListener {

    private static final Logger log = LoggerFactory.getLogger(RMIREventListener.class);
    private static final int MAX_RETRIES = 3;

    @Autowired
    private RMIRGoogleSheetsService rmirGoogleSheetsService;

    @Async
    @EventListener
    public void handleRMIREvent(RMIREvent event) {

        String action = event.getAction();
        Long rmirId = event.getRmir().getId();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                if ("SAVE".equals(action)) {
                    rmirGoogleSheetsService.addRMIRToSheet(event.getRmir());
                } else if ("DELETE".equals(action)) {
                    rmirGoogleSheetsService.deleteRMIRFromSheet(rmirId);
                } else if ("UPDATE".equals(action)) {
                    rmirGoogleSheetsService.updateRMIRInSheet(event.getRmir());
                }
                log.info("Sheets sync successful for RMIR ID: {} | Action: {}", rmirId, action);
                return;

            } catch (Exception e) {
                log.warn("Attempt {}/{} failed for RMIR ID: {} | Action: {} | Reason: {}",
                        attempt, MAX_RETRIES, rmirId, action, e.getMessage());

                if (attempt == MAX_RETRIES) {
                    log.error("All {} attempts failed for RMIR ID: {} | Action: {}",
                            MAX_RETRIES, rmirId, action);
                }
            }
        }
    }
}