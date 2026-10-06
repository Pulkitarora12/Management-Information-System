package com.notes.notes.repository.hrRepo;

import com.notes.notes.entity.hrEntities.InOutEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InOutEntryRepository extends JpaRepository<InOutEntry, Long> {

    List<InOutEntry> findByInTimeIsNull();

    List<InOutEntry> findByInTimeIsNotNullOrderByOutTimeDesc();

    @Query("SELECT e FROM InOutEntry e WHERE e.outTime >= :start AND e.outTime <= :end ORDER BY e.outTime DESC")
    List<InOutEntry> findEntriesInDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
