package com.notes.notes.entity.taskModuleEntities;

import com.notes.notes.entity.authEntities.User;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "task_status_history")
public class TaskStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne
    @JoinColumn(name = "changed_by", nullable = false)
    private User changedBy; // Who clicked the button

    @Enumerated(EnumType.STRING)
    private Task.TaskStatus oldStatus;

    @Enumerated(EnumType.STRING)
    private Task.TaskStatus newStatus;

    private LocalDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        this.timestamp = LocalDateTime.now();
    }
}
