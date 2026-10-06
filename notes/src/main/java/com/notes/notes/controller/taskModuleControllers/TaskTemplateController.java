    package com.notes.notes.controller.taskModuleControllers;

    import com.notes.notes.dto.taskManagementDTO.TemplateDTO;
    import com.notes.notes.entity.authEntities.User;
    import com.notes.notes.entity.taskModuleEntities.Task;
    import com.notes.notes.entity.taskModuleEntities.TaskTemplate;
    import com.notes.notes.repository.taskModuleRepositories.TaskTemplateRepository;
    import com.notes.notes.service.authServices.UserService;
    import com.notes.notes.service.taskModuleServices.TaskService;
    import com.notes.notes.service.taskModuleServices.TaskTemplateService;
    import org.springframework.stereotype.Controller;
    import org.springframework.transaction.annotation.Transactional;
    import org.springframework.ui.Model;
    import org.springframework.web.bind.annotation.*;
    import org.springframework.web.servlet.mvc.support.RedirectAttributes;

    import java.time.LocalDate;
    import java.util.List;

    @Controller
    @RequestMapping("/template")
    public class TaskTemplateController {

        private final TaskTemplateService taskTemplateService;
        private final TaskService taskService;
        private final UserService userService;
        private final TaskTemplateRepository taskTemplateRepository;

        public TaskTemplateController(TaskTemplateService taskTemplateService, TaskService taskService, UserService userService, TaskTemplateRepository taskTemplateRepository) {
            this.taskTemplateService = taskTemplateService;
            this.taskService = taskService;
            this.userService = userService;
            this.taskTemplateRepository = taskTemplateRepository;
        }

        /* ===================== LIST ALL TEMPLATES ===================== */

        @GetMapping
        public String getAllTemplates(Model model) {

            List<TaskTemplate> templates = taskTemplateService.findAll();
            model.addAttribute("templates", templates);

            return "tasks/templateList";
        }

        /* ===================== SHOW CREATE FORM ===================== */

        @GetMapping("/new")
        public String showCreateTemplateForm(Model model) {

            model.addAttribute("templateDTO", new TemplateDTO());

            // ✅ ADD THIS
            model.addAttribute("users", userService.getAllUsers());

            return "tasks/createTemplate";
        }

        /* ===================== CREATE TEMPLATE ===================== */

        @PostMapping("/new")
        public String createTemplate(
                @ModelAttribute TemplateDTO templateDTO,
                @ModelAttribute("loggedInUser") User loggedInUser
        ) {

            TaskTemplate template =
                    taskTemplateService.createTaskTemplate(templateDTO, loggedInUser);

            return "redirect:/template";
        }

        /* ===================== ACTIVATE TEMPLATE ===================== */

        @Transactional
        @PostMapping("/{id}/activate")
        public String activateTemplate(@PathVariable Long id) {
            TaskTemplate template = taskTemplateService.activateTask(id);
            if (template != null) {
                taskService.generateTasksForTemplate(template, template.getCreator());
            }
            return "redirect:/template";
        }

        /* ===================== DEACTIVATE TEMPLATE ===================== */

        @PostMapping("/{id}/deactivate")
        public String deactivateTemplate(@PathVariable Long id) {

            taskTemplateService.deactivate(id);
            return "redirect:/template";
        }

        /* ===================== DELETE TEMPLATE ===================== */

        @PostMapping("/{id}/delete")
        public String deleteTemplate(@PathVariable Long id) {

            taskTemplateService.deleteTaskTemplateById(id);
            return "redirect:/template";
        }

        @GetMapping("/{id}/view")
        public String viewTemplate(@PathVariable Long id, Model model) {
            TaskTemplate taskTemplate = taskTemplateService.findById(id);
            model.addAttribute("taskTemplate", taskTemplate);
            return "tasks/viewTemplate";
        }

        @GetMapping("/{id}/tasks")
        public String viewTasksCreatedFromTemplate(
                @PathVariable Long id,
                Model model,
                @ModelAttribute("loggedInUser") User loggedInUser
        ) {

            // 🔒 ADMIN CHECK
            if (!loggedInUser.isAdmin()) {
                throw new SecurityException("Access denied");
            }

            TaskTemplate template = taskTemplateService.findById(id);

            List<Task> tasks = taskService.getTasksByTemplate(template);

            model.addAttribute("tasks", tasks);
            model.addAttribute("templateTitle", template.getTitle());

            return "tasks/templateTasks";
        }

        @Transactional
        @GetMapping("/due")
        public String processDueTemplates(RedirectAttributes redirectAttributes) {

            LocalDate today = LocalDate.now();

            List<TaskTemplate> dueTemplates =
                    taskTemplateRepository
                            .findByIsActiveTrueAndNextRunDateLessThan(today.plusDays(1));

            for (TaskTemplate template : dueTemplates) {
                taskService.generateTasksForTemplate(template, template.getCreator());
            }

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "All tasks are up to date ✔"
            );

            return "redirect:/template";
        }

        // Add manual trigger endpoint
        @PostMapping("/{id}/create-task-now")
        public String createTaskNow(@PathVariable Long id,
                                    @ModelAttribute("loggedInUser") User user,
                                    RedirectAttributes redirectAttributes) {

            TaskTemplate template = taskTemplateService.findById(id);

            if (!template.isActive()) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Template must be active");
                return "redirect:/template";
            }

            // Create task immediately
            taskService.createTaskFromTemplate(template, user);

            // Update nextRunDate for next scheduled task
            taskTemplateService.calculateAndSetNextRunDate(template);
            taskTemplateRepository.save(template);

            redirectAttributes.addFlashAttribute("successMessage",
                    "Task created successfully");
            return "redirect:/template";
        }

    }
