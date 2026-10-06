package com.notes.notes.entity.moduleEntities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

@Entity
@Table(name = "production_timeslots")

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TimeSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalTime fromTime;
    private LocalTime toTime;
    private Integer produced;
    private Integer segregated;
    private Integer rejected;
    private String reason;
    private String remarks;

    @OneToOne
    @JoinColumn(name = "production_entry_id")
    private ProductionEntry productionEntry;
}
