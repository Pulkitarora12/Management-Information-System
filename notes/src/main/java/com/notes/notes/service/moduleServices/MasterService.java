package com.notes.notes.service.moduleServices;

import com.notes.notes.dto.moduleDTOS.MasterCreateDTO;
import com.notes.notes.entity.moduleEntities.Master;

import java.util.List;
import java.util.Map;

public interface MasterService {
    Master createMaster(MasterCreateDTO masterDTO);

    List<Master> getAllMasters();

    Master getMasterById(Long id);

    Master updateMaster(Long id, MasterCreateDTO masterDTO);

    List<String> getPartGrades();

    void deleteMaster(Long id);

    List<String> getMachineNames();

    List<String> getRejectionReasons();

    List<String> getGrades();

    List<String> getSuppliers();

    List<String> getInspectors();

    List<Map<String, String>> getPartDetailsList();

    List<String> getOperations();

    List<String> getOperators();

    List<String> getDowntimeReasons();
}