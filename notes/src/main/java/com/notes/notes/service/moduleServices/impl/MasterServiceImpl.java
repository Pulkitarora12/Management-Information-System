package com.notes.notes.service.moduleServices.impl;

import com.notes.notes.dto.moduleDTOS.FieldDataCreateDTO;
import com.notes.notes.dto.moduleDTOS.MasterCreateDTO;
import com.notes.notes.dto.moduleDTOS.MasterFieldCreateDTO;
import com.notes.notes.entity.moduleEntities.FieldData;
import com.notes.notes.entity.moduleEntities.Master;
import com.notes.notes.entity.moduleEntities.MasterField;
import com.notes.notes.repository.moduleRepo.MasterRepository;
import com.notes.notes.service.moduleServices.MasterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MasterServiceImpl implements MasterService {

    @Autowired
    private MasterRepository masterRepository;

    @Override
    public Master createMaster(MasterCreateDTO masterDTO) {

        Master master = new Master();
        master.setName(masterDTO.getName());

        if (masterDTO.getFields() != null) {
            for (MasterFieldCreateDTO fieldDTO : masterDTO.getFields()) {

                MasterField field = new MasterField();
                field.setFieldName(fieldDTO.getFieldName());
                field.setDataType(fieldDTO.getDataType());
                field.setMaster(master);

                if (fieldDTO.getFieldData() != null) {
                    for (FieldDataCreateDTO dataDTO : fieldDTO.getFieldData()) {
                        FieldData data = new FieldData();
                        data.setValue(dataDTO.getValue());
                        data.setField(field);
                        field.getFieldData().add(data);
                    }
                }

                master.getFields().add(field);
            }
        }

        return masterRepository.save(master);
    }

    @Override
    public Master getMasterById(Long id) {
        return masterRepository.findById(id).orElse(null);
    }

    @Override
    public List<Master> getAllMasters() {
        return masterRepository.findAll();
    }

    @Override
    public Master updateMaster(Long id, MasterCreateDTO masterDTO) {
        Master master = masterRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Master not found"));

        // Update Master Name
        master.setName(masterDTO.getName());

        // Update / Add Fields
        if (masterDTO.getFields() != null) {
            for (MasterFieldCreateDTO fieldDTO : masterDTO.getFields()) {

                // Find existing field or create new
                MasterField field = master.getFields().stream()
                        .filter(f -> f.getFieldName().equals(fieldDTO.getFieldName()))
                        .findFirst()
                        .orElse(new MasterField());

                field.setFieldName(fieldDTO.getFieldName());
                field.setDataType(fieldDTO.getDataType());
                field.setMaster(master);

                // Update / Add field data
                if (fieldDTO.getFieldData() != null) {
                    for (FieldDataCreateDTO dataDTO : fieldDTO.getFieldData()) {

                        FieldData data = field.getFieldData().stream()
                                .filter(d -> d.getValue().equals(dataDTO.getValue()))
                                .findFirst()
                                .orElse(new FieldData());

                        data.setValue(dataDTO.getValue());
                        data.setField(field);

                        if (!field.getFieldData().contains(data)) {
                            field.getFieldData().add(data);
                        }
                    }
                }

                if (!master.getFields().contains(field)) {
                    master.getFields().add(field);
                }
            }
        }

        return masterRepository.save(master);
    }

    @Override
    public List<String> getPartGrades() {
        Master master = masterRepository.findByName("Part Master");
        List<String> grades = new ArrayList<>();

        if (master != null && master.getFields().size() > 4) {
            MasterField gradeField = master.getFields().get(4); // PART GRADE
            for (FieldData data : gradeField.getFieldData()) {
                grades.add(data.getValue());
            }
        }
        return grades;
    }

    @Override
    public void deleteMaster(Long id) {
        masterRepository.deleteById(id);
    }

    @Override
    public List<String> getMachineNames() {
        Master machineMaster = masterRepository.findByName("Machine Master");

        List<String> machineNames = new ArrayList<>();

        if (machineMaster != null && !machineMaster.getFields().isEmpty()) {
            MasterField field = machineMaster.getFields().get(0); // First column only
            for (FieldData data : field.getFieldData()) {
                machineNames.add(data.getValue());
            }
        }

        return machineNames;
    }

    @Override
    public List<String> getRejectionReasons() {
        Master rejectionMaster = masterRepository.findByName("Rejection Master");

        List<String> rejectionReasons = new ArrayList<>();

        if (rejectionMaster != null && !rejectionMaster.getFields().isEmpty()) {
            MasterField field = rejectionMaster.getFields().get(0); // First column only
            for (FieldData data : field.getFieldData()) {
                rejectionReasons.add(data.getValue());
            }
        }

        return rejectionReasons;
    }

    @Override
    public List<String> getGrades() {
        Master gradeMaster = masterRepository.findByName("Grade Master");

        List<String> grades = new ArrayList<>();

        if (gradeMaster != null && !gradeMaster.getFields().isEmpty()) {
            MasterField field = gradeMaster.getFields().get(0); // First column only
            for (FieldData data : field.getFieldData()) {
                grades.add(data.getValue());
            }
        }

        return grades;
    }

    @Override
    public List<String> getSuppliers() {
        Master supplierMaster = masterRepository.findByName("Supplier Master");

        List<String> suppliers = new ArrayList<>();

        if (supplierMaster != null && !supplierMaster.getFields().isEmpty()) {
            MasterField field = supplierMaster.getFields().get(0); // First column only
            for (FieldData data : field.getFieldData()) {
                suppliers.add(data.getValue());
            }
        }

        return suppliers;
    }

    @Override
    public List<String> getInspectors() {
        Master inspectorMaster = masterRepository.findByName("Inspector Master");

        List<String> inspectors = new ArrayList<>();

        if (inspectorMaster != null && !inspectorMaster.getFields().isEmpty()) {
            MasterField field = inspectorMaster.getFields().get(0); // First column only
            for (FieldData data : field.getFieldData()) {
                inspectors.add(data.getValue());
            }
        }

        return inspectors;
    }

    @Override
    public List<Map<String, String>> getPartDetailsList() {
        Master master = masterRepository.findByName("Part Master");
        List<Map<String, String>> result = new ArrayList<>();

        if (master != null) {
            for (int i = 0; i < master.getFields().get(0).getFieldData().size(); i++) {
                Map<String, String> map = new HashMap<>();
                map.put("model", master.getFields().get(0).getFieldData().get(i).getValue());
                map.put("partNo", master.getFields().get(1).getFieldData().get(i).getValue());
                map.put("partName", master.getFields().get(2).getFieldData().get(i).getValue());
                map.put("rmSize", master.getFields().get(3).getFieldData().get(i).getValue());
                map.put("grade", master.getFields().get(4).getFieldData().get(i).getValue());
                map.put("gw", master.getFields().get(5).getFieldData().get(i).getValue());
                result.add(map);
            }
        }
        return result;
    }

    @Override
    public List<String> getOperations() {
        Master operationMaster = masterRepository.findByName("Operation Master");

        List<String> operations = new ArrayList<>();

        if (operationMaster != null && !operationMaster.getFields().isEmpty()) {
            MasterField field = operationMaster.getFields().get(0);
            for (FieldData data : field.getFieldData()) {
                operations.add(data.getValue());
            }
        }
        return operations;
    }

    @Override
    public List<String> getOperators() {
        Master operatorMaster = masterRepository.findByName("Operator Master");

        List<String> operators = new ArrayList<>();

        if (operatorMaster != null && !operatorMaster.getFields().isEmpty()) {
            MasterField field = operatorMaster.getFields().get(0);
            for (FieldData data : field.getFieldData()) {
                operators.add(data.getValue());
            }
        }
        return operators;
    }

    @Override
    public List<String> getDowntimeReasons() {
        Master downtimeMaster = masterRepository.findByName("Downtime Master");

        List<String> downtimes = new ArrayList<>();

        if (downtimeMaster != null && !downtimeMaster.getFields().isEmpty()) {
            MasterField field = downtimeMaster.getFields().get(0);
            for (FieldData data : field.getFieldData()) {
                downtimes.add(data.getValue());
            }
        }
        return downtimes;
    }

}