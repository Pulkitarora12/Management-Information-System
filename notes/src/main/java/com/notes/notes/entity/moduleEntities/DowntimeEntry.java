package com.notes.notes.entity.moduleEntities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "production_downtime")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DowntimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reason;
    private Integer minutes;

    @ManyToOne
    @JoinColumn(name = "production_entry_id")
    private ProductionEntry productionEntry;
}
