package com.notes.notes.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class TimeUtil {

    public static String getRemainingTime(LocalDate dueDate) {

        if (dueDate == null) {
            return "No due date";
        }

        // Assume task is due at end of the due date
        LocalDateTime dueDateTime = dueDate.atTime(23, 59);

        LocalDateTime now = LocalDateTime.now();

        // Overdue case
        if (now.isAfter(dueDateTime)) {
            Duration overdue = Duration.between(dueDateTime, now);

            long days = overdue.toDays();
            long hours = overdue.minusDays(days).toHours();
            long minutes = overdue.minusDays(days)
                    .minusHours(hours)
                    .toMinutes();

            return "Overdue by " + days + " days " + hours + " hours " + minutes + " minutes";
        }

        // Remaining time
        Duration duration = Duration.between(now, dueDateTime);

        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days)
                .minusHours(hours)
                .toMinutes();

        // Polished messages
        if (days == 0 && hours == 0) {
            return "Due in " + minutes + " minutes";
        }

        if (days == 0) {
            return "Due in " + hours + " hours " + minutes + " minutes";
        }

        return days + " days " + hours + " hours " + minutes + " minutes left";
    }
}
