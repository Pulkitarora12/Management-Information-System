package com.notes.notes.validator;

import com.notes.notes.dto.taskManagementDTO.TemplateDTO;
import com.notes.notes.entity.taskModuleEntities.TaskTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class TaskTemplateValidator {

    public void validateRecurrenceFields(TemplateDTO dto) {

        if (dto.getTaskFrequency() == null) {
            throw new IllegalArgumentException("Task frequency is required");
        }

        // 🔒 Date sanity checks (generic, not frequency-specific)
        if (dto.getStartDate() == null || dto.getFinalDate() == null) {
            throw new IllegalArgumentException("Start date and final date are required");
        }

        if (dto.getFinalDate().isBefore(dto.getStartDate())) {
            throw new IllegalArgumentException("Final date cannot be before start date");
        }

        TaskTemplate.TaskFrequency frequency = dto.getTaskFrequency();

        switch (frequency) {

            case DAILY:
                if (dto.getRecurrenceDay() != null || dto.getRecurrenceMonth() != null) {
                    throw new IllegalArgumentException(
                            "DAILY frequency must not have recurrence day or month"
                    );
                }
                break;

            case WEEKLY:
                if (dto.getRecurrenceDay() == null) {
                    throw new IllegalArgumentException(
                            "WEEKLY frequency requires recurrence day (1–7, Monday–Sunday)"
                    );
                }
                if (dto.getRecurrenceDay() < 1 || dto.getRecurrenceDay() > 7) {
                    throw new IllegalArgumentException(
                            "For WEEKLY, recurrence day must be between 1 (Monday) and 7 (Sunday)"
                    );
                }
                if (dto.getRecurrenceMonth() != null) {
                    throw new IllegalArgumentException(
                            "WEEKLY frequency must not have recurrence month"
                    );
                }
                break;

            case MONTHLY:
            case QUARTERLY:
                if (dto.getRecurrenceDay() == null) {
                    throw new IllegalArgumentException(
                            frequency + " frequency requires recurrence day (1–31)"
                    );
                }
                if (dto.getRecurrenceDay() < 1 || dto.getRecurrenceDay() > 31) {
                    throw new IllegalArgumentException(
                            "For " + frequency + ", recurrence day must be between 1 and 31"
                    );
                }
                if (dto.getRecurrenceMonth() != null) {
                    throw new IllegalArgumentException(
                            frequency + " frequency must not have recurrence month"
                    );
                }
                break;

            case YEARLY:
                if (dto.getRecurrenceDay() == null || dto.getRecurrenceMonth() == null) {
                    throw new IllegalArgumentException(
                            "YEARLY frequency requires both recurrence day and month"
                    );
                }
                if (dto.getRecurrenceDay() < 1 || dto.getRecurrenceDay() > 31) {
                    throw new IllegalArgumentException(
                            "For YEARLY, recurrence day must be between 1 and 31"
                    );
                }
                if (dto.getRecurrenceMonth() < 1 || dto.getRecurrenceMonth() > 12) {
                    throw new IllegalArgumentException(
                            "For YEARLY, recurrence month must be between 1 and 12"
                    );
                }
                break;

            default:
                throw new IllegalArgumentException("Invalid task frequency");
        }
    }
}
