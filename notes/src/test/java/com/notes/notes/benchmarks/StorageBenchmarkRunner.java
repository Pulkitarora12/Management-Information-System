package com.notes.notes.benchmarks;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.forms.entity.*;
import com.notes.notes.forms.repository.FormFieldRepository;
import com.notes.notes.forms.repository.FormSubmissionRepository;
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
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Phase 3: the storage benchmark harness from CLAUDE_CODE_PROMPT.md. NOT part of
 * the normal build (excluded from the default surefire run in pom.xml - see the
 * <excludes> block) because it writes a real, possibly large dataset and takes
 * minutes to run. Invoke explicitly:
 *
 *   mvn test -Dtest=StorageBenchmarkRunner -DfailIfNoTests=false
 *
 * Always runs against mis_bench (spring.profiles.active=bench), never mis or
 * mis_test. Every number in benchmarks/results/ comes from this actually running -
 * nothing here is estimated.
 */
@SpringBootTest
@ActiveProfiles("bench")
class StorageBenchmarkRunner {

    // Scaled down from the 10k/100k/1M in the original plan to fit one session's
    // time budget; see REPORT.md "Scope note" for why, and how to extend this list.
    private static final int[] DATASET_SIZES = {1_000, 10_000};
    private static final int WARMUP_RUNS = 1;
    private static final int TIMED_RUNS = 5;
    private static final int BATCH_SIZE = 1000;

    @Autowired private FormDefinitionService formDefinitionService;
    @Autowired private FormFieldRepository formFieldRepository;
    @Autowired private FormSubmissionRepository formSubmissionRepository;
    @Autowired private EavSubmissionStore eavSubmissionStore;
    @Autowired private JsonSubmissionStore jsonSubmissionStore;
    @Autowired private RelationalSubmissionStore relationalSubmissionStore;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private final List<String[]> csvRows = new ArrayList<>();
    private final BenchmarkDataGenerator generator = new BenchmarkDataGenerator();

    @Test
    void runBenchmark() throws IOException {
        User benchUser = benchUser();
        csvRows.add(new String[]{"strategy", "dataset_size", "operation", "run", "latency_ms", "rows_affected"});

        List<Variant> variants = List.of(
                new Variant("EAV", StorageType.EAV, eavSubmissionStore, false),
                new Variant("JSON_NO_INDEX", StorageType.JSON, jsonSubmissionStore, false),
                new Variant("JSON_WITH_INDEX", StorageType.JSON, jsonSubmissionStore, true),
                new Variant("RELATIONAL", StorageType.RELATIONAL, relationalSubmissionStore, false)
        );

        for (int size : DATASET_SIZES) {
            for (Variant variant : variants) {
                System.out.println("=== " + variant.label + " @ " + size + " rows ===");
                runOneVariant(variant, size, benchUser);
            }
        }

        writeResults();
    }

    private void runOneVariant(Variant variant, int size, User benchUser) {
        List<FormField> fields = buildFields(variant.indexed);
        FormDefinition form = formDefinitionService.createForm(
                "bench_" + variant.label.toLowerCase() + "_" + size + "_" + System.nanoTime(),
                "Benchmark Form " + variant.label + " " + size,
                "StorageBenchmarkRunner", variant.storageType, fields);
        List<FormField> allFields = form.getFields();

        List<Long> submissionIds = loadData(form, allFields, variant, size, benchUser);

        timed(variant.label, size, "find_by_id", 1, () -> {
            Long id = submissionIds.get(submissionIds.size() / 2);
            Map<String, Object> result = variant.store.findById(form, allFields, id);
            return result.isEmpty() ? 0 : 1;
        });

        timed(variant.label, size, "filter_eq_1field", 1, () -> {
            QuerySpec spec = new QuerySpec().addFilter(FilterCondition.eq("shift", "A")).page(0, 50);
            return variant.store.query(form, allFields, spec).size();
        });

        timed(variant.label, size, "filter_range_plus_eq", 1, () -> {
            QuerySpec spec = new QuerySpec()
                    .addFilter(FilterCondition.range("quantity", 100, 300))
                    .addFilter(FilterCondition.eq("shift", "B"))
                    .page(0, 50);
            return variant.store.query(form, allFields, spec).size();
        });

        timed(variant.label, size, "like_search", 1, () -> {
            QuerySpec spec = new QuerySpec().addFilter(FilterCondition.like("part_name", "Bracket Assembly 1")).page(0, 50);
            return variant.store.query(form, allFields, spec).size();
        });

        timed(variant.label, size, "sum_groupby", 1, () ->
                variant.store.aggregate(form, allFields, new AggregateSpec(AggregateOp.SUM, "total_value", "shift")).size());

        timed(variant.label, size, "count_all", 1, () ->
                (int) variant.store.count(form, allFields, new QuerySpec()));

        timed(variant.label, size, "paginated_history_page", 1, () -> {
            QuerySpec spec = new QuerySpec().sortBy("due_date", true).page(3, 20);
            return variant.store.query(form, allFields, spec).size();
        });

        // One-time schema-change cost, measured once (not a distribution) per CLAUDE_CODE_PROMPT.
        FormField newField = new FormField();
        newField.setFieldKey("inspector_notes");
        newField.setLabel("Inspector Notes");
        newField.setType(FieldType.TEXT);
        newField.setSortOrder(allFields.size());
        newField.setForm(form);
        long start = System.nanoTime();
        FormField persistedField = formFieldRepository.save(newField);
        variant.store.onFieldAdded(form, persistedField);
        long elapsed = System.nanoTime() - start;
        csvRows.add(row(variant.label, size, "add_field_on_populated_form", 1, elapsed, size));

        long storageBytes = variant.store.storageSizeBytes(form);
        csvRows.add(row(variant.label, size, "storage_size_bytes", 1, storageBytes, size));
    }

    private List<Long> loadData(FormDefinition form, List<FormField> fields, Variant variant, int size, User benchUser) {
        Random random = new Random(BenchmarkDataGenerator.SEED);
        List<Long> submissionIds = new ArrayList<>(size);

        // single insert (first row, measured alone)
        Map<String, Object> firstRow = generator.row(random, 0);
        long t0 = System.nanoTime();
        Long firstId = insertOne(form, fields, benchUser, firstRow, variant.store);
        long t1 = System.nanoTime();
        csvRows.add(row(variant.label, size, "single_insert", 1, t1 - t0, 1));
        submissionIds.add(firstId);

        // remaining rows, timed per batch of BATCH_SIZE
        int remaining = size - 1;
        int batchRun = 1;
        for (int inserted = 0; inserted < remaining; ) {
            int thisBatch = Math.min(BATCH_SIZE, remaining - inserted);
            long bStart = System.nanoTime();
            for (int i = 0; i < thisBatch; i++) {
                Map<String, Object> values = generator.row(random, inserted + i + 1);
                submissionIds.add(insertOne(form, fields, benchUser, values, variant.store));
            }
            long bElapsed = System.nanoTime() - bStart;
            csvRows.add(row(variant.label, size, "batch_insert_1000", batchRun++, bElapsed, thisBatch));
            inserted += thisBatch;
        }
        return submissionIds;
    }

    private Long insertOne(FormDefinition form, List<FormField> fields, User owner, Map<String, Object> values, SubmissionStore store) {
        FormSubmission header = new FormSubmission();
        header.setForm(form);
        header.setFormVersion(form.getVersion());
        header.setCreatedBy(owner);
        Long id = formSubmissionRepository.save(header).getId();
        store.save(form, fields, id, values);
        return id;
    }

    private void timed(String strategy, int size, String operation, int baseRun, java.util.function.Supplier<Integer> op) {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            op.get();
        }
        for (int run = 1; run <= TIMED_RUNS; run++) {
            long start = System.nanoTime();
            int rowsAffected = op.get();
            long elapsed = System.nanoTime() - start;
            csvRows.add(row(strategy, size, operation, run, elapsed, rowsAffected));
        }
    }

    private String[] row(String strategy, int size, String operation, int run, long elapsedNanos, long rowsAffected) {
        double ms = elapsedNanos / 1_000_000.0;
        return new String[]{strategy, String.valueOf(size), operation, String.valueOf(run),
                String.format(Locale.ROOT, "%.3f", ms), String.valueOf(rowsAffected)};
    }

    private List<FormField> buildFields(boolean indexed) {
        List<FormField> fields = new ArrayList<>();
        fields.add(field("part_no", FieldType.TEXT, 0, false));
        fields.add(field("part_name", FieldType.TEXT, 1, false));
        fields.add(field("quantity", FieldType.NUMBER, 2, indexed));
        fields.add(field("weight", FieldType.DECIMAL, 3, false));
        fields.add(field("due_date", FieldType.DATE, 4, indexed));
        fields.add(field("is_rework", FieldType.BOOLEAN, 5, false));
        fields.add(field("shift", FieldType.DROPDOWN, 6, indexed));
        fields.add(field("line", FieldType.DROPDOWN, 7, false));
        fields.add(field("operator", FieldType.TEXT, 8, false));
        fields.add(field("remarks", FieldType.TEXT, 9, false));
        fields.add(field("unit_price", FieldType.DECIMAL, 10, false));
        fields.add(field("total_value", FieldType.CALCULATED, 11, false));
        return fields;
    }

    private FormField field(String key, FieldType type, int order, boolean indexed) {
        FormField f = new FormField();
        f.setFieldKey(key);
        f.setLabel(key);
        f.setType(type);
        f.setSortOrder(order);
        if (indexed) {
            f.setConfig("{\"indexed\":true}");
        }
        return f;
    }

    private User benchUser() {
        String username = "benchuser";
        return userRepository.findByUserName(username).orElseGet(() -> {
            Role legacyUserRole = roleRepository.findByRoleName(AppRole.ROLE_USER)
                    .orElseThrow(() -> new IllegalStateException("Legacy ROLE_USER not seeded"));
            User user = new User();
            user.setUserName(username);
            user.setEmail(username + "@example.com");
            user.setPassword("bench-password-hash");
            user.setEnabled(true);
            user.setVerified(true);
            user.setAccountNonLocked(true);
            user.setAccountNonExpired(true);
            user.setCredentialsNonExpired(true);
            user.setRole(legacyUserRole);
            return userRepository.save(user);
        });
    }

    private void writeResults() throws IOException {
        Path resultsDir = Path.of("benchmarks", "results");
        Files.createDirectories(resultsDir);
        String timestamp = LocalDate.now().format(DateTimeFormatter.ISO_DATE) + "_" + System.currentTimeMillis();

        Path rawCsv = resultsDir.resolve("storage_" + timestamp + ".csv");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(rawCsv))) {
            for (String[] row : csvRows) {
                writer.println(String.join(",", row));
            }
        }
        System.out.println("Raw results written to " + rawCsv.toAbsolutePath());

        writeSummary(resultsDir);
    }

    private void writeSummary(Path resultsDir) throws IOException {
        // group latency samples by (strategy, size, operation) from runs 1..TIMED_RUNS of `timed()`
        Map<String, List<Long>> grouped = new LinkedHashMap<>();
        for (String[] r : csvRows.subList(1, csvRows.size())) {
            String key = r[0] + "|" + r[1] + "|" + r[2];
            double ms = Double.parseDouble(r[4]);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add((long) (ms * 1_000_000));
        }

        Path summaryCsv = resultsDir.resolve("summary.csv");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(summaryCsv))) {
            writer.println("strategy,dataset_size,operation,samples,mean_ms,stddev_ms,p50_ms,p95_ms");
            for (Map.Entry<String, List<Long>> entry : grouped.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                long[] samples = entry.getValue().stream().mapToLong(Long::longValue).toArray();
                LatencyStats stats = LatencyStats.of(samples);
                writer.println(String.join(",", parts[0], parts[1], parts[2], String.valueOf(samples.length),
                        fmt(stats.meanMs()), fmt(stats.stdDevMs()), fmt(stats.p50Ms()), fmt(stats.p95Ms())));
            }
        }
        System.out.println("Summary written to " + summaryCsv.toAbsolutePath());
    }

    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private record Variant(String label, StorageType storageType, SubmissionStore store, boolean indexed) {
    }
}
