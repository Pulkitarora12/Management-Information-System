package com.notes.notes.listeners;

import com.notes.notes.entity.moduleEntities.ProductionEntry;
import com.notes.notes.events.ProductionEntryEvent;
import com.notes.notes.repository.moduleRepo.ProductionEntryRepository;
import com.notes.notes.service.googleSheetServices.ProductionGoogleSheetsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ProductionEntryEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionEntryEventListener.class);
    private static final int MAX_RETRIES = 3;

    @Autowired
    private ProductionGoogleSheetsService productionGoogleSheetsService;

    @Autowired
    private ProductionEntryRepository productionEntryRepository;

    @Async
    @EventListener
    public void handleProductionEntryEvent(ProductionEntryEvent event) {

        String action = event.getAction();
        Long entryId = event.getProductionEntry().getId();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                if ("SAVE".equals(action)) {
                    productionGoogleSheetsService.addProductionEntryToSheet(event.getProductionEntry());
                } else if ("DELETE".equals(action)) {
                    productionGoogleSheetsService.deleteProductionEntryFromSheet(entryId);
                } else if ("UPDATE".equals(action)) {
                    productionGoogleSheetsService.updateProductionEntryInSheet(event.getProductionEntry());
                }
                log.info("Sheets sync successful for ProductionEntry ID: {} | Action: {}", entryId, action);
                return;

            } catch (Exception e) {
                log.warn("Attempt {}/{} failed for ProductionEntry ID: {} | Action: {} | Reason: {}",
                        attempt, MAX_RETRIES, entryId, action, e.getMessage());

                if (attempt == MAX_RETRIES) {
                    log.error("All {} attempts failed for ProductionEntry ID: {} | Action: {}",
                            MAX_RETRIES, entryId, action);
                }
            }
        }
    }
}