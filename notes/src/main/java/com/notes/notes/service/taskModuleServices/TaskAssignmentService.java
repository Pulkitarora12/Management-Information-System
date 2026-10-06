package com.notes.notes.service.taskModuleServices;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskAssignment;

import java.util.List;

public interface TaskAssignmentService {

    // Assign a MAIN assignee to a task (only one allowed)
    TaskAssignment assignMainAssignee(Task task, User user);

    // Add a SUPPORTING assignee
    TaskAssignment addSupportingAssignee(Task task, User user);

    // Remove an assignee from a task
    void removeAssignee(Task task, User user);

    // Get all assignees of a task
    List<TaskAssignment> getAssigneesByTask(Task task);

    // Check if user is assigned to task (authorization use)
    boolean isUserAssignedToTask(Task task, User user);

    List<TaskAssignment> getAssignmentsByUser(User user);

    TaskAssignment getAssignment(Task task, User user);
}
