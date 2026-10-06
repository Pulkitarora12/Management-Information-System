package com.notes.notes.entity.moduleEntities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class FieldData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String value;   // Store everything as String and parse based on dataType

    @ManyToOne
    @JoinColumn(name = "field_id")
    private MasterField field;
}
