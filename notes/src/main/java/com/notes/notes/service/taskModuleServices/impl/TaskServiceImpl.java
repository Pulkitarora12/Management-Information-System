package com.notes.notes.service.taskModuleServices.impl;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskAssignment;
import com.notes.notes.entity.taskModuleEntities.TaskStatusHistory;
import com.notes.notes.entity.taskModuleEntities.TaskTemplate;
import com.notes.notes.repository.authRepo.UserRepository;
import com.notes.notes.repository.taskModuleRepositories.*;
import com.notes.notes.service.taskModuleServices.TaskAssignmentService;
import com.notes.notes.service.taskModuleServices.TaskService;
import com.notes.notes.service.taskModuleServices.TaskTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class TaskServiceImpl implements TaskService {

    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final TaskCommentRepository taskCommentRepository;
    private final TaskStatusHistoryRepository taskStatusHistoryRepository;
    private final UserRepository userRepository;
    private final TaskAssignmentService taskAssignmentService;
    private final TaskTemplateRepository taskTemplateRepository;
    private final TaskTemplateService taskTemplateService;

    public TaskServiceImpl(TaskRepository taskRepository,
                           TaskAssignmentRepository taskAssignmentRepository,
                           TaskCommentRepository taskCommentRepository,
                           TaskStatusHistoryRepository taskStatusHistoryRepository, UserRepository userRepository, TaskAssignmentService taskAssignmentService, TaskTemplateRepository taskTemplateRepository, TaskTemplateService taskTemplateService) {
        this.taskRepository = taskRepository;
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.taskCommentRepository = taskCommentRepository;
        this.taskStatusHistoryRepository = taskStatusHistoryRepository;
        this.userRepository = userRepository;
        this.taskAssignmentService = taskAssignmentService;
        this.taskTemplateRepository = taskTemplateRepository;
        this.taskTemplateService = taskTemplateService;
    }

    @Override
    @Transactional
    public Task createTask(String title,
                           String description,
                           Task.TaskPriority priority,
                           User creator,
                           LocalDate dueDate,
                           Long templateId) {

        Task task = new Task();
        task.setTitle(title);
        task.setDescription(description);
        task.setPriority(priority);
        task.setDueDate(dueDate);
        task.setCreator(creator);

        // CHANGED: status is NOT set here anymore
        // handled in @PrePersist to avoid duplication / inconsistency

        /* ================= TEMPLATE MAPPING ================= */

        if (templateId != null) {
            TaskTemplate template = taskTemplateRepository.findById(templateId)
                    .orElseThrow(() ->
                            new IllegalArgumentException("TaskTemplate not found"));

            if (!template.isActive()) {
                throw new IllegalStateException("TaskTemplate is not active");
            }

            task.setSourceTemplate(template);
        }

        /* ================= SAVE TASK ================= */

        Task savedTask = taskRepository.save(task);

        /* ================= STATUS HISTORY ================= */

        TaskStatusHistory history = new TaskStatusHistory();
        history.setTask(savedTask);
        history.setChangedBy(creator);
        history.setOldStatus(null);
        history.setNewStatus(Task.TaskStatus.CREATED);
        taskStatusHistoryRepository.save(history);

        /* ================= ADMIN ASSIGNMENT ================= */

        List<User> admins =
                userRepository.findByRole_RoleName(AppRole.ROLE_ADMIN);

        for (User admin : admins) {
            TaskAssignment assignment =
                    taskAssignmentService.getAssignment(savedTask, admin);

            if (assignment == null) {
                taskAssignmentService.addSupportingAssignee(savedTask, admin);
            }
        }

        return savedTask;
    }



    @Override
    @Transactional
    public Task updateTaskStatus(Long taskId,
                                 Task.TaskStatus newStatus,
                                 User changedBy) {

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        Task.TaskStatus oldStatus = task.getStatus();

        if (oldStatus == newStatus) {
            return task;
        }

        task.setStatus(newStatus);
        Task updatedTask = taskRepository.save(task);

        // ---- STATUS HISTORY ----
        TaskStatusHistory history = new TaskStatusHistory();
        history.setTask(updatedTask);
        history.setChangedBy(changedBy);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        taskStatusHistoryRepository.save(history);

        // performance points with closed status
        if (newStatus == Task.TaskStatus.CLOSED) {

            int points = calculatePoints(updatedTask);
            TaskAssignment mainAssignee =
                    taskAssignmentRepository.findByTaskAndRoleType(
                            task, TaskAssignment.AssignmentRole.MAIN_ASSIGNEE);
            User main = mainAssignee.getUser();
            main.setPerformancePoints(main.getPerformancePoints() + points);
            userRepository.save(main);
        }

        return updatedTask;
    }

    @Override
    @Transactional
    public void deleteTask(Task task) {

        // 1️⃣ Delete dependent entities first (safe & explicit)
        taskAssignmentRepository.deleteByTask(task);
        taskCommentRepository.deleteByTask(task);
        taskStatusHistoryRepository.deleteByTask(task);

        // 2️⃣ Delete task
        taskRepository.delete(task);
    }

    @Override
    public List<Task> searchAndFilterTasks(
            List<Task> tasks,
            String search,
            Task.TaskPriority priority,
            Task.TaskStatus status,
            String progress
    ) {

        LocalDate today = LocalDate.now();

        return tasks.stream()

                // 🔍 Search: Task No OR Title
                .filter(task ->
                        search == null || search.isBlank()
                                || task.getTaskNo().toLowerCase().contains(search.toLowerCase())
                                || task.getTitle().toLowerCase().contains(search.toLowerCase())
                )

                // 🎯 Priority filter
                .filter(task ->
                        priority == null || task.getPriority() == priority
                )

                // 📌 Status filter
                .filter(task ->
                        status == null || task.getStatus() == status
                )

                // ⏳ Progress filter: PENDING / COMPLETED / OVERDUE
                .filter(task -> {

                    if (progress == null || progress.isBlank()) {
                        return true; // no progress filter
                    }

                    if ("COMPLETED".equalsIgnoreCase(progress)) {
                        return task.getStatus() == Task.TaskStatus.CLOSED;
                    }

                    if ("OVERDUE".equalsIgnoreCase(progress)) {
                        return task.getStatus() != Task.TaskStatus.CLOSED
                                && task.getDueDate() != null
                                && task.getDueDate().isBefore(today);
                    }

                    if ("PENDING".equalsIgnoreCase(progress)) {
                        return task.getStatus() != Task.TaskStatus.CLOSED
                                && (task.getDueDate() == null
                                || !task.getDueDate().isBefore(today));
                    }

                    return true; // unknown value → ignore safely
                })

                .toList();
    }

    @Override
    public Optional<Task> getTaskById(Long taskId) {
        return taskRepository.findById(taskId);
    }

    @Override
    public Optional<Task> getTaskByTaskNo(String taskNo) {
        return taskRepository.findByTaskNo(taskNo);
    }

    @Override
    public List<Task> getTasksCreatedByUser(User creator) {
        return taskRepository.findByCreator(creator);
    }

    @Override
    public List<Task> getTasksByStatus(Task.TaskStatus status) {
        return taskRepository.findByStatus(status);
    }

    private int calculatePoints(Task task) {
        LocalDate today = LocalDate.now();
        LocalDate dueDate = task.getDueDate();

        if (!today.isAfter(dueDate)) {
            return 10; // on time or early
        }

        long overdueDays = today.toEpochDay() - dueDate.toEpochDay();
        return (int) (-overdueDays * 5);
    }

    @Override
    public List<Task> getTasksByTemplate(TaskTemplate template) {
        return taskRepository.findBySourceTemplate(template);
    }

    @Override
    @Transactional
    public Task createTaskFromTemplate(TaskTemplate template, User creator) {

        if (!template.isActive()) {
            throw new IllegalStateException("Template not active");
        }

        Task task = new Task();
        task.setTitle(template.getTitle());
        task.setDescription(template.getDescription());
        task.setPriority(template.getPriority());
        task.setCreator(creator);
        task.setSourceTemplate(template);

        /* ================= DUE DATE (FIXED) ================= */
        // nextRunDate = creation date
        // dueDate = creation date + flashTime
        LocalDate runDate = template.getNextRunDate() != null ? template.getNextRunDate() : LocalDate.now();
        LocalDate dueDate = runDate.plusDays(template.getFlashTime());
        task.setDueDate(dueDate);

        /* ================= SAVE TASK ================= */
        Task savedTask = taskRepository.save(task);

        /* ================= MAIN ASSIGNEE ================= */
        User mainAssignee = template.getMainAssignee();
        if (mainAssignee != null) {
            taskAssignmentService.assignMainAssignee(savedTask, mainAssignee);
        }

        /* ============== SUPPORTING ASSIGNEES ============== */
        if (template.getAssignees() != null) {
            for (User user : template.getAssignees()) {
                taskAssignmentService.addSupportingAssignee(savedTask, user);
            }
        }

        /* ================= STATUS HISTORY ================= */
        TaskStatusHistory history = new TaskStatusHistory();
        history.setTask(savedTask);
        history.setChangedBy(creator);
        history.setOldStatus(null);
        history.setNewStatus(Task.TaskStatus.CREATED);
        taskStatusHistoryRepository.save(history);

        /* ================= ADMIN ASSIGNMENT ================= */
        List<User> admins =
                userRepository.findByRole_RoleName(AppRole.ROLE_ADMIN);

        for (User admin : admins) {
            TaskAssignment assignment =
                    taskAssignmentService.getAssignment(savedTask, admin);

            if (assignment == null) {
                taskAssignmentService.addSupportingAssignee(savedTask, admin);
            }
        }

        return savedTask;
    }

    @Override
    @Transactional
    public int generateTasksForTemplate(TaskTemplate template, User creator) {
        LocalDate today = LocalDate.now();
        int generatedCount = 0;
        int maxBackfillLimit = 100; // safety cap to prevent timeouts or infinite loops

        while (template.isActive() && template.getNextRunDate() != null && !template.getNextRunDate().isAfter(today)) {
            if (generatedCount >= maxBackfillLimit) {
                break;
            }
            createTaskFromTemplate(template, creator);
            taskTemplateService.calculateAndSetNextRunDate(template);
            taskTemplateRepository.save(template);
            generatedCount++;
        }
        return generatedCount;
    }
}