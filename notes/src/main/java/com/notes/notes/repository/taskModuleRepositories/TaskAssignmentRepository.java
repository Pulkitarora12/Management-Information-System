package com.notes.notes.repository.taskModuleRepositories;

import com.notes.notes.entity.taskModuleEntities.TaskAssignment;
import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.authEntities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, Long> {

    // 1. Find all assignments for a specific User
    // (Used to populate the "My Tasks" or "Pending Tasks" list)
    List<TaskAssignment> findByUser(User user);

    // 2. Find all employees assigned to a specific Task
    // (Used to show the "Team Members" list on the Task Details page)
    List<TaskAssignment> findByTask(Task task);

    // 3. Check if a specific user is assigned to a specific task
    // (Security Check: Used to verify if a user is allowed to view/chat in the task)
    boolean existsByTaskAndUser(Task task, User user);

    // 4. Find the 'Main Assignee' specifically (Optional)
    // (Useful for the Appraisal system later to find who gets the points)
    TaskAssignment findByTaskAndRoleType(Task task, TaskAssignment.AssignmentRole roleType);

    void deleteByTask(Task task);

    TaskAssignment findByTaskAndUser(Task task, User user);

    TaskAssignment findByTaskAndUserAndRoleType(
            Task task,
            User user,
            TaskAssignment.AssignmentRole roleType
    );
}