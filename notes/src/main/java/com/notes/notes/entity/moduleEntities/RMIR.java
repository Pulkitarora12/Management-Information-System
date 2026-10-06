package com.notes.notes.entity.moduleEntities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rmir_entries")
public class RMIR {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String partNo;
    private String partName;
    private String rmSize;
    private String grade;
    private Double stdGW;
    private String bundleGrade;
    private String bundleNo;
    private Double bundleGW;
    private Double bundleNW;
    private String bundleSize;
    private String supplier;
    private String inspector;
    private String remarks;

    @Column(nullable = true)
    private LocalDate createdDate;

    @Column(nullable = true)
    private LocalTime createdTime;

    @OneToMany(mappedBy = "rmir", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Observation> observations = new ArrayList<>();
}
