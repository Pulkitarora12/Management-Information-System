package com.notes.notes.entity.taskModuleEntities;

import com.notes.notes.entity.authEntities.User;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data // CHANGED: removed @Getter/@Setter duplication, @Data is sufficient
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "task_id") // CHANGED: explicit column mapping (prod-safe)
    private Long taskId;

    @Column(name = "task_no", nullable = true, unique = true)
    private String taskNo;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    private TaskPriority priority;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    private LocalDate dueDate;

    @ManyToOne
    @JoinColumn(name = "creator_id", nullable = false)
    // unchanged, already correct
    private User creator;

    @Column(name = "created_at", nullable = false, updatable = false)
    // CHANGED: matches NOT NULL constraint in prod
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    // CHANGED: explicit mapping
    private LocalDateTime updatedAt;

    @ManyToOne
    @JoinColumn(name = "template_id")
    private TaskTemplate sourceTemplate;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        // CHANGED: ensures created_at is never NULL
        this.status = TaskStatus.CREATED;
        // CHANGED: status default moved to entity lifecycle
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        // unchanged but important for prod consistency
    }

    @PostPersist
    private void generateTaskNo() {
        this.taskNo = "TSK-" + this.taskId;
    }

    public enum TaskPriority { LOW, MEDIUM, HIGH, CRITICAL }
    public enum TaskStatus { CREATED, IN_PROGRESS, REVIEW, SUBMITTED, CLOSED }
}
