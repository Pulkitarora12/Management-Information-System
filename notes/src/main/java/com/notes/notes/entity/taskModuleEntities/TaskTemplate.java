package com.notes.notes.entity.taskModuleEntities;

import com.notes.notes.entity.authEntities.User;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "task_template")
public class TaskTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Long flashTime;

    @Column(nullable = false)
    private Task.TaskPriority priority;

    private boolean isActive = false;

    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Enumerated(EnumType.STRING)
    private TaskFrequency taskFrequency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "recurrence_day")
    private Integer recurrenceDay;
    // WEEKLY: 1-7 (day of week, 1=Monday, 7=Sunday)
    // MONTHLY: 1-31 (day of month)
    // YEARLY: 1-31 (day of the specified month)
    // DAILY: null

    @Column(name = "recurrence_month")
    private Integer recurrenceMonth;
    // ONLY for YEARLY: 1-12 (January=1, December=12)
    // For other frequencies: null

    @Column(name = "next_run_date")
    private LocalDate nextRunDate;

    @Column(name = "final_date", nullable = false)
    private LocalDate finalDate; // Mandatory - template auto-deactivation date

    @ManyToOne
    @JoinColumn(name = "main_assignee_id")
    private User mainAssignee;

    @ManyToMany
    @JoinTable(
            name = "template_assignees",
            joinColumns = @JoinColumn(name = "template_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private List<User> assignees;

    @OneToMany(mappedBy = "sourceTemplate")
    private List<Task> tasks;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public enum TaskFrequency { DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY}
}