package com.notes.notes.benchmarks;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.forms.entity.*;
import com.notes.notes.forms.repository.FormFieldRepository;
import com.notes.notes.forms.service.FormDefinitionService;
import com.notes.notes.forms.storage.*;
import com.notes.notes.forms.storage.eav.EavSubmissionStore;
import com.notes.notes.forms.storage.json.JsonSubmissionStore;
import com.notes.notes.forms.storage.relational.RelationalSubmissionStore;
import com.notes.notes.repository.authRepo.RoleRepository;
import com.notes.notes.repository.authRepo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * BENCHMARK_FINAL_PROMPT.md, Scenarios A+B only (see REPORT.md "Scope decisions"
 * for why 1M rows and Scenarios C/D/E/EXPLAIN-capture/PNG-charts were cut from
 * this run). Always against mis_bench, never mis or mis_test.
 *
 *   mvn test -Dtest=PaperBenchmarkRunner -DfailIfNoTests=false
 */
@SpringBootTest
@ActiveProfiles("bench")
class PaperBenchmarkRunner {

    private static final int[] SIZES = {10_000, 100_000};
    private static final int WARMUP_READS = 3;
    private static final int TIMED_READS = 10;
    private static final int WARMUP_WRITES = 1;
    private static final int TIMED_WRITES = 5;
    private static final int CHUNK = 1000;
    private static final int EXPORT_RUNS = 3;
    private static final int NUM_BENCH_USERS = 50;

    @Autowired private FormDefinitionService formDefinitionService;
    @Autowired private FormFieldRepository formFieldRepository;
    @Autowired private EavSubmissionStore eavSubmissionStore;
    @Autowired private JsonSubmissionStore jsonSubmissionStore;
    @Autowired private RelationalSubmissionStore relationalSubmissionStore;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JdbcTemplate jdbc;

    private BulkLoader bulkLoader;
    private final List<String[]> csvRows = new ArrayList<>();
    private final PaperDataGenerator generator = new PaperDataGenerator();
    private final List<String> orderLog = new ArrayList<>();
    private final List<String> skipLog = new ArrayList<>();
    private long runStart;

    private record Variant(String label, StorageType storageType, Set<String> indexedKeys) {
    }

    private static final List<Variant> VARIANTS = List.of(
            new Variant("EAV", StorageType.EAV, Set.of()),
            new Variant("JSON", StorageType.JSON, Set.of()),
            new Variant("JSON_IDX", StorageType.JSON, Set.of("part_no", "prod_date")),
            new Variant("JSON_IDX_LOW", StorageType.JSON, Set.of("shift")),
            new Variant("RELATIONAL", StorageType.RELATIONAL, Set.of("part_no", "prod_date", "shift"))
    );

    @Test
    void runBenchmark() throws IOException {
        runStart = System.nanoTime();
        bulkLoader = new BulkLoader(jdbc);
        long[] ownerUserIds = benchUsers();
        csvRows.add(new String[]{"strategy", "dataset_size", "scenario", "operation", "run", "value", "unit", "rows_returned", "timestamp"});

        for (int size : SIZES) {
            List<Variant> order = new ArrayList<>(VARIANTS);
            Collections.shuffle(order, new Random(PaperDataGenerator.ORDER_SEED + size));
            orderLog.add("size=" + size + " order=" + order.stream().map(Variant::label).toList() + " (seed=" + (PaperDataGenerator.ORDER_SEED + size) + ")");

            for (Variant variant : order) {
                long cellStart = System.nanoTime();
                System.out.println("=== " + variant.label() + " @ " + size + " rows (elapsed so far: " + minutesSoFar() + " min) ===");
                try {
                    runCell(variant, size, ownerUserIds);
                } catch (Exception e) {
                    skipLog.add(variant.label() + "@" + size + " FAILED: " + e);
                    System.out.println("!!! CELL FAILED, continuing: " + e);
                    e.printStackTrace();
                }
                double cellMinutes = (System.nanoTime() - cellStart) / 60_000_000_000.0;
                System.out.println("=== " + variant.label() + " @ " + size + " took " + String.format(Locale.ROOT, "%.1f", cellMinutes) + " min ===");
                if (cellMinutes > 45) {
                    skipLog.add(variant.label() + "@" + size + " exceeded 45 min (" + String.format(Locale.ROOT, "%.1f", cellMinutes) + " min) - per run discipline, stopping further cells.");
                    writeResults();
                    throw new IllegalStateException("Cell exceeded 45 minutes, stopping per run discipline: " + variant.label() + "@" + size);
                }
                writeResults(); // checkpoint after every cell so a crash doesn't lose data
            }
        }
        writeResults();
        System.out.println("TOTAL RUNTIME: " + minutesSoFar() + " min");
    }

    private double minutesSoFar() {
        return Math.round((System.nanoTime() - runStart) / 6_000_000_000.0) / 10.0;
    }

    // ------------------------------------------------------------------ cell ----

    private void runCell(Variant variant, int size, long[] ownerUserIds) {
        // Isolation (section 1.2): form_value/form_submission_json are shared across every
        // form - a leftover row from an earlier run (or a prior cell in THIS run) would
        // corrupt both the storage_size measurement (whole-table, not per-form) and this
        // cell's own ANALYZE statistics. RELATIONAL doesn't need this: its table is
        // per-form (CREATE TABLE in onFormCreated), already isolated.
        switch (variant.storageType()) {
            case EAV -> jdbc.execute("TRUNCATE TABLE form_value");
            case JSON -> jdbc.execute("TRUNCATE TABLE form_submission_json");
            case RELATIONAL -> { /* isolated per-form table already */ }
        }

        List<FormField> fields = buildFields(variant.indexedKeys());
        FormDefinition form = formDefinitionService.createForm(
                "paper_" + variant.label().toLowerCase() + "_" + size + "_" + System.nanoTime(),
                "Paper Benchmark " + variant.label() + " " + size, "PaperBenchmarkRunner",
                variant.storageType(), fields);
        List<FormField> allFields = form.getFields();
        SubmissionStore store = storeFor(variant.storageType());

        Random dataRandom = new Random(PaperDataGenerator.DATA_SEED);
        Random ownerRandom = new Random(PaperDataGenerator.DATA_SEED + 1);

        List<Long> submissionIds = bulkLoad(variant, size, form, allFields, store, dataRandom, ownerRandom, ownerUserIds);
        analyzeTables(variant, form);

        runReads(variant, size, form, allFields, store, submissionIds, ownerUserIds);
        runWrites(variant, size, form, allFields, store, submissionIds, dataRandom, ownerUserIds);
        runExportFull(variant, size, form, allFields, store);
        runSchemaEvolution(variant, size, form, allFields, store);
        measureStorageSize(variant, size, form);

        teardownCell(variant, form);
    }

    // ------------------------------------------------------------- bulk load ----

    private List<Long> bulkLoad(Variant variant, int size, FormDefinition form, List<FormField> fields,
                                 SubmissionStore store, Random dataRandom, Random ownerRandom, long[] ownerUserIds) {
        List<Long> allIds = new ArrayList<>(size);
        long totalStart = System.nanoTime();
        int run = 1;
        for (int inserted = 0; inserted < size; inserted += CHUNK) {
            int thisChunk = Math.min(CHUNK, size - inserted);
            List<Map<String, Object>> rows = new ArrayList<>(thisChunk);
            for (int i = 0; i < thisChunk; i++) rows.add(generator.row(dataRandom));

            long chunkStart = System.nanoTime();
            List<Long> ids = bulkLoader.batchInsertHeaders(form.getId(), form.getVersion(), thisChunk, ownerUserIds, ownerRandom);
            switch (variant.storageType()) {
                case EAV -> bulkLoader.batchInsertEav(ids, fields, rows);
                case JSON -> bulkLoader.batchInsertJson(ids, rows);
                case RELATIONAL -> bulkLoader.batchInsertRelational(form.getId(), ids, fields, rows);
            }
            long chunkElapsed = System.nanoTime() - chunkStart;
            csvRows.add(csvRow(variant.label(), size, "A", "insert_batch_1000", run++, ms(chunkElapsed), "ms", thisChunk));
            allIds.addAll(ids);
        }
        long totalElapsed = System.nanoTime() - totalStart;
        csvRows.add(csvRow(variant.label(), size, "A", "bulk_load_total", 1, ms(totalElapsed), "ms", size));
        return allIds;
    }

    private void analyzeTables(Variant variant, FormDefinition form) {
        jdbc.execute("ANALYZE TABLE form_submission");
        switch (variant.storageType()) {
            case EAV -> jdbc.execute("ANALYZE TABLE form_value");
            case JSON -> jdbc.execute("ANALYZE TABLE form_submission_json");
            case RELATIONAL -> jdbc.execute("ANALYZE TABLE form_data_" + form.getId());
        }
    }

    // ------------------------------------------------------------------ reads ----

    private void runReads(Variant variant, int size, FormDefinition form, List<FormField> fields,
                           SubmissionStore store, List<Long> submissionIds, long[] ownerUserIds) {
        Random pick = new Random(99);
        timed(variant.label(), size, "A", "find_by_id", true, () -> {
            Long id = submissionIds.get(pick.nextInt(submissionIds.size()));
            return store.findById(form, fields, id).isEmpty() ? 0 : 1;
        });

        timed(variant.label(), size, "A", "filter_eq_high", true, () -> {
            QuerySpec spec = new QuerySpec().addFilter(FilterCondition.eq("part_no", "PN-10500")).page(0, 100);
            return store.query(form, fields, spec).size();
        });

        timed(variant.label(), size, "A", "filter_eq_low", true, () -> {
            QuerySpec spec = new QuerySpec().addFilter(FilterCondition.eq("shift", "A")).page(0, 100);
            return store.query(form, fields, spec).size();
        });

        timed(variant.label(), size, "A", "filter_range_eq", true, () -> {
            QuerySpec spec = new QuerySpec()
                    .addFilter(FilterCondition.range("prod_date", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 30)))
                    .addFilter(FilterCondition.eq("machine", "Machine-5"))
                    .page(0, 100);
            return store.query(form, fields, spec).size();
        });

        timed(variant.label(), size, "A", "filter_3_fields", true, () -> {
            QuerySpec spec = new QuerySpec()
                    .addFilter(FilterCondition.eq("shift", "B"))
                    .addFilter(FilterCondition.eq("machine", "Machine-10"))
                    .addFilter(FilterCondition.range("qty_rejected", 5, null))
                    .page(0, 100);
            return store.query(form, fields, spec).size();
        });

        timed(variant.label(), size, "A", "like_search", true, () -> {
            QuerySpec spec = new QuerySpec().addFilter(FilterCondition.like("remarks", "crack")).page(0, 100);
            return store.query(form, fields, spec).size();
        });

        timed(variant.label(), size, "A", "aggregate_group", true, () -> {
            int a = store.aggregate(form, fields, new AggregateSpec(AggregateOp.SUM, "qty_produced", "machine")).size();
            int b = store.aggregate(form, fields, new AggregateSpec(AggregateOp.SUM, "qty_rejected", "machine")).size();
            return a + b;
        });

        timed(variant.label(), size, "A", "aggregate_date", true, () -> aggregateDateMonthly(variant, form, fields));

        timed(variant.label(), size, "A", "count_all", true, () -> (int) store.count(form, fields, new QuerySpec()));

        timed(variant.label(), size, "A", "page_first", true, () -> {
            QuerySpec spec = new QuerySpec().sortBy("prod_date", true).page(0, 50);
            return store.query(form, fields, spec).size();
        });

        timed(variant.label(), size, "A", "page_deep", true, () -> {
            QuerySpec spec = new QuerySpec().sortBy("prod_date", true).page(submissionIds.size() / 2 / 50, 50);
            return store.query(form, fields, spec).size();
        });

        timed(variant.label(), size, "A", "own_scope_page", true, () -> {
            QuerySpec spec = new QuerySpec().ownerId(ownerUserIds[0]).page(0, 50);
            return store.query(form, fields, spec).size();
        });
    }

    private int aggregateDateMonthly(Variant variant, FormDefinition form, List<FormField> fields) {
        FormField dateField = byKey(fields, "prod_date");
        FormField weightField = byKey(fields, "total_weight");
        String sql = switch (variant.storageType()) {
            case EAV -> "SELECT DATE_FORMAT(d.value_date,'%Y-%m') ym, SUM(w.value_num) FROM form_value d " +
                    "JOIN form_value w ON w.submission_id = d.submission_id AND w.field_id = " + weightField.getId() +
                    " WHERE d.field_id = " + dateField.getId() +
                    " AND d.value_date BETWEEN '2026-01-01' AND '2026-12-31' GROUP BY ym";
            case JSON -> "SELECT DATE_FORMAT(j.data->>'$.prod_date','%Y-%m') ym, SUM(CAST(j.data->>'$.total_weight' AS DECIMAL(20,6))) " +
                    "FROM form_submission_json j JOIN form_submission fs ON fs.id = j.submission_id " +
                    "WHERE fs.form_id = " + form.getId() +
                    " AND j.data->>'$.prod_date' BETWEEN '2026-01-01' AND '2026-12-31' GROUP BY ym";
            case RELATIONAL -> "SELECT DATE_FORMAT(" + dateField.relationalColumnName() + ",'%Y-%m') ym, SUM(" + weightField.relationalColumnName() + ") " +
                    "FROM form_data_" + form.getId() +
                    " WHERE " + dateField.relationalColumnName() + " BETWEEN '2026-01-01' AND '2026-12-31' GROUP BY ym";
        };
        return jdbc.queryForList(sql).size();
    }

    // ----------------------------------------------------------------- writes ----

    private void runWrites(Variant variant, int size, FormDefinition form, List<FormField> fields,
                            SubmissionStore store, List<Long> submissionIds, Random dataRandom, long[] ownerUserIds) {
        User owner = userRepository.findById(ownerUserIds[0]).orElseThrow();

        timedWrite(variant.label(), size, "A", "insert_single", () -> {
            Map<String, Object> row = generator.row(dataRandom);
            com.notes.notes.forms.entity.FormSubmission header = new com.notes.notes.forms.entity.FormSubmission();
            header.setForm(form);
            header.setFormVersion(form.getVersion());
            header.setCreatedBy(owner);
            Long id = autowiredSaveSubmission(header);
            store.save(form, fields, id, row);
            submissionIds.add(id);
            return 1;
        });

        Long updateTargetId = submissionIds.get(0);
        timedWrite(variant.label(), size, "A", "update_single", () -> {
            Map<String, Object> changes = Map.of(
                    "part_name", "Updated Part " + System.nanoTime() % 1000,
                    "qty_produced", 77,
                    "remarks", "updated by benchmark");
            Map<String, Object> merged = new LinkedHashMap<>(store.findById(form, fields, updateTargetId));
            merged.putAll(changes);
            store.update(form, fields, updateTargetId, merged);
            return 1;
        });

        timedWrite(variant.label(), size, "A", "delete_single", () -> {
            Map<String, Object> row = generator.row(dataRandom);
            com.notes.notes.forms.entity.FormSubmission header = new com.notes.notes.forms.entity.FormSubmission();
            header.setForm(form);
            header.setFormVersion(form.getVersion());
            header.setCreatedBy(owner);
            Long id = autowiredSaveSubmission(header);
            store.save(form, fields, id, row);
            store.delete(form, id);
            return 1;
        });
    }

    @Autowired
    private com.notes.notes.forms.repository.FormSubmissionRepository formSubmissionRepository;

    private Long autowiredSaveSubmission(com.notes.notes.forms.entity.FormSubmission header) {
        return formSubmissionRepository.save(header).getId();
    }

    // -------------------------------------------------------------- export ----

    private void runExportFull(Variant variant, int size, FormDefinition form, List<FormField> fields, SubmissionStore store) {
        for (int run = 1; run <= EXPORT_RUNS; run++) {
            long start = System.nanoTime();
            int total = 0;
            int page = 0;
            while (true) {
                QuerySpec spec = new QuerySpec().page(page, 5000);
                List<SubmissionRecord> batch = store.query(form, fields, spec);
                total += batch.size();
                if (batch.size() < 5000) break;
                page++;
            }
            long elapsed = System.nanoTime() - start;
            csvRows.add(csvRow(variant.label(), size, "A", "export_full", run, ms(elapsed), "ms", total));
        }
    }

    // ------------------------------------------------------- schema evolution ----

    private void runSchemaEvolution(Variant variant, int size, FormDefinition form, List<FormField> fields, SubmissionStore store) {
        long before = store.count(form, fields, new QuerySpec());

        FormField plainField = newField("audit_note", FieldType.TEXT, fields.size(), form);
        long t0 = System.nanoTime();
        FormField savedPlain = formFieldRepository.save(plainField);
        store.onFieldAdded(form, savedPlain);
        csvRows.add(csvRow(variant.label(), size, "B", "add_field_plain", 1, ms(System.nanoTime() - t0), "ms", 0));
        verifyReadable(variant, size, "add_field_plain", form, fields, store, before);

        FormField indexedField = newField("audit_flag", FieldType.BOOLEAN, fields.size() + 1, form);
        if (variant.storageType() != StorageType.EAV) {
            indexedField.setConfig("{\"indexed\":true}");
        }
        long t1 = System.nanoTime();
        if (variant.storageType() == StorageType.EAV) {
            csvRows.add(csvRow(variant.label(), size, "B", "add_field_indexed", 1, "0", "ms", 0));
            skipLog.add(variant.label() + "@" + size + " add_field_indexed: n/a for EAV - no DDL for new fields regardless of indexed flag");
        } else {
            FormField savedIndexed = formFieldRepository.save(indexedField);
            store.onFieldAdded(form, savedIndexed);
            csvRows.add(csvRow(variant.label(), size, "B", "add_field_indexed", 1, ms(System.nanoTime() - t1), "ms", 0));
        }
        verifyReadable(variant, size, "add_field_indexed", form, fields, store, before);

        long t2 = System.nanoTime();
        FormField remarksField = byKey(fields, "remarks");
        store.onFieldRemoved(form, remarksField);
        csvRows.add(csvRow(variant.label(), size, "B", "remove_field", 1, ms(System.nanoTime() - t2), "ms", 0));

        long t3 = System.nanoTime();
        remarksField.setLabel("Remarks (renamed)");
        formFieldRepository.save(remarksField);
        csvRows.add(csvRow(variant.label(), size, "B", "rename_label", 1, ms(System.nanoTime() - t3), "ms", 0));
    }

    private void verifyReadable(Variant variant, int size, String afterOp, FormDefinition form, List<FormField> fields, SubmissionStore store, long expectedCount) {
        long actual = store.count(form, fields, new QuerySpec());
        if (actual != expectedCount) {
            skipLog.add(variant.label() + "@" + size + " " + afterOp + ": count mismatch after schema change, expected " + expectedCount + " got " + actual);
        }
    }

    private FormField newField(String key, FieldType type, int order, FormDefinition form) {
        FormField f = new FormField();
        f.setFieldKey(key);
        f.setLabel(key);
        f.setType(type);
        f.setSortOrder(order);
        f.setForm(form);
        return f;
    }

    // ---------------------------------------------------------- storage size ----

    private void measureStorageSize(Variant variant, int size, FormDefinition form) {
        long dataBytes;
        long indexBytes;
        switch (variant.storageType()) {
            case RELATIONAL -> {
                Map<String, Object> row = jdbc.queryForMap(
                        "SELECT DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                        "form_data_" + form.getId());
                dataBytes = ((Number) row.get("DATA_LENGTH")).longValue();
                indexBytes = ((Number) row.get("INDEX_LENGTH")).longValue();
            }
            case EAV -> {
                Map<String, Object> row = jdbc.queryForMap(
                        "SELECT DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'form_value'");
                dataBytes = ((Number) row.get("DATA_LENGTH")).longValue();
                indexBytes = ((Number) row.get("INDEX_LENGTH")).longValue();
            }
            default -> {
                Map<String, Object> row = jdbc.queryForMap(
                        "SELECT DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'form_submission_json'");
                dataBytes = ((Number) row.get("DATA_LENGTH")).longValue();
                indexBytes = ((Number) row.get("INDEX_LENGTH")).longValue();
            }
        }
        csvRows.add(csvRow(variant.label(), size, "A", "storage_size_data", 1, String.valueOf(dataBytes), "bytes", size));
        csvRows.add(csvRow(variant.label(), size, "A", "storage_size_index", 1, String.valueOf(indexBytes), "bytes", size));
    }

    // -------------------------------------------------------------- teardown ----

    private void teardownCell(Variant variant, FormDefinition form) {
        switch (variant.storageType()) {
            case EAV -> jdbc.execute("TRUNCATE TABLE form_value");
            case JSON -> jdbc.execute("TRUNCATE TABLE form_submission_json");
            case RELATIONAL -> jdbc.execute("DROP TABLE IF EXISTS form_data_" + form.getId());
        }
        jdbc.update("DELETE FROM form_submission WHERE form_id = ?", form.getId());
        jdbc.update("DELETE FROM form_field WHERE form_id = ?", form.getId());
        jdbc.update("DELETE FROM form_definition WHERE id = ?", form.getId());
    }

    // ----------------------------------------------------------------- utils ----

    private SubmissionStore storeFor(StorageType type) {
        return switch (type) {
            case EAV -> eavSubmissionStore;
            case JSON -> jsonSubmissionStore;
            case RELATIONAL -> relationalSubmissionStore;
        };
    }

    private FormField byKey(List<FormField> fields, String key) {
        return fields.stream().filter(f -> f.getFieldKey().equals(key)).findFirst().orElseThrow();
    }

    private void timed(String strategy, int size, String scenario, String operation, boolean isRead, Supplier<Integer> op) {
        int warmup = isRead ? WARMUP_READS : WARMUP_WRITES;
        int runs = isRead ? TIMED_READS : TIMED_WRITES;
        for (int i = 0; i < warmup; i++) op.get();
        for (int run = 1; run <= runs; run++) {
            long start = System.nanoTime();
            int rows = op.get();
            long elapsed = System.nanoTime() - start;
            csvRows.add(csvRow(strategy, size, scenario, operation, run, ms(elapsed), "ms", rows));
        }
    }

    private void timedWrite(String strategy, int size, String scenario, String operation, Supplier<Integer> op) {
        timed(strategy, size, scenario, operation, false, op);
    }

    private String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private String[] csvRow(String strategy, int size, String scenario, String operation, int run, String value, String unit, long rowsReturned) {
        return new String[]{strategy, String.valueOf(size), scenario, operation, String.valueOf(run), value, unit,
                String.valueOf(rowsReturned), String.valueOf(System.currentTimeMillis())};
    }

    private List<FormField> buildFields(Set<String> indexedKeys) {
        List<FormField> fields = new ArrayList<>();
        fields.add(indexable("part_no", FieldType.TEXT, 0, indexedKeys));
        fields.add(indexable("part_name", FieldType.TEXT, 1, indexedKeys));
        fields.add(indexable("machine", FieldType.DROPDOWN, 2, indexedKeys));
        fields.add(indexable("shift", FieldType.DROPDOWN, 3, indexedKeys));
        fields.add(indexable("operator", FieldType.MASTER_REF, 4, indexedKeys));
        fields.add(indexable("prod_date", FieldType.DATE, 5, indexedKeys));
        fields.add(indexable("qty_produced", FieldType.NUMBER, 6, indexedKeys));
        fields.add(indexable("qty_rejected", FieldType.NUMBER, 7, indexedKeys));
        fields.add(indexable("gw", FieldType.DECIMAL, 8, indexedKeys));
        fields.add(indexable("total_weight", FieldType.CALCULATED, 9, indexedKeys));
        fields.add(indexable("ok_flag", FieldType.BOOLEAN, 10, indexedKeys));
        fields.add(indexable("remarks", FieldType.TEXT, 11, indexedKeys));
        return fields;
    }

    private FormField indexable(String key, FieldType type, int order, Set<String> indexedKeys) {
        FormField f = new FormField();
        f.setFieldKey(key);
        f.setLabel(key);
        f.setType(type);
        f.setSortOrder(order);
        if (indexedKeys.contains(key)) {
            f.setConfig("{\"indexed\":true}");
        }
        return f;
    }

    private long[] benchUsers() {
        Role legacyUserRole = roleRepository.findByRoleName(AppRole.ROLE_USER)
                .orElseThrow(() -> new IllegalStateException("Legacy ROLE_USER not seeded"));
        long[] ids = new long[NUM_BENCH_USERS];
        for (int i = 0; i < NUM_BENCH_USERS; i++) {
            String username = "pbu" + i;
            User user = userRepository.findByUserName(username).orElseGet(() -> {
                User u = new User();
                u.setUserName(username);
                u.setEmail(username + "@example.com");
                u.setPassword("bench-password-hash");
                u.setEnabled(true);
                u.setVerified(true);
                u.setAccountNonLocked(true);
                u.setAccountNonExpired(true);
                u.setCredentialsNonExpired(true);
                u.setRole(legacyUserRole);
                return userRepository.save(u);
            });
            ids[i] = user.getUserId();
        }
        return ids;
    }

    // ------------------------------------------------------------- output ----

    private void writeResults() throws IOException {
        Path resultsDir = Path.of("benchmarks", "results");
        Files.createDirectories(resultsDir);

        Path rawCsv = resultsDir.resolve("raw_paper.csv");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(rawCsv))) {
            for (String[] row : csvRows) writer.println(String.join(",", row));
        }

        writeSummary(resultsDir);

        Path logPath = resultsDir.resolve("run_log.txt");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logPath))) {
            writer.println("Variant order per size:");
            orderLog.forEach(writer::println);
            writer.println();
            writer.println("Skips/anomalies during run:");
            skipLog.forEach(writer::println);
            writer.println();
            writer.println("Total runtime so far: " + minutesSoFar() + " min");
        }
    }

    private void writeSummary(Path resultsDir) throws IOException {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (String[] r : csvRows.subList(1, csvRows.size())) {
            if (!r[6].equals("ms")) continue; // byte-unit rows (storage_size_*) go in a separate file
            String key = String.join("|", r[0], r[1], r[2], r[3]);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(Double.parseDouble(r[5]));
        }
        Path summaryCsv = resultsDir.resolve("summary.csv");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(summaryCsv))) {
            writer.println("strategy,dataset_size,scenario,operation,n,mean_ms,std_ms,min_ms,p50_ms,p95_ms,p99_ms,max_ms");
            for (Map.Entry<String, List<Double>> entry : grouped.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                PaperStats s = PaperStats.of(entry.getValue());
                writer.println(String.join(",", parts[0], parts[1], parts[2], parts[3], String.valueOf(s.n()),
                        fmt(s.meanMs()), fmt(s.stdDevMs()), fmt(s.minMs()), fmt(s.p50Ms()), fmt(s.p95Ms()), fmt(s.p99Ms()), fmt(s.maxMs())));
            }
        }

        Path storageCsv = resultsDir.resolve("storage_size_summary.csv");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(storageCsv))) {
            writer.println("strategy,dataset_size,operation,bytes");
            for (String[] r : csvRows.subList(1, csvRows.size())) {
                if (r[6].equals("bytes")) {
                    writer.println(String.join(",", r[0], r[1], r[3], r[5]));
                }
            }
        }
    }

    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
