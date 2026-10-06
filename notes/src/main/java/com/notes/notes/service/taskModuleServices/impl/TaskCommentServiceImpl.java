package com.notes.notes.service.taskModuleServices.impl;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskComment;
import com.notes.notes.repository.taskModuleRepositories.TaskCommentRepository;
import com.notes.notes.service.emailService.EmailService;
import com.notes.notes.service.taskModuleServices.TaskAssignmentService;
import com.notes.notes.service.taskModuleServices.TaskCommentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TaskCommentServiceImpl implements TaskCommentService {

    private final TaskCommentRepository taskCommentRepository;
    private final TaskAssignmentService taskAssignmentService;
    private final EmailService emailService;

    public TaskCommentServiceImpl(
            TaskCommentRepository taskCommentRepository,
            TaskAssignmentService taskAssignmentService,
            EmailService emailService
    ) {
        this.taskCommentRepository = taskCommentRepository;
        this.taskAssignmentService = taskAssignmentService;
        this.emailService = emailService;
    }

    @Override
    @Transactional
    public TaskComment addComment(Task task,
                                  User sender,
                                  String content,
                                  String attachmentUrl) {

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setSender(sender);
        comment.setContent(content);
        comment.setAttachmentUrl(attachmentUrl);

        // 🔹 IMPORTANT: set message type
        if (attachmentUrl != null && !attachmentUrl.isBlank()) {
            comment.setMessageType(TaskComment.MessageType.FILE);
        } else {
            comment.setMessageType(TaskComment.MessageType.TEXT);
        }

        return taskCommentRepository.save(comment);
    }

    @Override
    public List<TaskComment> getCommentsByTask(Task task) {
        return taskCommentRepository.findByTaskOrderByChangedAtAsc(task);
    }
}
