package com.notes.notes.dto.taskManagementDTO;

import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskTemplate;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class TemplateDTO {

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Description is required")
    private String description;

    @NotNull(message = "Flash time is required")
    @Min(value = 0, message = "Flash time must be at least 0 days")
    private Long flashTime;

    @NotNull(message = "Priority is required")
    private Task.TaskPriority priority;

    @NotNull(message = "Task frequency is required")
    private TaskTemplate.TaskFrequency taskFrequency;

    @Min(value = 1, message = "Recurrence day must be at least 1")
    @Max(value = 31, message = "Recurrence day cannot exceed 31")
    private Integer recurrenceDay;

    @Min(value = 1, message = "Recurrence month must be between 1 and 12")
    @Max(value = 12, message = "Recurrence month must be between 1 and 12")
    private Integer recurrenceMonth;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "Final date is required")
    @Future(message = "Final date must be in the future")
    private LocalDate finalDate;

    @NotNull(message = "Main assignee is required")
    private Long mainAssigneeId;

    @NotEmpty(message = "At least one supporting assignee is required")
    private List<Long> supportingAssigneeIds;
}
