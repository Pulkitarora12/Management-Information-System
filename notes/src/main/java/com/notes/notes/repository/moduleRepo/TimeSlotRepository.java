package com.notes.notes.repository.moduleRepo;

import com.notes.notes.entity.moduleEntities.TimeSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TimeSlotRepository extends JpaRepository<TimeSlot,Long> {
}
