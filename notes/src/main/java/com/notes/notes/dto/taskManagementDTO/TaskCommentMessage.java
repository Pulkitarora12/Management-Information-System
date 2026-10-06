package com.notes.notes.dto.taskManagementDTO;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TaskCommentMessage {
    private Long taskId;
    private String senderName;
    private String content;
    private LocalDateTime timestamp;
}