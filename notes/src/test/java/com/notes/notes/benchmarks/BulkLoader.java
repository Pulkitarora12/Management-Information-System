package com.notes.notes.benchmarks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notes.notes.forms.entity.FieldType;
import com.notes.notes.forms.entity.FormDefinition;
import com.notes.notes.forms.entity.FormField;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real multi-row JDBC batching (BENCHMARK_FINAL_PROMPT.md section 1.1) for loading
 * the benchmark dataset itself - NOT how the app inserts data in production
 * (that still goes through SubmissionStore.save(), which is what insert_single
 * measures). This exists purely so loading 100k+ rows doesn't dominate the whole
 * run with per-row round trips the real harness isn't trying to measure.
 */
class BulkLoader {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    BulkLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record HeaderBatch(List<Long> submissionIds, List<Long> ownerIds) {
    }

    /** Batch-inserts `count` form_submission header rows; returns generated ids and the owner used for each, in order. */
    HeaderBatch batchInsertHeaders(long formId, int formVersion, int count, long[] ownerUserIds, java.util.Random random) {
        Long maxIdBefore = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM form_submission", Long.class);
        List<Object[]> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long owner = ownerUserIds[random.nextInt(ownerUserIds.length)];
            args.add(new Object[]{formId, formVersion, owner});
        }
        jdbc.batchUpdate("INSERT INTO form_submission (form_id, form_version, created_by) VALUES (?, ?, ?)", args);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, created_by FROM form_submission WHERE form_id = ? AND id > ? ORDER BY id",
                formId, maxIdBefore);
        List<Long> ids = new ArrayList<>(rows.size());
        List<Long> owners = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ids.add(((Number) row.get("id")).longValue());
            owners.add(((Number) row.get("created_by")).longValue());
        }
        return new HeaderBatch(ids, owners);
    }

    void batchInsertEav(List<Long> submissionIds, List<FormField> fields, List<Map<String, Object>> rows) {
        List<Object[]> args = new ArrayList<>(submissionIds.size() * fields.size());
        for (int i = 0; i < submissionIds.size(); i++) {
            Long submissionId = submissionIds.get(i);
            Map<String, Object> values = rows.get(i);
            for (FormField field : fields) {
                Object value = values.get(field.getFieldKey());
                if (value == null) continue;
                String text = null;
                BigDecimal num = null;
                Date date = null;
                Integer bool = null;
                switch (field.getType()) {
                    case TEXT, DROPDOWN, MASTER_REF -> text = String.valueOf(value);
                    case NUMBER, DECIMAL, CALCULATED -> num = toBigDecimal(value);
                    case DATE -> date = Date.valueOf((LocalDate) value);
                    case BOOLEAN -> bool = ((Boolean) value) ? 1 : 0;
                    case FILE -> text = String.valueOf(value);
                }
                args.add(new Object[]{submissionId, field.getId(), text, num, date, bool});
            }
        }
        jdbc.batchUpdate("INSERT INTO form_value (submission_id, field_id, value_text, value_num, value_date, value_bool) VALUES (?, ?, ?, ?, ?, ?)", args);
    }

    void batchInsertJson(List<Long> submissionIds, List<Long> ownerIds, List<Map<String, Object>> rows) {
        List<Object[]> args = new ArrayList<>(submissionIds.size());
        for (int i = 0; i < submissionIds.size(); i++) {
            args.add(new Object[]{submissionIds.get(i), toJson(rows.get(i)), ownerIds.get(i)});
        }
        jdbc.batchUpdate("INSERT INTO form_submission_json (submission_id, data, created_by) VALUES (?, CAST(? AS JSON), ?)", args);
    }

    void batchInsertRelational(long formId, List<Long> submissionIds, List<Long> ownerIds, List<FormField> fields, List<Map<String, Object>> rows) {
        String table = "form_data_" + formId;
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (submission_id, created_by");
        for (FormField field : fields) {
            sql.append(", ").append(field.relationalColumnName());
        }
        sql.append(") VALUES (?, ?");
        sql.append(", ?".repeat(fields.size()));
        sql.append(")");

        List<Object[]> args = new ArrayList<>(submissionIds.size());
        for (int i = 0; i < submissionIds.size(); i++) {
            Map<String, Object> values = rows.get(i);
            Object[] rowArgs = new Object[fields.size() + 2];
            rowArgs[0] = submissionIds.get(i);
            rowArgs[1] = ownerIds.get(i);
            for (int f = 0; f < fields.size(); f++) {
                rowArgs[f + 2] = convert(values.get(fields.get(f).getFieldKey()), fields.get(f).getType());
            }
            args.add(rowArgs);
        }
        jdbc.batchUpdate(sql.toString(), args);
    }

    private Object convert(Object value, FieldType type) {
        if (value == null) return null;
        return switch (type) {
            case NUMBER -> value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
            case DECIMAL, CALCULATED -> toBigDecimal(value);
            case DATE -> Date.valueOf((LocalDate) value);
            case BOOLEAN -> ((Boolean) value) ? 1 : 0;
            default -> value;
        };
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(String.valueOf(value));
    }

    private String toJson(Map<String, Object> values) {
        Map<String, Object> normalized = new java.util.LinkedHashMap<>();
        values.forEach((k, v) -> normalized.put(k, v instanceof LocalDate ld ? ld.toString() : v));
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
