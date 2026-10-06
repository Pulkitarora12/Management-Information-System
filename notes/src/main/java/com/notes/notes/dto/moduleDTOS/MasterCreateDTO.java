package com.notes.notes.dto.moduleDTOS;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter

public class MasterCreateDTO {
    private String name;
    private List<MasterFieldCreateDTO> fields;
}
