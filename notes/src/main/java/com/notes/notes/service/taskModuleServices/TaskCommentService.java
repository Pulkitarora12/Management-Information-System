package com.notes.notes.service.taskModuleServices;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskComment;

import java.util.List;

public interface TaskCommentService {

    // Add a comment to a task
    TaskComment addComment(Task task, User sender, String content, String attachmentUrl);

    // Fetch all comments of a task (oldest first)
    List<TaskComment> getCommentsByTask(Task task);
}
