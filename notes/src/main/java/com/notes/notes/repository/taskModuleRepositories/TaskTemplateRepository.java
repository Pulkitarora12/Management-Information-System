package com.notes.notes.repository.taskModuleRepositories;

import com.notes.notes.entity.taskModuleEntities.TaskTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;


public interface TaskTemplateRepository extends JpaRepository<TaskTemplate, Long> {

    Optional<TaskTemplate> findByTitle(String name);

    List<TaskTemplate> findByIsActiveTrueAndNextRunDate(LocalDate date);
// This will fetch all active templates whose nextRunDate matches today

    List<TaskTemplate> findByIsActiveTrueAndNextRunDateLessThan(LocalDate date);

    @Query("SELECT t FROM TaskTemplate t WHERE " +
            "t.isActive = false AND " +
            "t.startDate <= :today")
    List<TaskTemplate> findTemplatesReadyForActivation(@Param("today") LocalDate today);

}
