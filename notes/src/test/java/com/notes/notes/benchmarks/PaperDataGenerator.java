package com.notes.notes.benchmarks;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** BENCHMARK_FINAL_PROMPT.md section 3: the 12-field "Production Log" dataset. */
class PaperDataGenerator {

    static final long DATA_SEED = 20261107L;
    static final long ORDER_SEED = 42L;

    private static final String[] SHIFTS = {"A", "B", "C"};
    private static final int PART_NO_CARDINALITY = 2000;
    private static final int MACHINE_CARDINALITY = 25;
    private static final int OPERATOR_CARDINALITY = 150;

    Map<String, Object> row(Random random) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("part_no", "PN-" + (10000 + random.nextInt(PART_NO_CARDINALITY)));
        values.put("part_name", "Bracket Assembly " + random.nextInt(50));
        values.put("machine", "Machine-" + (1 + random.nextInt(MACHINE_CARDINALITY)));
        values.put("shift", SHIFTS[random.nextInt(SHIFTS.length)]);
        values.put("operator", "Operator-" + (1 + random.nextInt(OPERATOR_CARDINALITY)));
        values.put("prod_date", LocalDate.of(2025, 1, 1).plusDays(random.nextInt(730)));
        int qtyProduced = 1 + random.nextInt(500);
        int qtyRejected = random.nextInt(qtyProduced / 10 + 1);
        values.put("qty_produced", qtyProduced);
        values.put("qty_rejected", qtyRejected);
        BigDecimal gw = BigDecimal.valueOf(0.1 + random.nextDouble() * 9.9).setScale(3, java.math.RoundingMode.HALF_UP);
        values.put("gw", gw);
        values.put("total_weight", gw.multiply(BigDecimal.valueOf(qtyProduced)));
        values.put("ok_flag", qtyRejected == 0);
        values.put("remarks", random.nextInt(10) == 0 ? "Visible crack on surface" : (random.nextInt(3) == 0 ? "Minor wear" : ""));
        return values;
    }
}
