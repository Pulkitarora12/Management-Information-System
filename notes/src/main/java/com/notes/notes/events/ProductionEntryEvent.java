package com.notes.notes.events;

import com.notes.notes.entity.moduleEntities.ProductionEntry;
import org.springframework.context.ApplicationEvent;

public class ProductionEntryEvent extends ApplicationEvent {

    private final ProductionEntry productionEntry;
    private final String action; // "SAVE" or "DELETE"

    public ProductionEntryEvent(Object source, ProductionEntry productionEntry, String action) {
        super(source);
        this.productionEntry = productionEntry;
        this.action = action;
    }

    public ProductionEntry getProductionEntry() {
        return productionEntry;
    }

    public String getAction() {
        return action;
    }
}