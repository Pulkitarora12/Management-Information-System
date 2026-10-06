package com.notes.notes.repository.moduleRepo;

import com.notes.notes.entity.moduleEntities.Master;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MasterRepository extends JpaRepository<Master, Long> {

    Master findByName(String name);
}
