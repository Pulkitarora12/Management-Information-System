package com.notes.notes.scheduler;

import com.notes.notes.entity.taskModuleEntities.TaskTemplate;
import com.notes.notes.repository.taskModuleRepositories.TaskTemplateRepository;
import com.notes.notes.service.taskModuleServices.TaskService;
import com.notes.notes.service.taskModuleServices.TaskTemplateService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class TaskTemplateScheduler {

    private final TaskTemplateRepository taskTemplateRepository;
    private final TaskService taskService;
    private final TaskTemplateService taskTemplateService;

    private static final Logger log = LoggerFactory.getLogger(TaskTemplateScheduler.class);

    public TaskTemplateScheduler(TaskTemplateRepository taskTemplateRepository,
                                 TaskService taskService,
                                 TaskTemplateService taskTemplateService) {
        this.taskTemplateRepository = taskTemplateRepository;
        this.taskService = taskService;
        this.taskTemplateService = taskTemplateService;
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void runDailyTemplates() {

        LocalDate today = LocalDate.now();

        log.info("========================================");
        log.info("SCHEDULER STARTED at {}", LocalDateTime.now());
        log.info("========================================");

        int activatedCount = 0;
        int tasksCreatedCount = 0;

        /* ===== PHASE 1: AUTO-ACTIVATE (Optimized) ===== */
        List<TaskTemplate> templatesToActivate =
                taskTemplateRepository.findTemplatesReadyForActivation(today);

        log.info("Found {} templates ready for activation", templatesToActivate.size());

        for (TaskTemplate template : templatesToActivate) {
            try {
                template.setActive(true);
                if (template.getNextRunDate() == null) {
                    template.setNextRunDate(template.getStartDate());
                }
                taskTemplateRepository.save(template);
                activatedCount++;
                log.debug("Activated template: {}", template.getTitle());
            } catch (Exception e) {
                log.error("Failed to activate template {}: {}",
                        template.getId(), e.getMessage());
            }
        }

        /* ===== PHASE 2: CREATE TASKS (Already optimized) ===== */
        List<TaskTemplate> dueTemplates =
                taskTemplateRepository
                        .findByIsActiveTrueAndNextRunDateLessThan(today.plusDays(1));

        log.info("Found {} templates due for task creation", dueTemplates.size());

        for (TaskTemplate template : dueTemplates) {
            try {
                int created = taskService.generateTasksForTemplate(
                        template,
                        template.getCreator()
                );
                tasksCreatedCount += created;
                if (created > 0) {
                    log.debug("Generated {} tasks from template: {}", created, template.getTitle());
                }
            } catch (Exception e) {
                log.error("Failed to create tasks from template {}: {}",
                        template.getId(), e.getMessage());
            }
        }

        log.info("========================================");
        log.info("SCHEDULER COMPLETED:");
        log.info("  - {} templates activated", activatedCount);
        log.info("  - {} tasks created", tasksCreatedCount);
        log.info("========================================");
    }
}
