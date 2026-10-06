package com.notes.notes.dto.moduleDTOS;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RMIRRequestDTO {
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

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdDate;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime createdTime;

    private List<ObservationDTO> observations = new ArrayList<>();

    @Data
    public static class ObservationDTO {
        private Double l;
        private Double b;
        private Double h;
        private Double gwSheet;
    }
}
