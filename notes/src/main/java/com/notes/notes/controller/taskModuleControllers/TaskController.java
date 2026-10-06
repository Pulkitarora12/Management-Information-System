package com.notes.notes.controller.taskModuleControllers;

import com.notes.notes.dto.taskManagementDTO.TaskCommentMessage;
import com.notes.notes.dto.taskManagementDTO.TaskStatusMessage;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskAssignment;
import com.notes.notes.entity.taskModuleEntities.TaskComment;
import com.notes.notes.entity.taskModuleEntities.TaskTemplate;
import com.notes.notes.service.authServices.UserService;
import com.notes.notes.service.taskModuleServices.*;
import com.notes.notes.util.TimeUtil;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskAssignmentService taskAssignmentService;
    private final TaskCommentService taskCommentService;
    private final TaskStatusHistoryService taskStatusHistoryService;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserService userService;
    private final TaskTemplateService taskTemplateService;

    public TaskController(TaskService taskService,
                          TaskAssignmentService taskAssignmentService,
                          TaskCommentService taskCommentService,
                          TaskStatusHistoryService taskStatusHistoryService,
                          UserService userService,
                          SimpMessagingTemplate messagingTemplate, TaskTemplateService taskTemplateService) {
        this.taskService = taskService;
        this.taskAssignmentService = taskAssignmentService;
        this.taskCommentService = taskCommentService;
        this.taskStatusHistoryService = taskStatusHistoryService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;  // ADD THIS
        this.taskTemplateService = taskTemplateService;
    }

    @GetMapping
    public String listOpenTasks(Model model,
                                @RequestParam(required = false) String search,
                                @RequestParam(required = false) Task.TaskPriority priority,
                                @RequestParam(required = false) Task.TaskStatus status,
                                @RequestParam(required = false) String progress,
                                @ModelAttribute("loggedInUser") User loggedInUser) {

        List<Task> createdTasks = taskService.getTasksCreatedByUser(loggedInUser)
                .stream()
                .filter(task -> task.getStatus() != Task.TaskStatus.CLOSED)
                .toList();

        createdTasks = taskService.searchAndFilterTasks(
                createdTasks,
                search,
                priority,
                status,
                progress
        );

        model.addAttribute("createdTasks", createdTasks);

        List<Task> assignedTasks = taskAssignmentService
                .getAssignmentsByUser(loggedInUser)
                .stream()
                .map(TaskAssignment::getTask)
                .filter(task -> task.getStatus() != Task.TaskStatus.CLOSED)
                .toList();

        assignedTasks = taskService.searchAndFilterTasks(
                assignedTasks,
                search,
                priority,
                status,
                progress
        );

        model.addAttribute("assignedTasks", assignedTasks);

        // ================= UI STATE =================
        model.addAttribute("search", search);
        model.addAttribute("selectedPriority", priority);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedProgress", progress);
        model.addAttribute("performancePoints", loggedInUser.getPerformancePoints());
        model.addAttribute("pageType", "OPEN"); // useful for button toggle

        return "tasks/list";
    }

    @GetMapping("/closed")
    public String listClosedTasks(Model model,
                                  @RequestParam(required = false) String search,
                                  @RequestParam(required = false) Task.TaskPriority priority,
                                  @RequestParam(required = false) String progress,
                                  @ModelAttribute("loggedInUser") User loggedInUser) {

        // ================= CREATED BY ME (CLOSED) =================
        List<Task> createdTasks = taskService.getTasksCreatedByUser(loggedInUser)
                .stream()
                .filter(task -> task.getStatus() == Task.TaskStatus.CLOSED)
                .toList();

        createdTasks = taskService.searchAndFilterTasks(
                createdTasks,
                search,
                priority,
                Task.TaskStatus.CLOSED,
                progress
        );

        model.addAttribute("createdTasks", createdTasks);

        // ================= ASSIGNED TO ME (CLOSED) =================
        List<Task> assignedTasks = taskAssignmentService
                .getAssignmentsByUser(loggedInUser)
                .stream()
                .map(TaskAssignment::getTask)
                .filter(task -> task.getStatus() == Task.TaskStatus.CLOSED)
                .toList();

        assignedTasks = taskService.searchAndFilterTasks(
                assignedTasks,
                search,
                priority,
                Task.TaskStatus.CLOSED,
                progress
        );

        model.addAttribute("assignedTasks", assignedTasks);

        // ================= UI STATE =================
        model.addAttribute("search", search);
        model.addAttribute("selectedPriority", priority);
        model.addAttribute("selectedProgress", progress);
        model.addAttribute("pageType", "CLOSED"); // for UI toggle / heading
        model.addAttribute("performancePoints", loggedInUser.getPerformancePoints());

        return "tasks/list"; // reuse same view
    }



    @GetMapping("/new")
    public String createTaskPage(
            @RequestParam(required = false) Long templateId,
            Model model
    ) {

        if (templateId != null) {

            TaskTemplate template = taskTemplateService.findById(templateId);

            // Safety: allow only ACTIVE templates
            if (!template.isActive()) {
                throw new IllegalStateException("Template is inactive");
            }

            model.addAttribute("prefillTitle", template.getTitle());
            model.addAttribute("prefillDescription", template.getDescription());
            model.addAttribute("prefillPriority", template.getPriority());

            // 🔒 LOCKED auto due date
            LocalDate autoDueDate =
                    LocalDate.now().plusDays(template.getFlashTime());

            model.addAttribute("prefillDueDate", autoDueDate);
            model.addAttribute("sourceTemplateId", templateId);
        }

        return "tasks/create";
    }



    @PostMapping
    public String createTask(
            @RequestParam String title,
            @RequestParam String description,
            @RequestParam Task.TaskPriority priority,
            @RequestParam LocalDate dueDate,
            @RequestParam(required = false) Long sourceTemplateId,
            @ModelAttribute("loggedInUser") User loggedInUser
    ) {

        Task task = taskService.createTask(
                title,
                description,
                priority,
                loggedInUser,
                dueDate,
                sourceTemplateId
        );

        return "redirect:/tasks/" + task.getTaskId();
    }


    @GetMapping("/{taskId}")
    public String taskDetails(@PathVariable Long taskId, Model model,
                        @ModelAttribute("loggedInUser") User loggedInUser,
                              RedirectAttributes redirectAttributes) {

        Task task = taskService.getTaskById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        if (!canAccessTask(task, loggedInUser)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You are not authorized to access this task."
            );
            return "redirect:/tasks";
        }


        String remainingTime = TimeUtil.getRemainingTime(task.getDueDate());
        model.addAttribute("remainingTime", remainingTime);

        model.addAttribute("task", task);
        model.addAttribute("assignees",
                taskAssignmentService.getAssigneesByTask(task));
        model.addAttribute("comments",
                taskCommentService.getCommentsByTask(task));
        model.addAttribute("history",
                taskStatusHistoryService.getHistoryByTask(task));

        return "tasks/details";
    }

    @PostMapping("/{taskId}/status")
    public String updateStatus(@PathVariable Long taskId,
                               @RequestParam Task.TaskStatus status,
                               @ModelAttribute("loggedInUser") User loggedInUser,
                               RedirectAttributes redirectAttributes) {

        Task task = taskService.getTaskById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        if (task.getStatus() == Task.TaskStatus.CLOSED) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "This task is closed and can no longer be modified."
            );
            return "redirect:/tasks/" + taskId;
        }

        if (status == Task.TaskStatus.CLOSED &&
                !task.getCreator().getUserId().equals(loggedInUser.getUserId())) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Only the task creator can close this task."
            );

            return "redirect:/tasks/" + taskId;
        }

        Task.TaskStatus oldStatus = task.getStatus();
        taskService.updateTaskStatus(taskId, status, loggedInUser);

        TaskStatusMessage wsMessage = new TaskStatusMessage();
        wsMessage.setTaskId(taskId);
        wsMessage.setOldStatus(oldStatus.name());
        wsMessage.setNewStatus(status.name());
        wsMessage.setChangedByUserId(loggedInUser.getUserId());
        wsMessage.setChangedByUserName(loggedInUser.getUserName());
        wsMessage.setTimestamp(java.time.LocalDateTime.now());

        messagingTemplate.convertAndSend("/topic/task/" + taskId + "/status", wsMessage);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Task status updated successfully."
        );

        return "redirect:/tasks/" + taskId;
    }

    @GetMapping("/{taskId}/assign")
    public String assignPage(@PathVariable Long taskId, Model model) {

        Task task = taskService.getTaskById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        model.addAttribute("task", task);
        model.addAttribute("users", userService.getAllUsers());
        model.addAttribute("assignees",
                taskAssignmentService.getAssigneesByTask(task));

        return "tasks/assign";
    }

    @PostMapping("/{taskId}/assign")
    public String assignUser(@PathVariable Long taskId,
                             @RequestParam(required = false) Long userId,
                             @RequestParam(required = false) List<Long> userIds,
                             @RequestParam String role,
                             RedirectAttributes redirectAttributes) {

        Task task = taskService.getTaskById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        if (task.getStatus() == Task.TaskStatus.CLOSED) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Assignees cannot be modified after task closure."
            );
            return "redirect:/tasks/" + taskId + "/assign";
        }

        if ("MAIN".equalsIgnoreCase(role)) {
            if (userId != null) {
                User user = userService.getUserById(userId);
                taskAssignmentService.assignMainAssignee(task, user);
            }
        } else {
            if (userIds != null) {
                for (Long uid : userIds) {
                    try {
                        User user = userService.getUserById(uid);
                        taskAssignmentService.addSupportingAssignee(task, user);
                    } catch (IllegalStateException ignored) {}
                }
            }
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Assignees updated successfully."
        );

        return "redirect:/tasks/" + taskId + "/assign";
    }

    @PostMapping("/{taskId}/comment")
    public String addComment(@PathVariable Long taskId,
                             @RequestParam String content,
                             @ModelAttribute("loggedInUser") User loggedInUser,
                             RedirectAttributes redirectAttributes) {

        Task task = taskService.getTaskById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        if (task.getStatus() == Task.TaskStatus.CLOSED) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Comments are disabled for closed tasks."
            );
            return "redirect:/tasks/" + taskId;
        }

        TaskComment savedComment = taskCommentService.addComment(task, loggedInUser, content, null);

        TaskCommentMessage wsMessage = new TaskCommentMessage();
        wsMessage.setTaskId(taskId);
        wsMessage.setSenderName(loggedInUser.getUserName());
        wsMessage.setContent(content);
        wsMessage.setTimestamp(savedComment.getChangedAt());

        messagingTemplate.convertAndSend("/topic/task/" + taskId + "/comments", wsMessage);

        return "redirect:/tasks/" + taskId;
    }

    @PostMapping("/{taskId}/delete")
    public String deleteTask(@PathVariable Long taskId,
                             @ModelAttribute("loggedInUser") User loggedInUser,
                             RedirectAttributes redirectAttributes) {

        Task task = taskService.getTaskById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        // Rule 1: Only creator can delete
        if (!task.getCreator().getUserId().equals(loggedInUser.getUserId())) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Only the task creator can delete this task."
            );
            return "redirect:/tasks/" + taskId;
        }

        // Rule 2: Closed tasks cannot be deleted
        if (task.getStatus() == Task.TaskStatus.CLOSED) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Closed tasks cannot be deleted."
            );
            return "redirect:/tasks/" + taskId;
        }

        taskService.deleteTask(task);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Task deleted successfully."
        );

        return "redirect:/tasks";
    }

    private boolean canAccessTask(Task task, User user) {
        // Creator can always access
        if (task.getCreator().getUserId().equals(user.getUserId())) {
            return true;
        }

        // Assigned users can access
        return taskAssignmentService.isUserAssignedToTask(task, user);
    }
}
