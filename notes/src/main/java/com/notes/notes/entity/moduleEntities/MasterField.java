package com.notes.notes.entity.moduleEntities;


import com.notes.notes.entity.authEntities.DataType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Entity
public class MasterField {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fieldName;

    @Enumerated(EnumType.STRING)  // STRING, INTEGER, BOOLEAN
    private DataType dataType;

    @ManyToOne
    @JoinColumn(name = "master_id")
    private Master master;

    @OneToMany(mappedBy = "field", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FieldData> fieldData = new ArrayList<>();
}
