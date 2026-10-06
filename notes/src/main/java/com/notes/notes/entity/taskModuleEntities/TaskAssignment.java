package com.notes.notes.entity.taskModuleEntities;

import com.notes.notes.entity.authEntities.User;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "task_assignments")
public class TaskAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentRole roleType; // MAIN_ASSIGNEE or SUPPORTING

    public enum AssignmentRole { MAIN_ASSIGNEE, SUPPORTING }
}
