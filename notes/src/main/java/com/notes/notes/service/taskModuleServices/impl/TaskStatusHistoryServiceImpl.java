package com.notes.notes.service.taskModuleServices.impl;

import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskStatusHistory;
import com.notes.notes.repository.taskModuleRepositories.TaskStatusHistoryRepository;
import com.notes.notes.service.taskModuleServices.TaskStatusHistoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskStatusHistoryServiceImpl implements TaskStatusHistoryService {

    private final TaskStatusHistoryRepository taskStatusHistoryRepository;

    public TaskStatusHistoryServiceImpl(TaskStatusHistoryRepository taskStatusHistoryRepository) {
        this.taskStatusHistoryRepository = taskStatusHistoryRepository;
    }

    @Override
    public List<TaskStatusHistory> getHistoryByTask(Task task) {
        return taskStatusHistoryRepository.findByTaskOrderByTimestampDesc(task);
    }
}
