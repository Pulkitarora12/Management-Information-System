package com.notes.notes.service.taskModuleServices;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskTemplate;
import com.notes.notes.dto.taskManagementDTO.TemplateDTO;
import jakarta.transaction.Transactional;

import java.util.*;

public interface TaskTemplateService {

    public List<TaskTemplate> findAll();

    public TaskTemplate findById(Long id);

    public TaskTemplate findByName(String name);

    public TaskTemplate createTaskTemplate(TemplateDTO dto, User creator);

    public void deleteTaskTemplateById(Long id);

    public TaskTemplate deactivate(Long id);

    @Transactional
    TaskTemplate activateTask(Long id);

    void calculateAndSetNextRunDate(TaskTemplate template);
}
