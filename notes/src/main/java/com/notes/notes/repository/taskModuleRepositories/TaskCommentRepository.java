package com.notes.notes.repository.taskModuleRepositories;

import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskCommentRepository extends JpaRepository<TaskComment, Long> {

    // Fetch all comments for a specific task, oldest first (like a chat)
    List<TaskComment> findByTaskOrderByChangedAtAsc(Task task);

    void deleteByTask(Task task);
}