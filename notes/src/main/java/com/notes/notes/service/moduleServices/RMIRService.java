package com.notes.notes.service.moduleServices;

import com.notes.notes.dto.moduleDTOS.RMIRRequestDTO;
import com.notes.notes.entity.moduleEntities.RMIR;

import java.time.LocalDate;
import java.util.List;

public interface RMIRService {
    RMIR saveRMIR(RMIRRequestDTO dto);

    List<RMIR> getAllEntries();

    List<RMIR> getFilteredEntries(
            Integer month,
            LocalDate createdDate,
            String part,
            String supplier,
            String grade,
            String inspector,
            boolean overweight
    );

    public void deleteRMIR(Long id);

}