package com.notes.notes.entity.moduleEntities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "production_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;
    private String shift;      // A or B
    private String line;       // Line 1 or Line 2
    private String machine;
    private String operation;
    private String operator1;
    private String operator2;
    private String partNo;
    private String partName;
    private String remarks;
    private String sheetSize;
    private String inspector;

    // Single TimeSlot instead of List
    @OneToOne(mappedBy = "productionEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private TimeSlot timeSlot;

    @OneToMany(mappedBy = "productionEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DowntimeEntry> downtimeEntries;
}