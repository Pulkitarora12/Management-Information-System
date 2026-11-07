package com.notes.notes.benchmarks;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Generates the same deterministic dataset (same seed every call) regardless of
 * which storage strategy is about to receive it - so the benchmark compares the
 * strategies on identical data, not on different random draws (Phase 3 plan).
 */
class BenchmarkDataGenerator {

    static final long SEED = 20261107L;

    private static final String[] SHIFTS = {"A", "B", "C"};
    private static final String[] LINES = {"Line 1", "Line 2", "Line 3"};

    Map<String, Object> row(Random random, int index) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("part_no", "PN-" + (10000 + random.nextInt(900)));
        values.put("part_name", "Bracket Assembly " + (index % 50));
        int quantity = 1 + random.nextInt(500);
        values.put("quantity", quantity);
        values.put("weight", BigDecimal.valueOf(0.1 + random.nextDouble() * 9.9).setScale(3, java.math.RoundingMode.HALF_UP));
        values.put("due_date", LocalDate.of(2026, 1, 1).plusDays(random.nextInt(365)));
        values.put("is_rework", random.nextInt(10) == 0);
        values.put("shift", SHIFTS[random.nextInt(SHIFTS.length)]);
        values.put("line", LINES[random.nextInt(LINES.length)]);
        values.put("operator", "Operator-" + (1 + random.nextInt(30)));
        values.put("remarks", random.nextInt(5) == 0 ? "Needs inspection" : "OK");
        BigDecimal unitPrice = BigDecimal.valueOf(5 + random.nextDouble() * 95).setScale(2, java.math.RoundingMode.HALF_UP);
        values.put("unit_price", unitPrice);
        values.put("total_value", unitPrice.multiply(BigDecimal.valueOf(quantity)));
        return values;
    }
}
