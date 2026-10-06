package com.notes.notes.dto.taskManagementDTO;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskStatusMessage {
    private Long taskId;
    private String oldStatus;
    private String newStatus;

    private Long changedByUserId;
    private String changedByUserName;

    private LocalDateTime timestamp;
}
