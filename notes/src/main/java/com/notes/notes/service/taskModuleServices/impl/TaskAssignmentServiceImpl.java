package com.notes.notes.service.taskModuleServices.impl;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskAssignment;
import com.notes.notes.repository.taskModuleRepositories.TaskAssignmentRepository;
import com.notes.notes.service.emailService.EmailService;
import com.notes.notes.service.taskModuleServices.TaskAssignmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TaskAssignmentServiceImpl implements TaskAssignmentService {

    private final TaskAssignmentRepository taskAssignmentRepository;
    private final EmailService emailService;

    public TaskAssignmentServiceImpl(
            TaskAssignmentRepository taskAssignmentRepository,
            EmailService emailService
    ) {
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.emailService = emailService;
    }

    @Override
    @Transactional
    public TaskAssignment assignMainAssignee(Task task, User user) {

        // 1. Remove existing MAIN_ASSIGNEE (if any)
        TaskAssignment existingMain =
                taskAssignmentRepository.findByTaskAndRoleType(
                        task, TaskAssignment.AssignmentRole.MAIN_ASSIGNEE);

        if (existingMain != null &&
                existingMain.getUser().getUserId().equals(user.getUserId())) {
            return existingMain;
        }

        if (existingMain != null) {
            taskAssignmentRepository.delete(existingMain);
        }

        // 2. If the new MAIN_ASSIGNEE is ADMIN,
        //    remove that SAME user from SUPPORTING_ASSIGNEE
        if (user.getRole() != null
                && user.getRole().getRoleName() == AppRole.ROLE_ADMIN) {

            TaskAssignment adminSupporting =
                    taskAssignmentRepository.findByTaskAndUserAndRoleType(
                            task,
                            user,
                            TaskAssignment.AssignmentRole.SUPPORTING
                    );

            if (adminSupporting != null) {
                taskAssignmentRepository.delete(adminSupporting);
            }
        }

        // 3. Assign new MAIN_ASSIGNEE
        TaskAssignment assignment = new TaskAssignment();
        assignment.setTask(task);
        assignment.setUser(user);
        assignment.setRoleType(TaskAssignment.AssignmentRole.MAIN_ASSIGNEE);

        TaskAssignment savedAssignment =
                taskAssignmentRepository.save(assignment);

        // 4. Notify newly assigned user (if not creator)
        User creator = task.getCreator();

        if (!user.getUserId().equals(creator.getUserId())) {

            String email = user.getEmail();

            if (email != null && !email.isBlank()) {
                emailService.sendTaskAssignmentNotification(
                        email,
                        task,
                        TaskAssignment.AssignmentRole.MAIN_ASSIGNEE
                );
            }
        }

        return savedAssignment;
    }


    @Override
    @Transactional
    public TaskAssignment addSupportingAssignee(Task task, User user) {

        if (taskAssignmentRepository.existsByTaskAndUser(task, user)) {
            throw new IllegalStateException("User already assigned to task");
        }

        TaskAssignment assignment = new TaskAssignment();
        assignment.setTask(task);
        assignment.setUser(user);
        assignment.setRoleType(TaskAssignment.AssignmentRole.SUPPORTING);

        TaskAssignment savedAssignment =
                taskAssignmentRepository.save(assignment);

        // Notify newly assigned user
        User creator = task.getCreator();

        if (!user.getUserId().equals(creator.getUserId())) {

            String email = user.getEmail();

            if (email != null && !email.isBlank()) {
                emailService.sendTaskAssignmentNotification(
                        email,
                        task,
                        TaskAssignment.AssignmentRole.SUPPORTING
                );
            }
        }

        return savedAssignment;
    }


    @Override
    @Transactional
    public void removeAssignee(Task task, User user) {

        List<TaskAssignment> assignments = taskAssignmentRepository.findByTask(task);

        assignments.stream()
                .filter(a -> a.getUser().equals(user))
                .forEach(taskAssignmentRepository::delete);
    }

    @Override
    public List<TaskAssignment> getAssigneesByTask(Task task) {
        return taskAssignmentRepository.findByTask(task);
    }

    @Override
    public boolean isUserAssignedToTask(Task task, User user) {
        return taskAssignmentRepository.existsByTaskAndUser(task, user);
    }

    @Override
    public List<TaskAssignment> getAssignmentsByUser(User user) {
        return taskAssignmentRepository.findByUser(user);
    }

    @Override
    public TaskAssignment getAssignment(Task task, User user) {
        return taskAssignmentRepository.findByTaskAndUser(task, user);
    }
}
