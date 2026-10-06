package com.notes.notes.dto.moduleDTOS;

import com.notes.notes.entity.authEntities.DataType;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MasterFieldCreateDTO {
    private String fieldName;
    private DataType dataType;
    private List<FieldDataCreateDTO> fieldData;
}