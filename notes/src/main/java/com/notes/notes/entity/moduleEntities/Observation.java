package com.notes.notes.entity.moduleEntities;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rmir_observations")
public class Observation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Double l;
    private Double b;
    private Double h;
    private Double gwSheet;

    @ManyToOne
    @JoinColumn(name = "rmir_id")
    private RMIR rmir;
}
