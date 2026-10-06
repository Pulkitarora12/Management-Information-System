package com.notes.notes.entity.hrEntities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "in_out_entries")
public class InOutEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "out_time", nullable = false)
    private LocalDateTime outTime;

    @Column(name = "in_time")
    private LocalDateTime inTime;

    @Column(name = "duration_minutes")
    private Long durationMinutes;

    private String remarks;
}
