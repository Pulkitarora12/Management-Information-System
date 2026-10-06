package com.notes.notes.service.moduleServices.impl;

import com.notes.notes.dto.moduleDTOS.ProductionEntryRequestDTO;
import com.notes.notes.entity.moduleEntities.DowntimeEntry;
import com.notes.notes.entity.moduleEntities.ProductionEntry;
import com.notes.notes.entity.moduleEntities.TimeSlot;
import com.notes.notes.events.ProductionEntryEvent;
import com.notes.notes.repository.moduleRepo.ProductionEntryRepository;
import com.notes.notes.service.googleSheetServices.ProductionGoogleSheetsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ProductionEntryService {

    @Autowired
    private ProductionEntryRepository repository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ProductionGoogleSheetsService productionGoogleSheetsService;

    private static final Set<String> activeUsers = ConcurrentHashMap.newKeySet();
    private static final Set<Long> activeDeletes = ConcurrentHashMap.newKeySet();


    public ProductionEntry saveProduction(ProductionEntryRequestDTO dto) {

        String user = dto.getInspector();

        if (!activeUsers.add(user)) {
            throw new IllegalStateException(
                    "Previous production entry is still being processed"
            );
        }

        try {
            ProductionEntry entry = new ProductionEntry();
            entry.setDate(dto.getDate());
            entry.setShift(dto.getShift());
            entry.setLine(dto.getLine());
            entry.setMachine(dto.getMachine());
            entry.setOperation(dto.getOperation());
            entry.setOperator1(dto.getOperator1());
            entry.setOperator2(dto.getOperator2());
            entry.setPartNo(dto.getPartNo());
            entry.setPartName(dto.getPartName());
            entry.setRemarks(dto.getRemarks());
            entry.setSheetSize(dto.getSheetSize());
            entry.setInspector(dto.getInspector());

            // Single time slot
            if (dto.getTimeSlot() != null) {
                TimeSlot t = new TimeSlot();
                t.setFromTime(dto.getTimeSlot().getFromTime());
                t.setToTime(dto.getTimeSlot().getToTime());
                t.setProduced(dto.getTimeSlot().getProduced());
                t.setSegregated(dto.getTimeSlot().getSegregated());
                t.setRejected(dto.getTimeSlot().getRejected());
                t.setReason(dto.getTimeSlot().getReason());
                t.setRemarks(dto.getTimeSlot().getRemarks());
                t.setProductionEntry(entry);
                entry.setTimeSlot(t);
            }

            // Downtime
            List<DowntimeEntry> downtimeList = dto.getDowntimeEntries().stream().map(d -> {
                DowntimeEntry down = new DowntimeEntry();
                down.setReason(d.getReason());
                down.setMinutes(d.getMinutes());
                down.setProductionEntry(entry);
                return down;
            }).collect(Collectors.toList());

            entry.setDowntimeEntries(downtimeList);

            // Save to database
            ProductionEntry savedEntry = repository.save(entry);

            // Sync to Google Sheets
            eventPublisher.publishEvent(new ProductionEntryEvent(this, savedEntry, "SAVE"));

            return savedEntry;
        } finally {
            activeUsers.remove(user);
        }
    }

    public List<ProductionEntry> getAllEntries() {
        return repository.findAll(
                Sort.by(Sort.Direction.DESC, "date", "id")
        );
    }

    public List<ProductionEntry> getFilteredEntries(
            Integer month,
            LocalDate date,
            String line,
            String part,
            String machine,
            String operation
    ) {

        return repository.findAll(
                        Sort.by(Sort.Direction.DESC, "date", "id")
                ).stream()

                // Month filter
                .filter(e -> month == null || e.getDate().getMonthValue() == month)

                // Date filter (exact)
                .filter(e -> date == null || e.getDate().equals(date))

                // Line filter
                .filter(e -> line == null || line.isBlank() || line.equals(e.getLine()))

                // Machine filter
                .filter(e -> machine == null || machine.isBlank() || machine.equals(e.getMachine()))

                // Operation filter
                .filter(e -> operation == null || operation.isBlank() || operation.equals(e.getOperation()))

                // Part filter (part no OR name)
                .filter(e -> part == null || part.isBlank()
                        || e.getPartNo().toLowerCase().contains(part.toLowerCase())
                        || e.getPartName().toLowerCase().contains(part.toLowerCase()))

                .toList();
    }


    public TimeSlot getTimeSlotByEntry(Long id) {
        ProductionEntry entry = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Entry not found"));
        return entry.getTimeSlot();
    }

    public List<DowntimeEntry> getDowntimeByEntry(Long id) {
        ProductionEntry entry = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Entry not found"));
        return entry.getDowntimeEntries();
    }

    public void deleteEntry(Long id) {

        if (!activeDeletes.add(id)) {
            throw new IllegalStateException(
                    "This entry is already being deleted"
            );
        }

        try {
            ProductionEntry entry = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Entry not found"));

            repository.deleteById(id);

            eventPublisher.publishEvent(new ProductionEntryEvent(this, entry, "DELETE"));

        } finally {
            activeDeletes.remove(id);
        }
    }


    public ProductionEntry updateProduction(Long id, ProductionEntryRequestDTO dto) {
        ProductionEntry entry = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Entry not found with ID: " + id));

        // Update basic fields
        entry.setDate(dto.getDate());
        entry.setShift(dto.getShift());
        entry.setLine(dto.getLine());
        entry.setMachine(dto.getMachine());
        entry.setOperation(dto.getOperation());
        entry.setOperator1(dto.getOperator1());
        entry.setOperator2(dto.getOperator2());
        entry.setPartNo(dto.getPartNo());
        entry.setPartName(dto.getPartName());
        entry.setRemarks(dto.getRemarks());
        entry.setSheetSize(dto.getSheetSize());
        entry.setInspector(dto.getInspector());

        // Update time slot (single)
        if (dto.getTimeSlot() != null) {
            TimeSlot t = entry.getTimeSlot();
            if (t == null) {
                t = new TimeSlot();
                t.setProductionEntry(entry);
            }
            t.setFromTime(dto.getTimeSlot().getFromTime());
            t.setToTime(dto.getTimeSlot().getToTime());
            t.setProduced(dto.getTimeSlot().getProduced());
            t.setSegregated(dto.getTimeSlot().getSegregated());
            t.setRejected(dto.getTimeSlot().getRejected());
            t.setReason(dto.getTimeSlot().getReason());
            t.setRemarks(dto.getTimeSlot().getRemarks());
            entry.setTimeSlot(t);
        } else {
            entry.setTimeSlot(null);
        }

        // Clear and rebuild downtime entries
        entry.getDowntimeEntries().clear();
        List<DowntimeEntry> downtimeList = dto.getDowntimeEntries().stream().map(d -> {
            DowntimeEntry down = new DowntimeEntry();
            down.setReason(d.getReason());
            down.setMinutes(d.getMinutes());
            down.setProductionEntry(entry);
            return down;
        }).collect(Collectors.toList());
        entry.getDowntimeEntries().addAll(downtimeList);

        // Save to database
        ProductionEntry updatedEntry = repository.save(entry);

        // Sync to Google Sheets
        eventPublisher.publishEvent(new ProductionEntryEvent(this, updatedEntry, "UPDATE"));

        return updatedEntry;
    }
}