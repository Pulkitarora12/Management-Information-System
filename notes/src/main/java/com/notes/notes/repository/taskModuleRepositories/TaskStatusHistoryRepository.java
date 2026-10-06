package com.notes.notes.repository.taskModuleRepositories;

import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskStatusHistoryRepository extends JpaRepository<TaskStatusHistory, Long> {

    // Fetch the history of a specific task, ordered by latest changes first
    List<TaskStatusHistory> findByTaskOrderByTimestampDesc(Task task);

    void deleteByTask(Task task);
}