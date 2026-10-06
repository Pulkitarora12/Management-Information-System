package com.notes.notes.service.taskModuleServices;

import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskStatusHistory;

import java.util.List;

public interface TaskStatusHistoryService {

    // Fetch status history of a task (latest first)
    List<TaskStatusHistory> getHistoryByTask(Task task);
}
