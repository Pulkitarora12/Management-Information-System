package com.notes.notes.repository.moduleRepo;

import com.notes.notes.entity.moduleEntities.DowntimeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DownTimeRepository extends JpaRepository<DowntimeEntry, Long> {
}
