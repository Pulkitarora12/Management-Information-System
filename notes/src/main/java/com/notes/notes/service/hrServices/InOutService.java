package com.notes.notes.service.hrServices;

import com.notes.notes.entity.hrEntities.Employee;
import com.notes.notes.entity.hrEntities.InOutEntry;
import com.notes.notes.repository.hrRepo.EmployeeRepository;
import com.notes.notes.repository.hrRepo.InOutEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class InOutService {

    @Autowired
    private InOutEntryRepository inOutEntryRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    public List<InOutEntry> getActiveEntries() {
        return inOutEntryRepository.findByInTimeIsNull();
    }

    public List<InOutEntry> getHistoricalEntries() {
        return inOutEntryRepository.findByInTimeIsNotNullOrderByOutTimeDesc();
    }

    public InOutEntry logOut(Long employeeId, LocalDateTime outTime, String remarks) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        // Check if employee is already out
        List<InOutEntry> active = inOutEntryRepository.findByInTimeIsNull();
        for (InOutEntry entry : active) {
            if (entry.getEmployee().getId().equals(employeeId)) {
                throw new IllegalStateException("Employee is already checked out! Check in first.");
            }
        }

        InOutEntry entry = new InOutEntry();
        entry.setEmployee(employee);
        entry.setOutTime(outTime != null ? outTime : LocalDateTime.now());
        entry.setRemarks(remarks);
        
        return inOutEntryRepository.save(entry);
    }

    public InOutEntry logIn(Long entryId, LocalDateTime inTime) {
        InOutEntry entry = inOutEntryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Log entry not found"));

        if (entry.getInTime() != null) {
            throw new IllegalStateException("Employee is already checked in for this entry.");
        }

        LocalDateTime returnTime = inTime != null ? inTime : LocalDateTime.now();
        if (returnTime.isBefore(entry.getOutTime())) {
            throw new IllegalArgumentException("In-time cannot be before out-time!");
        }

        entry.setInTime(returnTime);
        long minutes = Duration.between(entry.getOutTime(), returnTime).toMinutes();
        entry.setDurationMinutes(minutes);

        return inOutEntryRepository.save(entry);
    }

    public void deleteEntry(Long id) {
        inOutEntryRepository.deleteById(id);
    }

    public Optional<InOutEntry> getEntryById(Long id) {
        return inOutEntryRepository.findById(id);
    }

    public List<Map<String, Object>> getMonthlySummary(int month, int year) {
        // Calculate the range for the selected month/year
        LocalDateTime start = LocalDateTime.of(year, month, 1, 0, 0, 0);
        // End of the month: add 1 month, subtract 1 second
        LocalDateTime end = start.plusMonths(1).minusSeconds(1);

        List<InOutEntry> entries = inOutEntryRepository.findEntriesInDateRange(start, end);
        List<Employee> allEmployees = employeeRepository.findAll();

        // Group entries by employee ID
        Map<Long, List<InOutEntry>> employeeEntriesMap = new HashMap<>();
        for (InOutEntry entry : entries) {
            Long empId = entry.getEmployee().getId();
            employeeEntriesMap.computeIfAbsent(empId, k -> new ArrayList<>()).add(entry);
        }

        List<Map<String, Object>> summaryList = new ArrayList<>();

        for (Employee emp : allEmployees) {
            List<InOutEntry> empEntries = employeeEntriesMap.getOrDefault(emp.getId(), Collections.emptyList());

            long totalMinutes = 0;
            int count = 0;

            for (InOutEntry entry : empEntries) {
                if (entry.getDurationMinutes() != null) {
                    totalMinutes += entry.getDurationMinutes();
                    count++;
                }
            }

            Map<String, Object> map = new HashMap<>();
            map.put("employee", emp);
            map.put("count", count);
            map.put("totalMinutes", totalMinutes);
            map.put("formattedDuration", formatMinutes(totalMinutes));

            summaryList.add(map);
        }

        // Sort summary by employee name
        summaryList.sort((a, b) -> {
            Employee empA = (Employee) a.get("employee");
            Employee empB = (Employee) b.get("employee");
            return empA.getName().compareToIgnoreCase(empB.getName());
        });

        return summaryList;
    }

    public String formatMinutes(long totalMinutes) {
        if (totalMinutes == 0) return "0 mins";
        long hours = totalMinutes / 60;
        long mins = totalMinutes % 60;
        if (hours > 0) {
            return String.format("%d hrs %d mins", hours, mins);
        } else {
            return String.format("%d mins", mins);
        }
    }
}
