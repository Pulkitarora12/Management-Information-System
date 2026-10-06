package com.notes.notes.dto.moduleDTOS;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductionEntryRequestDTO {

    private LocalDate date;
    private String shift;
    private String line;
    private String machine;
    private String operation;
    private String operator1;
    private String operator2;
    private String partNo;
    private String partName;
    private String remarks;
    private String sheetSize;
    private String inspector;

    // Changed from List to single object
    private TimeSlotDTO timeSlot;
    private List<DowntimeDTO> downtimeEntries = new ArrayList<>();


    @Data
    public static class TimeSlotDTO {
        private LocalTime fromTime;
        private LocalTime toTime;
        private Integer produced;
        private Integer segregated;
        private Integer rejected;
        private String reason;
        private String remarks;
    }

    @Data
    public static class DowntimeDTO {
        private String reason;
        private Integer minutes;
    }
}