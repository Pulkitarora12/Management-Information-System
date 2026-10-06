package com.notes.notes.events;

import com.notes.notes.entity.moduleEntities.RMIR;
import org.springframework.context.ApplicationEvent;

public class RMIREvent extends ApplicationEvent {

    private final RMIR rmir;
    private final String action; // "SAVE", "UPDATE", "DELETE"

    public RMIREvent(Object source, RMIR rmir, String action) {
        super(source);
        this.rmir = rmir;
        this.action = action;
    }

    public RMIR getRmir() {
        return rmir;
    }

    public String getAction() {
        return action;
    }
}