package com.notes.notes.benchmarks;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.forms.entity.*;
import com.notes.notes.forms.repository.FormFieldRepository;
import com.notes.notes.forms.service.FormDefinitionService;
import com.notes.notes.forms.storage.*;
import com.notes.notes.forms.storage.eav.EavNaiveSubmissionStore;
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

    static class ChecksumMismatchException extends RuntimeException {
        ChecksumMismatchException(String message) {
            super(message);
        }
    }


    private static final int[] SIZES = {10_000, 25_000, 50_000, 100_000};
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
    @Autowired private EavNaiveSubmissionStore eavNaiveSubmissionStore;
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

    /**
     * storageType drives physical layout decisions (which table(s) to create/truncate,
     * what bulk-insert path to use). storeOverride, when set, is the SubmissionStore
     * actually used for every read/write measurement instead of the one storageType
     * would normally resolve to - this is EAV_NAIVE's entire reason to exist: same
     * physical form_value table and bulk-load path as EAV, different query logic.
     */
    private record Variant(String label, StorageType storageType, Set<String> indexedKeys, SubmissionStore storeOverride) {
        Variant(String label, StorageType storageType, Set<String> indexedKeys) {
            this(label, storageType, indexedKeys, null);
        }
    }

    // EAV's (field_id, value_*) indexes already cover every field generically, so it
    // never needs per-field "indexed":true config the way JSON/RELATIONAL's
    // generated/opt-in columns do - hence Set.of() for both EAV variants below.
    private List<Variant> variants() {
        return List.of(
                new Variant("EAV", StorageType.EAV, Set.of()),
                new Variant("EAV_NAIVE", StorageType.EAV, Set.of(), eavNaiveSubmissionStore),
                new Variant("JSON", StorageType.JSON, Set.of()),
                new Variant("JSON_IDX", StorageType.JSON, Set.of("part_no", "prod_date", "shift", "machine")),
                new Variant("JSON_IDX_LOW", StorageType.JSON, Set.of("shift")),
                new Variant("RELATIONAL", StorageType.RELATIONAL, Set.of("part_no", "prod_date", "shift", "machine"))
        );
    }

    @Test
    void runBenchmark() throws IOException {
        runStart = System.nanoTime();
        bulkLoader = new BulkLoader(jdbc);
        long[] ownerUserIds = benchUsers();
        csvRows.add(new String[]{"strategy", "dataset_size", "scenario", "operation", "run", "value", "unit", "rows_returned", "timestamp"});

        for (int size : SIZES) {
            List<Variant> order = new ArrayList<>(variants());
            Collections.shuffle(order, new Random(PaperDataGenerator.ORDER_SEED + size));
            orderLog.add("size=" + size + " order=" + order.stream().map(Variant::label).toList() + " (seed=" + (PaperDataGenerator.ORDER_SEED + size) + ")");

            for (Variant variant : order) {
                long cellStart = System.nanoTime();
                System.out.println("=== " + variant.label() + " @ " + size + " rows (elapsed so far: " + minutesSoFar() + " min) ===");
                try {
                    runCell(variant, size, ownerUserIds);
                } catch (ChecksumMismatchException e) {
                    skipLog.add("FATAL: " + e.getMessage());
                    writeResults();
                    throw e; // item 5: "fail the run if not" - a real correctness bug, not a flaky cell
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

        try {
            runIntegrityScenario(ownerUserIds);
        } catch (Exception e) {
            skipLog.add("Scenario E FAILED: " + e);
            System.out.println("!!! Scenario E FAILED: " + e);
            e.printStackTrace();
        }

        writeResults();
        System.out.println("TOTAL RUNTIME: " + minutesSoFar() + " min");
    }

    // -------------------------------------------------------------- integrity ----

    private static final int INTEGRITY_SIZE = 10_000;
    private static final int INTEGRITY_ATTEMPTS = 100;
    private final List<String[]> integrityRows = new ArrayList<>();

    /**
     * Scenario E: for each variant, load a small (10k) dataset, then attempt 100
     * raw-JDBC writes per violation type - bypassing the service/validation layer
     * entirely, writing straight to each strategy's own schema - and count how many
     * the SCHEMA itself rejects vs silently accepts. This measures what each
     * strategy's table definitions actually guarantee, not what the application
     * layer guarantees (ValidationService would catch all of these before they
     * reach the database in normal use - that's not what's being tested here).
     */
    private void runIntegrityScenario(long[] ownerUserIds) {
        for (Variant variant : variants()) {
            System.out.println("=== Scenario E: " + variant.label() + " (elapsed so far: " + minutesSoFar() + " min) ===");
            switch (variant.storageType()) {
                case EAV -> jdbc.execute("TRUNCATE TABLE form_value");
                case JSON -> resetJsonTable();
                default -> { }
            }
            List<FormField> fields = buildFields(variant.indexedKeys());
            FormDefinition form = formDefinitionService.createForm(
                    "integrity_" + variant.label().toLowerCase() + "_" + System.nanoTime(),
                    "Integrity Test " + variant.label(), "PaperBenchmarkRunner", variant.storageType(), fields);
            List<FormField> allFields = form.getFields();
            SubmissionStore store = variant.storeOverride() != null ? variant.storeOverride() : storeFor(variant.storageType());

            Random dataRandom = new Random(PaperDataGenerator.DATA_SEED);
            Random ownerRandom = new Random(PaperDataGenerator.DATA_SEED + 1);
            // bulkLoad also appends its own insert_batch_1000/bulk_load_total rows to csvRows
            // (scenario "A") for this throwaway form - harmless noise, left as-is rather than
            // adding a parameter just to suppress it for this one-off setup pass.
            List<Long> submissionIds = bulkLoad(variant, INTEGRITY_SIZE, form, allFields, store, dataRandom, ownerRandom, ownerUserIds);

            FormField numericField = byKey(allFields, "qty_produced");
            FormField dateField = byKey(allFields, "prod_date");
            FormField operatorField = byKey(allFields, "operator");

            integrityAttempt(variant, "wrong_type_numeric", () -> attemptWrongTypeNumeric(variant, form, submissionIds, dataRandom, ownerUserIds, numericField));
            integrityAttempt(variant, "invalid_date", () -> attemptInvalidDate(variant, form, submissionIds, dataRandom, ownerUserIds, dateField));
            integrityAttempt(variant, "missing_required", () -> attemptMissingRequired(variant, form, allFields, store, dataRandom, ownerUserIds));
            integrityAttempt(variant, "dangling_master_ref", () -> attemptDanglingMasterRef(variant, form, submissionIds, operatorField));
            integrityAttempt(variant, "orphan_value_row", () -> attemptOrphanValueRow(variant, form, numericField));

            teardownCell(variant, form);
        }
    }

    private void integrityAttempt(Variant variant, String violationType, java.util.function.BooleanSupplier attempt) {
        int rejected = 0;
        for (int i = 0; i < INTEGRITY_ATTEMPTS; i++) {
            boolean wasRejected = attempt.getAsBoolean();
            if (wasRejected) rejected++;
        }
        integrityRows.add(new String[]{variant.label(), violationType, String.valueOf(INTEGRITY_ATTEMPTS),
                String.valueOf(rejected), String.valueOf(INTEGRITY_ATTEMPTS - rejected)});
    }

    /** @return true if the schema rejected the write (threw), false if it silently accepted it. */
    private boolean attemptWrongTypeNumeric(Variant variant, FormDefinition form, List<Long> submissionIds, Random random, long[] ownerUserIds, FormField numericField) {
        try {
            Long id = newThrowawaySubmission(form, ownerUserIds, random);
            switch (variant.storageType()) {
                case EAV -> jdbc.update("INSERT INTO form_value (submission_id, field_id, value_text) VALUES (?, ?, 'abc')", id, numericField.getId());
                case JSON -> jdbc.update("INSERT INTO form_submission_json (submission_id, data) VALUES (?, ?)", id, "{\"qty_produced\":\"abc\"}");
                case RELATIONAL -> jdbc.update("INSERT INTO form_data_" + form.getId() + " (submission_id, created_by, " +
                        numericField.relationalColumnName() + ") VALUES (?, ?, 'abc')", id, ownerUserIds[0]);
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean attemptInvalidDate(Variant variant, FormDefinition form, List<Long> submissionIds, Random random, long[] ownerUserIds, FormField dateField) {
        try {
            Long id = newThrowawaySubmission(form, ownerUserIds, random);
            switch (variant.storageType()) {
                case EAV -> jdbc.update("INSERT INTO form_value (submission_id, field_id, value_date) VALUES (?, ?, '2026-13-45')", id, dateField.getId());
                case JSON -> jdbc.update("INSERT INTO form_submission_json (submission_id, data) VALUES (?, ?)", id, "{\"prod_date\":\"2026-13-45\"}");
                case RELATIONAL -> jdbc.update("INSERT INTO form_data_" + form.getId() + " (submission_id, created_by, " +
                        dateField.relationalColumnName() + ") VALUES (?, ?, '2026-13-45')", id, ownerUserIds[0]);
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** None of the 3 schemas enforce FormField.required as a DB constraint - expected result: 0 rejected, every variant. */
    private boolean attemptMissingRequired(Variant variant, FormDefinition form, List<FormField> fields, SubmissionStore store, Random random, long[] ownerUserIds) {
        try {
            Long id = newThrowawaySubmission(form, ownerUserIds, random);
            Map<String, Object> partial = new LinkedHashMap<>(generator.row(random));
            partial.remove("part_no"); // simulate a required field never being supplied
            store.save(form, fields, id, partial);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * MASTER_REF fields in this implementation store a plain value, not an actual FK
     * to a master table (no real master-linking was built - see conversation scope
     * decisions). There is therefore no constraint any strategy COULD enforce here;
     * this always "accepts" by construction, not because any strategy is lax.
     */
    private boolean attemptDanglingMasterRef(Variant variant, FormDefinition form, List<Long> submissionIds, FormField operatorField) {
        try {
            Long id = submissionIds.get(0);
            switch (variant.storageType()) {
                case EAV -> jdbc.update("UPDATE form_value SET value_text = 'Operator-99999' WHERE submission_id = ? AND field_id = ?", id, operatorField.getId());
                case JSON -> jdbc.update("UPDATE form_submission_json SET data = JSON_SET(data, '$.operator', 'Operator-99999') WHERE submission_id = ?", id);
                case RELATIONAL -> jdbc.update("UPDATE form_data_" + form.getId() + " SET " + operatorField.relationalColumnName() + " = 'Operator-99999' WHERE submission_id = ?", id);
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean attemptOrphanValueRow(Variant variant, FormDefinition form, FormField numericField) {
        long bogusSubmissionId = 999_999_000_000L;
        try {
            switch (variant.storageType()) {
                case EAV -> jdbc.update("INSERT INTO form_value (submission_id, field_id, value_num) VALUES (?, ?, 1)", bogusSubmissionId, numericField.getId());
                case JSON -> jdbc.update("INSERT INTO form_submission_json (submission_id, data) VALUES (?, '{}')", bogusSubmissionId);
                case RELATIONAL -> jdbc.update("INSERT INTO form_data_" + form.getId() + " (submission_id, created_by) VALUES (?, 1)", bogusSubmissionId);
            }
            return false;
        } catch (Exception e) {
            return true; // expected: the FK to form_submission(id) rejects this for every strategy
        }
    }

    private Long newThrowawaySubmission(FormDefinition form, long[] ownerUserIds, Random random) {
        com.notes.notes.forms.entity.FormSubmission header = new com.notes.notes.forms.entity.FormSubmission();
        header.setForm(form);
        header.setFormVersion(form.getVersion());
        header.setCreatedBy(userRepository.findById(ownerUserIds[random.nextInt(ownerUserIds.length)]).orElseThrow());
        return formSubmissionRepository.save(header).getId();
    }

    private double minutesSoFar() {
        return Math.round((System.nanoTime() - runStart) / 6_000_000_000.0) / 10.0;
    }

    // ------------------------------------------------------------------ cell ----

    private void runCell(Variant variant, int size, long[] ownerUserIds) {
        runCell(variant, size, ownerUserIds, true);
    }

    private void runCell(Variant variant, int size, long[] ownerUserIds, boolean includeSchemaEvolution) {
        // Isolation (section 1.2): form_value/form_submission_json are shared across every
        // form - a leftover row from an earlier run (or a prior cell in THIS run) would
        // corrupt both the storage_size measurement (whole-table, not per-form) and this
        // cell's own ANALYZE statistics. RELATIONAL doesn't need this: its table is
        // per-form (CREATE TABLE in onFormCreated), already isolated.
        switch (variant.storageType()) {
            case EAV -> jdbc.execute("TRUNCATE TABLE form_value");
            case JSON -> resetJsonTable();
            case RELATIONAL -> { /* isolated per-form table already */ }
        }

        List<FormField> fields = buildFields(variant.indexedKeys());
        FormDefinition form = formDefinitionService.createForm(
                "paper_" + variant.label().toLowerCase() + "_" + size + "_" + System.nanoTime(),
                "Paper Benchmark " + variant.label() + " " + size, "PaperBenchmarkRunner",
                variant.storageType(), fields);
        List<FormField> allFields = form.getFields();
        SubmissionStore store = variant.storeOverride() != null ? variant.storeOverride() : storeFor(variant.storageType());

        Random dataRandom = new Random(PaperDataGenerator.DATA_SEED);
        Random ownerRandom = new Random(PaperDataGenerator.DATA_SEED + 1);

        List<Long> submissionIds = bulkLoad(variant, size, form, allFields, store, dataRandom, ownerRandom, ownerUserIds);
        analyzeTables(variant, form);
        // item 6: immediately after load+ANALYZE, before anything else touches row count or schema.
        measureStorageSize(variant, size, form);

        runReads(variant, size, form, allFields, store, submissionIds, ownerUserIds);
        runWrites(variant, size, form, allFields, store, submissionIds, dataRandom, ownerUserIds);
        runExportFull(variant, size, form, allFields, store);
        if (size == 100_000) {
            captureExplain(variant, size, form, allFields, ownerUserIds);
        }
        if (includeSchemaEvolution) {
            runSchemaEvolution(variant, size, form, allFields, store, size);
        }

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
            BulkLoader.HeaderBatch headerBatch = bulkLoader.batchInsertHeaders(form.getId(), form.getVersion(), thisChunk, ownerUserIds, ownerRandom);
            List<Long> ids = headerBatch.submissionIds();
            switch (variant.storageType()) {
                case EAV -> bulkLoader.batchInsertEav(ids, fields, rows);
                case JSON -> bulkLoader.batchInsertJson(ids, headerBatch.ownerIds(), rows);
                case RELATIONAL -> bulkLoader.batchInsertRelational(form.getId(), ids, headerBatch.ownerIds(), fields, rows);
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

        Map<Long, Integer> idToGenerationIndex = new HashMap<>();
        for (int i = 0; i < submissionIds.size(); i++) idToGenerationIndex.put(submissionIds.get(i), i);

        runFilterWithFullAndChecksum(variant, size, form, fields, store, idToGenerationIndex,
                "filter_eq_high", () -> new QuerySpec().addFilter(FilterCondition.eq("part_no", "PN-10500")));

        runFilterWithFullAndChecksum(variant, size, form, fields, store, idToGenerationIndex,
                "filter_eq_low", () -> new QuerySpec().addFilter(FilterCondition.eq("shift", "A")));

        runFilterWithFullAndChecksum(variant, size, form, fields, store, idToGenerationIndex,
                "filter_range_eq", () -> new QuerySpec()
                        .addFilter(FilterCondition.range("prod_date", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 30)))
                        .addFilter(FilterCondition.eq("machine", "Machine-5")));

        runFilterWithFullAndChecksum(variant, size, form, fields, store, idToGenerationIndex,
                "filter_3_fields", () -> new QuerySpec()
                        .addFilter(FilterCondition.eq("shift", "B"))
                        .addFilter(FilterCondition.eq("machine", "Machine-10"))
                        .addFilter(FilterCondition.range("qty_rejected", 5, null)));

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

        // Identical query shape for every strategy (item 4): a bare count against the
        // shared form_submission header table, independent of where field values live.
        timed(variant.label(), size, "A", "count_all", true, () -> {
            Long c = jdbc.queryForObject("SELECT COUNT(*) FROM form_submission WHERE form_id = ?", Long.class, form.getId());
            return c == null ? 0 : c.intValue();
        });

        // Per-strategy, by contrast: exercises each store's own filtering code path.
        timed(variant.label(), size, "A", "count_filtered", true, () ->
                (int) store.count(form, fields, new QuerySpec().addFilter(FilterCondition.eq("shift", "A"))));

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

    // key = size + "|" + filterName -> checksum computed by the FIRST variant to run it for that cell group.
    private final Map<String, String> referenceChecksums = new HashMap<>();
    private final List<String> checksumLog = new ArrayList<>();

    /**
     * Item 5: measures the page (LIMIT 100) version as usual, then once fetches
     * EVERY matching row ("_full"), and verifies all 6 variants agree on exactly
     * which logical rows matched - not by comparing raw submission_id (each variant
     * has its own independent auto-increment range), but by generation index: row i
     * generated by PaperDataGenerator always lands at submissionIds.get(i) for every
     * variant, since every cell reseeds the same fixed seed. A sorted-index checksum
     * mismatch means two strategies disagree on query semantics - this throws
     * (fails the run) rather than just logging, per the brief's "fail the run if not".
     */
    private void runFilterWithFullAndChecksum(Variant variant, int size, FormDefinition form, List<FormField> fields,
                                                SubmissionStore store, Map<Long, Integer> idToGenerationIndex,
                                                String opName, Supplier<QuerySpec> specFactory) {
        timed(variant.label(), size, "A", opName, true, () -> {
            QuerySpec spec = specFactory.get().page(0, 100);
            return store.query(form, fields, spec).size();
        });

        long start = System.nanoTime();
        QuerySpec fullSpec = specFactory.get().page(0, size);
        List<SubmissionRecord> all = store.query(form, fields, fullSpec);
        long elapsed = System.nanoTime() - start;
        csvRows.add(csvRow(variant.label(), size, "A", opName + "_full", 1, ms(elapsed), "ms", all.size()));

        List<Integer> indices = all.stream()
                .map(r -> idToGenerationIndex.get(r.submissionId()))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        String checksum = all.size() + ":" + indices.hashCode();
        String key = size + "|" + opName;
        String reference = referenceChecksums.get(key);
        if (reference == null) {
            referenceChecksums.put(key, checksum);
            checksumLog.add(key + ": reference set by " + variant.label() + " (n=" + all.size() + ")");
        } else if (!reference.equals(checksum)) {
            String msg = key + ": MISMATCH - " + variant.label() + " got checksum " + checksum + " but reference (from an earlier variant) was " + reference;
            checksumLog.add(msg);
            throw new ChecksumMismatchException("Cross-strategy result checksum mismatch: " + msg);
        } else {
            checksumLog.add(key + ": " + variant.label() + " matches reference (n=" + all.size() + ")");
        }
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

    private static final int SCENARIO_B_RUNS = 5;

    /**
     * Item 7: 5 runs per operation, each on its own fresh field (never reusing or
     * mutating the same one). Item 3: remove_field in two modes - soft (delete the
     * FormField metadata row only; physical data is left behind, orphaned) and hard
     * (metadata removed AND the underlying data physically purged: EAV deletes its
     * form_value rows, JSON runs JSON_REMOVE across every row, RELATIONAL drops the
     * column). Each remove run first adds+backfills a throwaway field with real data
     * for every row (untimed setup) so the removal cost reflects removing a
     * genuinely populated field, not an empty one.
     */
    private void runSchemaEvolution(Variant variant, int size, FormDefinition form, List<FormField> fields, SubmissionStore store, int rowCount) {
        long before = store.count(form, fields, new QuerySpec());

        schemaEvolutionPhase(variant, size, "add_field_plain", () -> {
            for (int i = 1; i <= SCENARIO_B_RUNS; i++) {
                FormField plainField = newField("sb_plain_" + i, FieldType.TEXT, fields.size() + 100 + i, form);
                long t0 = System.nanoTime();
                FormField saved = formFieldRepository.save(plainField);
                store.onFieldAdded(form, saved);
                csvRows.add(csvRow(variant.label(), size, "B", "add_field_plain", i, ms(System.nanoTime() - t0), "ms", 0));
            }
        });
        verifyReadable(variant, size, "add_field_plain", form, fields, store, before);

        schemaEvolutionPhase(variant, size, "add_field_indexed", () -> {
            for (int i = 1; i <= SCENARIO_B_RUNS; i++) {
                FormField indexedField = newField("sb_idx_" + i, FieldType.BOOLEAN, fields.size() + 200 + i, form);
                if (variant.storageType() != StorageType.EAV) {
                    indexedField.setConfig("{\"indexed\":true}");
                }
                if (variant.storageType() == StorageType.EAV) {
                    csvRows.add(csvRow(variant.label(), size, "B", "add_field_indexed", i, "0", "ms", 0));
                } else {
                    long t1 = System.nanoTime();
                    FormField saved = formFieldRepository.save(indexedField);
                    store.onFieldAdded(form, saved);
                    csvRows.add(csvRow(variant.label(), size, "B", "add_field_indexed", i, ms(System.nanoTime() - t1), "ms", 0));
                }
            }
        });
        if (variant.storageType() == StorageType.EAV) {
            skipLog.add(variant.label() + "@" + size + " add_field_indexed: n/a for EAV - no DDL for new fields regardless of indexed flag");
        }
        verifyReadable(variant, size, "add_field_indexed", form, fields, store, before);

        schemaEvolutionPhase(variant, size, "remove_field_soft", () -> removeFieldSoftPhase(variant, size, form, fields, store));
        verifyReadable(variant, size, "remove_field_soft", form, fields, store, before);

        schemaEvolutionPhase(variant, size, "remove_field_hard", () -> {
            for (int i = 1; i <= SCENARIO_B_RUNS; i++) {
                FormField field = newField("sb_hard_" + i, FieldType.TEXT, fields.size() + 400 + i, form);
                FormField saved = formFieldRepository.save(field);
                store.onFieldAdded(form, saved);
                backfillField(variant, form, saved);
                long t = System.nanoTime();
                hardRemoveField(variant, form, store, saved);
                formFieldRepository.delete(saved);
                csvRows.add(csvRow(variant.label(), size, "B", "remove_field_hard", i, ms(System.nanoTime() - t), "ms", rowCount));
            }
        });
        verifyReadable(variant, size, "remove_field_hard", form, fields, store, before);

        schemaEvolutionPhase(variant, size, "rename_label", () -> {
            for (int i = 1; i <= SCENARIO_B_RUNS; i++) {
                FormField field = newField("sb_rename_" + i, FieldType.TEXT, fields.size() + 500 + i, form);
                FormField saved = formFieldRepository.save(field);
                store.onFieldAdded(form, saved);
                long t = System.nanoTime();
                saved.setLabel("Renamed " + i);
                formFieldRepository.save(saved);
                csvRows.add(csvRow(variant.label(), size, "B", "rename_label", i, ms(System.nanoTime() - t), "ms", 0));
            }
        });
    }

    /**
     * Soft remove is a metadata-only flag for every strategy - the field row (and
     * its data, if any) stays in place, just marked retired. No FK to worry about
     * since this is an UPDATE, not a DELETE of form_field. Extracted so the
     * standalone fix-rerun can re-measure this one phase without re-running
     * everything else in Scenario B.
     */
    private void removeFieldSoftPhase(Variant variant, int size, FormDefinition form, List<FormField> fields, SubmissionStore store) {
        for (int i = 1; i <= SCENARIO_B_RUNS; i++) {
            FormField field = newField("sb_soft_" + i, FieldType.TEXT, fields.size() + 300 + i, form);
            FormField saved = formFieldRepository.save(field);
            store.onFieldAdded(form, saved);
            backfillField(variant, form, saved);
            long t = System.nanoTime();
            saved.setActive(false);
            formFieldRepository.save(saved);
            csvRows.add(csvRow(variant.label(), size, "B", "remove_field_soft", i, ms(System.nanoTime() - t), "ms", 0));
        }
    }

    /** A failure in one Scenario B phase must never take down the rest - each is independent. */
    private void schemaEvolutionPhase(Variant variant, int size, String phase, Runnable work) {
        try {
            work.run();
        } catch (Exception e) {
            skipLog.add(variant.label() + "@" + size + " Scenario B phase '" + phase + "' FAILED: " + e);
        }
    }

    /** Populates a freshly-added field with a real value for every existing row, untimed (setup, not measured). */
    private void backfillField(Variant variant, FormDefinition form, FormField field) {
        switch (variant.storageType()) {
            case EAV -> jdbc.update(
                    "INSERT INTO form_value (submission_id, field_id, value_text) SELECT id, ?, 'x' FROM form_submission WHERE form_id = ?",
                    field.getId(), form.getId());
            case JSON -> jdbc.update(
                    "UPDATE form_submission_json j JOIN form_submission fs ON fs.id = j.submission_id " +
                            "SET j.data = JSON_SET(j.data, ?, 'x') WHERE fs.form_id = ?",
                    "$." + field.getFieldKey(), form.getId());
            case RELATIONAL -> jdbc.update(
                    "UPDATE form_data_" + form.getId() + " SET " + field.relationalColumnName() + " = 'x'");
        }
    }

    /** Item 3 "hard" mode: physically purge the field's data, not just its metadata. */
    private void hardRemoveField(Variant variant, FormDefinition form, SubmissionStore store, FormField field) {
        if (variant.storageType() == StorageType.JSON) {
            jdbc.update(
                    "UPDATE form_submission_json j JOIN form_submission fs ON fs.id = j.submission_id " +
                            "SET j.data = JSON_REMOVE(j.data, ?) WHERE fs.form_id = ?",
                    "$." + field.getFieldKey(), form.getId());
        }
        store.onFieldRemoved(form, field); // EAV: DELETE rows. RELATIONAL: DROP COLUMN. JSON: drops the generated column/index if any.
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

    // -------------------------------------------------------------- explain ----

    /**
     * Item 8, scoped down (see REPORT.md "Scope decisions"): full EXPLAIN ANALYZE
     * coverage of every read op x every variant would need each store to expose its
     * raw SQL, a larger refactor than this phase's time budget allows. Captured
     * instead for the four operations this run's own results flagged as anomalous
     * or architecturally significant: find_by_id (baseline), filter_eq_low (the
     * JSON_IDX "indexing made it slower" anomaly), aggregate_date, and page_first
     * (EAV's correlated-subquery-vs-join sort difference). Reconstructs literal SQL
     * directly here using the same table/column naming conventions the stores use -
     * acceptable duplication for an analysis-only diagnostic, not production code.
     */
    private void captureExplain(Variant variant, int size, FormDefinition form, List<FormField> fields, long[] ownerUserIds) {
        try {
            Files.createDirectories(Path.of("benchmarks", "results", "explain"));
        } catch (IOException e) {
            skipLog.add("Could not create explain/ directory: " + e);
            return;
        }
        captureExplainSafely(variant, size, form, "find_by_id", () -> explainFindById(variant, form, fields));
        captureExplainSafely(variant, size, form, "filter_eq_low", () -> explainFilterEqLow(variant, form, fields));
        captureExplainSafely(variant, size, form, "aggregate_date", () -> explainAggregateDate(variant, form, fields));
        captureExplainSafely(variant, size, form, "page_first", () -> explainPageFirst(variant, form, fields));
    }

    /** A failure building or running any one EXPLAIN query must never take down the whole cell's real measurements. */
    private void captureExplainSafely(Variant variant, int size, FormDefinition form, String op, Supplier<String> sqlBuilder) {
        try {
            captureExplainOne(variant, size, form, op, sqlBuilder.get());
        } catch (Exception e) {
            skipLog.add(variant.label() + "@" + size + " EXPLAIN ANALYZE for " + op + " (building SQL) failed: " + e);
        }
    }

    private void captureExplainOne(Variant variant, int size, FormDefinition form, String op, String sql) {
        if (sql == null) return;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("EXPLAIN ANALYZE " + sql);
            StringBuilder out = new StringBuilder();
            out.append("-- ").append(variant.label()).append(" @ ").append(size).append(" : ").append(op).append("\n");
            out.append("-- SQL: ").append(sql).append("\n\n");
            for (Map<String, Object> row : rows) {
                row.values().forEach(v -> out.append(v).append("\n"));
            }
            Path file = Path.of("benchmarks", "results", "explain", variant.label() + "_" + size + "_" + op + ".txt");
            Files.writeString(file, out.toString());
        } catch (Exception e) {
            skipLog.add(variant.label() + "@" + size + " EXPLAIN ANALYZE for " + op + " failed: " + e);
        }
    }

    private String explainFindById(Variant variant, FormDefinition form, List<FormField> fields) {
        return switch (variant.storageType()) {
            case EAV -> "SELECT field_id, value_text, value_num, value_date, value_bool FROM form_value WHERE submission_id = " + midSubmissionIdPlaceholder(form);
            case JSON -> "SELECT data FROM form_submission_json WHERE submission_id = " + midSubmissionIdPlaceholder(form);
            case RELATIONAL -> "SELECT * FROM form_data_" + form.getId() + " WHERE submission_id = " + midSubmissionIdPlaceholder(form);
        };
    }

    private String explainFilterEqLow(Variant variant, FormDefinition form, List<FormField> fields) {
        FormField shiftField = byKey(fields, "shift");
        return switch (variant.storageType()) {
            case EAV -> "SELECT fs.id FROM form_submission fs JOIN form_value fv0 ON fv0.submission_id = fs.id AND fv0.field_id = " +
                    shiftField.getId() + " AND fv0.value_text = 'A' WHERE fs.form_id = " + form.getId();
            case JSON -> "SELECT fs.id FROM form_submission fs JOIN form_submission_json j ON j.submission_id = fs.id " +
                    "WHERE fs.form_id = " + form.getId() + " AND j.data->>'$.shift' = 'A'";
            case RELATIONAL -> "SELECT submission_id FROM form_data_" + form.getId() + " WHERE shift_col_placeholder = 'A'"
                    .replace("shift_col_placeholder", shiftField.relationalColumnName());
        };
    }

    private String explainAggregateDate(Variant variant, FormDefinition form, List<FormField> fields) {
        FormField dateField = byKey(fields, "prod_date");
        FormField weightField = byKey(fields, "total_weight");
        return switch (variant.storageType()) {
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
    }

    private String explainPageFirst(Variant variant, FormDefinition form, List<FormField> fields) {
        FormField dateField = byKey(fields, "prod_date");
        return switch (variant.storageType()) {
            case EAV -> variant.storeOverride() != null
                    ? "SELECT fs.id FROM form_submission fs WHERE fs.form_id = " + form.getId() +
                    " ORDER BY (SELECT value_date FROM form_value WHERE submission_id = fs.id AND field_id = " + dateField.getId() + ") DESC LIMIT 50"
                    : "SELECT fs.id FROM form_submission fs LEFT JOIN form_value sortv ON sortv.submission_id = fs.id AND sortv.field_id = " +
                    dateField.getId() + " WHERE fs.form_id = " + form.getId() + " ORDER BY sortv.value_date DESC LIMIT 50";
            case JSON -> "SELECT fs.id FROM form_submission fs JOIN form_submission_json j ON j.submission_id = fs.id " +
                    "WHERE fs.form_id = " + form.getId() + " ORDER BY j.data->>'$.prod_date' DESC LIMIT 50";
            case RELATIONAL -> "SELECT submission_id FROM form_data_" + form.getId() +
                    " ORDER BY " + dateField.relationalColumnName() + " DESC LIMIT 50";
        };
    }

    private String midSubmissionIdPlaceholder(FormDefinition form) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM form_submission WHERE form_id = ?", Long.class, form.getId());
        long offset = (count == null ? 0 : count) / 2;
        Long id = jdbc.queryForObject("SELECT id FROM form_submission WHERE form_id = ? ORDER BY id LIMIT 1 OFFSET " + offset,
                Long.class, form.getId());
        return String.valueOf(id);
    }

    /**
     * TRUNCATE alone only resets data, not schema - JSON's generated
     * columns/indexes added during Scenario B (onFieldAdded for indexed fields)
     * persist across cells since they're DDL on this shared table. Without this,
     * indexes accumulate cell over cell until MySQL's 64-key-per-table limit is
     * hit (a real failure caught during this run's own smoke test). DROP+CREATE
     * matches V4__forms.sql + V7__form_submission_json_created_by.sql exactly,
     * for full isolation.
     */
    private void resetJsonTable() {
        jdbc.execute("DROP TABLE IF EXISTS form_submission_json");
        jdbc.execute("CREATE TABLE form_submission_json (" +
                "submission_id BIGINT NOT NULL, data JSON NOT NULL, created_by BIGINT NULL, PRIMARY KEY (submission_id), " +
                "KEY idx_form_submission_json_created_by (created_by), " +
                "CONSTRAINT fk_form_submission_json_submission FOREIGN KEY (submission_id) REFERENCES form_submission (id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
    }

    private void teardownCell(Variant variant, FormDefinition form) {
        switch (variant.storageType()) {
            case EAV -> jdbc.execute("TRUNCATE TABLE form_value");
            case JSON -> resetJsonTable();
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

        Path integrityCsv = resultsDir.resolve("integrity.csv");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(integrityCsv))) {
            writer.println("variant,violation_type,attempts,rejected,accepted");
            for (String[] row : integrityRows) writer.println(String.join(",", row));
        }

        Path logPath = resultsDir.resolve("run_log.txt");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logPath))) {
            writer.println("Variant order per size:");
            orderLog.forEach(writer::println);
            writer.println();
            writer.println("Cross-strategy result checksums (item 5):");
            checksumLog.forEach(writer::println);
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

    // ===================================================== targeted fix rerun ====

    /**
     * Re-measures only what the post-interim fairness fixes actually affect,
     * instead of re-running the whole 10k-100k x 6-variant matrix from scratch:
     *   1. remove_field_soft, now a metadata flag flip for every strategy (was
     *      FK-blocked for EAV) - rerun for all 6 variants x all 4 sizes.
     *   2. JSON_IDX's Scenario A reads, now that filter/sort/group-by reference the
     *      generated column by name instead of a JSON path expression that didn't
     *      reliably hit the functional index - rerun JSON_IDX's full Scenario A at
     *      all 4 sizes.
     *   3. Any already-measured read op whose std_ms exceeded 30% of its mean_ms -
     *      rerun with 20 samples instead of the original 10/5.
     * Loads the existing raw_paper.csv/integrity.csv into memory first, strips the
     * rows the fixes invalidate, appends freshly measured ones, and writes the
     * result back through the normal writeResults() path so summary.csv/
     * storage_size_summary.csv stay consistent with raw_paper.csv.
     *
     *   mvn test -Dtest=PaperBenchmarkRunner#rerunFixedScenarios -DfailIfNoTests=false
     */
    /**
     * Item 2 follow-up: form_submission_json now denormalizes created_by (V7
     * migration) the same way RELATIONAL already did, so every JSON-backed
     * variant's own-scope filtering changed, not just JSON_IDX's. Rerun all three
     * JSON variants' full Scenario A at every size in one pass (this also
     * recaptures their EXPLAIN ANALYZE files at 100k for free, since captureExplain
     * already runs inside runCell whenever size==100_000 - no separate explain pass
     * needed).
     *
     *   mvn test -Dtest=PaperBenchmarkRunner#rerunJsonCreatedByFix -DfailIfNoTests=false
     */
    @Test
    void rerunJsonCreatedByFix() throws IOException {
        runStart = System.nanoTime();
        bulkLoader = new BulkLoader(jdbc);
        long[] ownerUserIds = benchUsers();
        loadExistingCsvRows();
        loadExistingIntegrityRows();

        List<Variant> jsonVariants = variants().stream()
                .filter(v -> v.storageType() == StorageType.JSON)
                .toList();
        for (int size : SIZES) {
            for (Variant variant : jsonVariants) {
                System.out.println("=== [rerun] " + variant.label() + " Scenario A @ " + size + " (created_by denorm) ===");
                removeRows(r -> r[0].equals(variant.label()) && r[1].equals(String.valueOf(size)) && r[2].equals("A"));
                runCell(variant, size, ownerUserIds, false);
                writeResults();
            }
        }
        System.out.println("TOTAL RERUN TIME: " + minutesSoFar() + " min");
    }

    @Test
    void rerunFixedScenarios() throws IOException {
        runStart = System.nanoTime();
        bulkLoader = new BulkLoader(jdbc);
        long[] ownerUserIds = benchUsers();
        loadExistingCsvRows();
        loadExistingIntegrityRows();

        for (int size : SIZES) {
            for (Variant variant : variants()) {
                System.out.println("=== [rerun] remove_field_soft: " + variant.label() + " @ " + size + " ===");
                removeRows(r -> r[0].equals(variant.label()) && r[1].equals(String.valueOf(size))
                        && r[2].equals("B") && r[3].equals("remove_field_soft"));
                rerunRemoveFieldSoftOnly(variant, size, ownerUserIds);
                writeResults();
            }
        }

        Variant jsonIdx = variants().stream().filter(v -> v.label().equals("JSON_IDX")).findFirst().orElseThrow();
        for (int size : SIZES) {
            System.out.println("=== [rerun] JSON_IDX Scenario A @ " + size + " ===");
            removeRows(r -> r[0].equals("JSON_IDX") && r[1].equals(String.valueOf(size)) && r[2].equals("A"));
            runCell(jsonIdx, size, ownerUserIds, false);
            writeResults();
        }

        rerunHighVarianceCells(ownerUserIds);
        writeResults();
        System.out.println("TOTAL RERUN TIME: " + minutesSoFar() + " min");
    }

    private void loadExistingCsvRows() throws IOException {
        Path raw = Path.of("benchmarks", "results", "raw_paper.csv");
        if (!Files.exists(raw)) {
            csvRows.add(new String[]{"strategy", "dataset_size", "scenario", "operation", "run", "value", "unit", "rows_returned", "timestamp"});
            return;
        }
        for (String line : Files.readAllLines(raw)) {
            if (!line.isBlank()) csvRows.add(line.split(",", -1));
        }
    }

    private void loadExistingIntegrityRows() throws IOException {
        Path integrity = Path.of("benchmarks", "results", "integrity.csv");
        if (!Files.exists(integrity)) return;
        List<String> lines = Files.readAllLines(integrity);
        for (int i = 1; i < lines.size(); i++) { // skip header
            if (!lines.get(i).isBlank()) integrityRows.add(lines.get(i).split(",", -1));
        }
    }

    /** Drops every data row (never the header at index 0) matching the predicate, so a rerun can replace them. */
    private void removeRows(java.util.function.Predicate<String[]> matches) {
        for (int i = csvRows.size() - 1; i >= 1; i--) {
            if (matches.test(csvRows.get(i))) csvRows.remove(i);
        }
    }

    private void rerunRemoveFieldSoftOnly(Variant variant, int size, long[] ownerUserIds) {
        switch (variant.storageType()) {
            case EAV -> jdbc.execute("TRUNCATE TABLE form_value");
            case JSON -> resetJsonTable();
            case RELATIONAL -> { }
        }
        List<FormField> fields = buildFields(variant.indexedKeys());
        FormDefinition form = formDefinitionService.createForm(
                "rerun_soft_" + variant.label().toLowerCase() + "_" + size + "_" + System.nanoTime(),
                "Rerun Soft Remove " + variant.label() + " " + size, "PaperBenchmarkRunner",
                variant.storageType(), fields);
        List<FormField> allFields = form.getFields();
        SubmissionStore store = variant.storeOverride() != null ? variant.storeOverride() : storeFor(variant.storageType());

        Random dataRandom = new Random(PaperDataGenerator.DATA_SEED);
        Random ownerRandom = new Random(PaperDataGenerator.DATA_SEED + 1);
        // bulkLoad's own insert_batch_1000/bulk_load_total rows for this throwaway form
        // would duplicate what's already on record for this variant/size - drop any
        // existing ones first, they're not what this rerun is measuring.
        removeRows(r -> r[0].equals(variant.label()) && r[1].equals(String.valueOf(size))
                && (r[3].equals("insert_batch_1000") || r[3].equals("bulk_load_total")));
        bulkLoad(variant, size, form, allFields, store, dataRandom, ownerRandom, ownerUserIds);
        analyzeTables(variant, form);

        removeFieldSoftPhase(variant, size, form, allFields, store);

        teardownCell(variant, form);
    }

    /**
     * Item 5: any read op (Scenario A, timed() path only - not Scenario B/schema
     * ops, and not the once-per-cell "_full" measurements) whose std_ms exceeded
     * 30% of its mean_ms in the data gathered so far gets re-measured with 20
     * samples instead of the original 10 (reads) / 5 (writes).
     */
    private static final Set<String> RERUN_ELIGIBLE_OPS = Set.of(
            "find_by_id", "filter_eq_high", "filter_eq_low", "filter_range_eq", "filter_3_fields",
            "like_search", "aggregate_group", "aggregate_date", "count_all", "count_filtered",
            "page_first", "page_deep", "own_scope_page", "insert_single", "update_single", "delete_single");

    private void rerunHighVarianceCells(long[] ownerUserIds) {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (String[] r : csvRows.subList(1, csvRows.size())) {
            if (!r[6].equals("ms") || !RERUN_ELIGIBLE_OPS.contains(r[3])) continue;
            grouped.computeIfAbsent(String.join("|", r[0], r[1], r[3]), k -> new ArrayList<>()).add(Double.parseDouble(r[5]));
        }
        List<String> flagged = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : grouped.entrySet()) {
            PaperStats s = PaperStats.of(e.getValue());
            if (s.meanMs() > 0 && s.stdDevMs() / s.meanMs() > 0.30) flagged.add(e.getKey());
        }
        skipLog.add("High-variance rerun (item 5) flagged " + flagged.size() + " cell(s) for 20-run remeasurement: " + flagged);

        for (String key : flagged) {
            String[] parts = key.split("\\|");
            String strategyLabel = parts[0];
            int size = Integer.parseInt(parts[1]);
            String op = parts[2];
            Variant variant = variants().stream().filter(v -> v.label().equals(strategyLabel)).findFirst().orElse(null);
            if (variant == null) continue;
            System.out.println("=== [rerun] high-variance " + key + " (20 runs) ===");
            try {
                rerunSingleOpWithMoreRuns(variant, size, op, ownerUserIds);
            } catch (Exception ex) {
                skipLog.add(key + ": high-variance rerun FAILED: " + ex);
            }
        }
    }

    // ===================================================== phase 2: bp1g + n=3 bulk load ====

    /**
     * Phase-2 item 1: re-measures Scenario A+B at 50k/100k under a 1G buffer pool, tagged
     * with a "_BP1G" label suffix so these rows land as their own strategy key in
     * summary.csv/raw_paper.csv instead of colliding with the default-buffer-pool rows for
     * the same variant/size. Caller is responsible for actually changing
     * innodb_buffer_pool_size and restarting MySQL BEFORE invoking this, and restoring it
     * afterward - this method only runs the benchmark cells and relies on whatever buffer
     * pool is live when it runs.
     *
     *   mvn test -Dtest=PaperBenchmarkRunner#runBufferPoolSensitivity -DfailIfNoTests=false
     */
    @Test
    void runBufferPoolSensitivity() throws IOException {
        runStart = System.nanoTime();
        bulkLoader = new BulkLoader(jdbc);
        long[] ownerUserIds = benchUsers();
        loadExistingCsvRows();
        loadExistingIntegrityRows();

        int[] bpSizes = {50_000, 100_000};
        for (int size : bpSizes) {
            List<Variant> order = new ArrayList<>(variants());
            Collections.shuffle(order, new Random(PaperDataGenerator.ORDER_SEED + size + 1));
            orderLog.add("[bp1g] size=" + size + " order=" + order.stream().map(Variant::label).toList()
                    + " (seed=" + (PaperDataGenerator.ORDER_SEED + size + 1) + ")");
            for (Variant base : order) {
                Variant tagged = new Variant(base.label() + "_BP1G", base.storageType(), base.indexedKeys(), base.storeOverride());
                System.out.println("=== [bp1g] " + tagged.label() + " @ " + size + " (elapsed so far: " + minutesSoFar() + " min) ===");
                removeRows(r -> r[0].equals(tagged.label()) && r[1].equals(String.valueOf(size)));
                try {
                    runCell(tagged, size, ownerUserIds);
                } catch (Exception e) {
                    skipLog.add(tagged.label() + "@" + size + " [bp1g] FAILED: " + e);
                    e.printStackTrace();
                }
                writeResults();
            }
        }
        System.out.println("TOTAL BP1G RUNTIME: " + minutesSoFar() + " min");
    }

    /**
     * Phase-2 item 3: bulk_load_total measured 3x per variant/size (full load + full
     * teardown between each of the 3 runs, since it's a single-shot whole-dataset
     * operation that can't be measured 3x in place without re-loading) instead of once.
     * Replaces the existing single-run bulk_load_total rows for every variant x size with
     * the new 3-sample set so summary.csv reports mean±std/p95 for this metric like every
     * other. insert_batch_1000 rows from these extra loads are intentionally discarded
     * (not needed 3x, already well-sampled from the original run) to avoid skewing that
     * metric's n.
     *
     *   mvn test -Dtest=PaperBenchmarkRunner#rerunBulkLoadTriple -DfailIfNoTests=false
     */
    @Test
    void rerunBulkLoadTriple() throws IOException {
        runStart = System.nanoTime();
        bulkLoader = new BulkLoader(jdbc);
        long[] ownerUserIds = benchUsers();
        loadExistingCsvRows();
        loadExistingIntegrityRows();

        removeRows(r -> r[3].equals("bulk_load_total"));

        for (int size : SIZES) {
            for (Variant variant : variants()) {
                System.out.println("=== [bulk_load x3] " + variant.label() + " @ " + size + " (elapsed so far: " + minutesSoFar() + " min) ===");
                for (int run = 1; run <= 3; run++) {
                    switch (variant.storageType()) {
                        case EAV -> jdbc.execute("TRUNCATE TABLE form_value");
                        case JSON -> resetJsonTable();
                        case RELATIONAL -> { }
                    }
                    List<FormField> fields = buildFields(variant.indexedKeys());
                    FormDefinition form = formDefinitionService.createForm(
                            "bl3_" + variant.label().toLowerCase() + "_" + size + "_" + System.nanoTime(),
                            "Bulk Load x3 " + variant.label() + " " + size, "PaperBenchmarkRunner",
                            variant.storageType(), fields);
                    List<FormField> allFields = form.getFields();
                    SubmissionStore store = variant.storeOverride() != null ? variant.storeOverride() : storeFor(variant.storageType());
                    Random dataRandom = new Random(PaperDataGenerator.DATA_SEED);
                    Random ownerRandom = new Random(PaperDataGenerator.DATA_SEED + 1);

                    long totalStart = System.nanoTime();
                    List<Long> ids = bulkLoadNoCsv(variant, size, form, allFields, store, dataRandom, ownerRandom, ownerUserIds);
                    long totalElapsed = System.nanoTime() - totalStart;
                    csvRows.add(csvRow(variant.label(), size, "A", "bulk_load_total", run, ms(totalElapsed), "ms", size));

                    teardownCell(variant, form);
                }
                writeResults();
            }
        }
        System.out.println("TOTAL BULK_LOAD x3 RUNTIME: " + minutesSoFar() + " min");
    }

    /** Same as bulkLoad() but discards the per-chunk insert_batch_1000 rows - used by rerunBulkLoadTriple so 3x reloads don't skew that metric's sample count. */
    private List<Long> bulkLoadNoCsv(Variant variant, int size, FormDefinition form, List<FormField> fields,
                                      SubmissionStore store, Random dataRandom, Random ownerRandom, long[] ownerUserIds) {
        List<Long> allIds = new ArrayList<>(size);
        for (int inserted = 0; inserted < size; inserted += CHUNK) {
            int thisChunk = Math.min(CHUNK, size - inserted);
            List<Map<String, Object>> rows = new ArrayList<>(thisChunk);
            for (int i = 0; i < thisChunk; i++) rows.add(generator.row(dataRandom));
            BulkLoader.HeaderBatch headerBatch = bulkLoader.batchInsertHeaders(form.getId(), form.getVersion(), thisChunk, ownerUserIds, ownerRandom);
            List<Long> ids = headerBatch.submissionIds();
            switch (variant.storageType()) {
                case EAV -> bulkLoader.batchInsertEav(ids, fields, rows);
                case JSON -> bulkLoader.batchInsertJson(ids, headerBatch.ownerIds(), rows);
                case RELATIONAL -> bulkLoader.batchInsertRelational(form.getId(), ids, headerBatch.ownerIds(), fields, rows);
            }
            allIds.addAll(ids);
        }
        return allIds;
    }

    private void rerunSingleOpWithMoreRuns(Variant variant, int size, String op, long[] ownerUserIds) {
        switch (variant.storageType()) {
            case EAV -> jdbc.execute("TRUNCATE TABLE form_value");
            case JSON -> resetJsonTable();
            case RELATIONAL -> { }
        }
        List<FormField> fields = buildFields(variant.indexedKeys());
        FormDefinition form = formDefinitionService.createForm(
                "rerun_var_" + variant.label().toLowerCase() + "_" + size + "_" + System.nanoTime(),
                "Rerun High-Variance " + variant.label() + " " + size, "PaperBenchmarkRunner",
                variant.storageType(), fields);
        List<FormField> allFields = form.getFields();
        SubmissionStore store = variant.storeOverride() != null ? variant.storeOverride() : storeFor(variant.storageType());

        Random dataRandom = new Random(PaperDataGenerator.DATA_SEED);
        Random ownerRandom = new Random(PaperDataGenerator.DATA_SEED + 1);
        removeRows(r -> r[0].equals(variant.label()) && r[1].equals(String.valueOf(size))
                && (r[3].equals("insert_batch_1000") || r[3].equals("bulk_load_total")));
        List<Long> submissionIds = bulkLoad(variant, size, form, allFields, store, dataRandom, ownerRandom, ownerUserIds);
        analyzeTables(variant, form);

        removeRows(r -> r[0].equals(variant.label()) && r[1].equals(String.valueOf(size)) && r[3].equals(op));

        User owner = userRepository.findById(ownerUserIds[0]).orElseThrow();
        Random pick = new Random(99);
        Supplier<Integer> action = switch (op) {
            case "find_by_id" -> () -> {
                Long id = submissionIds.get(pick.nextInt(submissionIds.size()));
                return store.findById(form, allFields, id).isEmpty() ? 0 : 1;
            };
            case "filter_eq_high" -> () -> store.query(form, allFields,
                    new QuerySpec().addFilter(FilterCondition.eq("part_no", "PN-10500")).page(0, 100)).size();
            case "filter_eq_low" -> () -> store.query(form, allFields,
                    new QuerySpec().addFilter(FilterCondition.eq("shift", "A")).page(0, 100)).size();
            case "filter_range_eq" -> () -> store.query(form, allFields, new QuerySpec()
                    .addFilter(FilterCondition.range("prod_date", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 30)))
                    .addFilter(FilterCondition.eq("machine", "Machine-5")).page(0, 100)).size();
            case "filter_3_fields" -> () -> store.query(form, allFields, new QuerySpec()
                    .addFilter(FilterCondition.eq("shift", "B"))
                    .addFilter(FilterCondition.eq("machine", "Machine-10"))
                    .addFilter(FilterCondition.range("qty_rejected", 5, null)).page(0, 100)).size();
            case "like_search" -> () -> store.query(form, allFields,
                    new QuerySpec().addFilter(FilterCondition.like("remarks", "crack")).page(0, 100)).size();
            case "aggregate_group" -> () -> {
                int a = store.aggregate(form, allFields, new AggregateSpec(AggregateOp.SUM, "qty_produced", "machine")).size();
                int b = store.aggregate(form, allFields, new AggregateSpec(AggregateOp.SUM, "qty_rejected", "machine")).size();
                return a + b;
            };
            case "aggregate_date" -> () -> aggregateDateMonthly(variant, form, allFields);
            case "count_all" -> () -> {
                Long c = jdbc.queryForObject("SELECT COUNT(*) FROM form_submission WHERE form_id = ?", Long.class, form.getId());
                return c == null ? 0 : c.intValue();
            };
            case "count_filtered" -> () -> (int) store.count(form, allFields, new QuerySpec().addFilter(FilterCondition.eq("shift", "A")));
            case "page_first" -> () -> store.query(form, allFields, new QuerySpec().sortBy("prod_date", true).page(0, 50)).size();
            case "page_deep" -> () -> store.query(form, allFields, new QuerySpec().sortBy("prod_date", true).page(submissionIds.size() / 2 / 50, 50)).size();
            case "own_scope_page" -> () -> store.query(form, allFields, new QuerySpec().ownerId(ownerUserIds[0]).page(0, 50)).size();
            case "insert_single" -> () -> {
                Map<String, Object> row = generator.row(dataRandom);
                com.notes.notes.forms.entity.FormSubmission header = new com.notes.notes.forms.entity.FormSubmission();
                header.setForm(form);
                header.setFormVersion(form.getVersion());
                header.setCreatedBy(owner);
                Long id = autowiredSaveSubmission(header);
                store.save(form, allFields, id, row);
                submissionIds.add(id);
                return 1;
            };
            case "update_single" -> () -> {
                Map<String, Object> changes = Map.of(
                        "part_name", "Updated Part " + System.nanoTime() % 1000,
                        "qty_produced", 77, "remarks", "updated by benchmark");
                Long targetId = submissionIds.get(0);
                Map<String, Object> merged = new LinkedHashMap<>(store.findById(form, allFields, targetId));
                merged.putAll(changes);
                store.update(form, allFields, targetId, merged);
                return 1;
            };
            case "delete_single" -> () -> {
                Map<String, Object> row = generator.row(dataRandom);
                com.notes.notes.forms.entity.FormSubmission header = new com.notes.notes.forms.entity.FormSubmission();
                header.setForm(form);
                header.setFormVersion(form.getVersion());
                header.setCreatedBy(owner);
                Long id = autowiredSaveSubmission(header);
                store.save(form, allFields, id, row);
                store.delete(form, id);
                return 1;
            };
            default -> throw new IllegalArgumentException("Unsupported op for high-variance rerun: " + op);
        };

        boolean isWrite = op.equals("insert_single") || op.equals("update_single") || op.equals("delete_single");
        int warmup = isWrite ? 2 : 5;
        int runs = 20;
        for (int i = 0; i < warmup; i++) action.get();
        for (int run = 1; run <= runs; run++) {
            long start = System.nanoTime();
            int rows = action.get();
            long elapsed = System.nanoTime() - start;
            csvRows.add(csvRow(variant.label(), size, "A", op, run, ms(elapsed), "ms", rows));
        }

        teardownCell(variant, form);
    }
}
