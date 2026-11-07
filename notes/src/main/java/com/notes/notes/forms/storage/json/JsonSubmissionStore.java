package com.notes.notes.forms.storage.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notes.notes.forms.entity.FieldType;
import com.notes.notes.forms.entity.FormDefinition;
import com.notes.notes.forms.entity.FormField;
import com.notes.notes.forms.storage.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One JSON document per submission in form_submission_json.data, keyed by field_key.
 * Fields flagged "indexed":true in their config get a generated column + index added
 * at runtime (onFieldAdded) so filtering on them doesn't require a full JSON scan -
 * that's the "measured twice, with and without the index" variant from the benchmark
 * plan (Phase 3).
 */
@Component
@RequiredArgsConstructor
public class JsonSubmissionStore implements SubmissionStore {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onFormCreated(FormDefinition form, List<FormField> fields) {
        for (FormField field : fields) {
            if (isIndexed(field)) {
                addGeneratedColumn(field);
            }
        }
    }

    @Override
    public void onFieldAdded(FormDefinition form, FormField field) {
        if (isIndexed(field)) {
            addGeneratedColumn(field);
        }
    }

    @Override
    public void onFieldRemoved(FormDefinition form, FormField field) {
        if (isIndexed(field)) {
            String column = generatedColumnName(field);
            jdbc.execute("ALTER TABLE form_submission_json DROP COLUMN " + column);
        }
    }

    @Override
    @Transactional
    public void save(FormDefinition form, List<FormField> fields, Long submissionId, Map<String, Object> values) {
        String json = toJson(values);
        jdbc.update("INSERT INTO form_submission_json (submission_id, data) VALUES (?, CAST(? AS JSON))", submissionId, json);
    }

    @Override
    @Transactional
    public void update(FormDefinition form, List<FormField> fields, Long submissionId, Map<String, Object> values) {
        String json = toJson(values);
        jdbc.update("UPDATE form_submission_json SET data = CAST(? AS JSON) WHERE submission_id = ?", json, submissionId);
    }

    @Override
    public void delete(FormDefinition form, Long submissionId) {
        jdbc.update("DELETE FROM form_submission_json WHERE submission_id = ?", submissionId);
    }

    @Override
    public Map<String, Object> findById(FormDefinition form, List<FormField> fields, Long submissionId) {
        List<String> jsons = jdbc.queryForList(
                "SELECT data FROM form_submission_json WHERE submission_id = ?", String.class, submissionId);
        if (jsons.isEmpty()) {
            return Map.of();
        }
        return typedFromJson(jsons.get(0), fields);
    }

    @Override
    public List<SubmissionRecord> query(FormDefinition form, List<FormField> fields, QuerySpec spec) {
        Map<String, FormField> byKey = indexByKey(fields);
        StringBuilder sql = new StringBuilder(
                "SELECT fs.id AS submission_id, j.data AS data FROM form_submission fs " +
                        "JOIN form_submission_json j ON j.submission_id = fs.id WHERE fs.form_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(form.getId());

        for (FilterCondition filter : spec.getFilters()) {
            FormField field = byKey.get(filter.fieldKey());
            if (field == null) continue;
            appendFilterPredicate(sql, field, filter, params);
        }
        if (spec.getOwnerId() != null) {
            sql.append(" AND fs.created_by = ? ");
            params.add(spec.getOwnerId());
        }

        if (spec.getSortFieldKey() != null && byKey.containsKey(spec.getSortFieldKey())) {
            requireSafeKey(spec.getSortFieldKey());
            sql.append(" ORDER BY j.data->>'$.").append(spec.getSortFieldKey()).append("' ")
                    .append(spec.isSortDescending() ? "DESC" : "ASC");
        } else {
            sql.append(" ORDER BY fs.id ").append(spec.isSortDescending() ? "DESC" : "ASC");
        }
        sql.append(" LIMIT ? OFFSET ?");
        params.add(spec.getSize());
        params.add(spec.getOffset());

        return jdbc.query(sql.toString(), (rs, rowNum) -> new SubmissionRecord(
                rs.getLong("submission_id"), typedFromJson(rs.getString("data"), fields)
        ), params.toArray());
    }

    @Override
    public long count(FormDefinition form, List<FormField> fields, QuerySpec spec) {
        Map<String, FormField> byKey = indexByKey(fields);
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM form_submission fs JOIN form_submission_json j ON j.submission_id = fs.id WHERE fs.form_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(form.getId());
        for (FilterCondition filter : spec.getFilters()) {
            FormField field = byKey.get(filter.fieldKey());
            if (field == null) continue;
            appendFilterPredicate(sql, field, filter, params);
        }
        if (spec.getOwnerId() != null) {
            sql.append(" AND fs.created_by = ? ");
            params.add(spec.getOwnerId());
        }
        Long result = jdbc.queryForObject(sql.toString(), params.toArray(), Long.class);
        return result == null ? 0 : result;
    }

    @Override
    public List<AggregateRow> aggregate(FormDefinition form, List<FormField> fields, AggregateSpec spec) {
        Map<String, FormField> byKey = indexByKey(fields);
        String aggExpr = switch (spec.op()) {
            case SUM -> "SUM(CAST(j.data->>'$." + safe(spec.fieldKey()) + "' AS DECIMAL(20,6)))";
            case AVG -> "AVG(CAST(j.data->>'$." + safe(spec.fieldKey()) + "' AS DECIMAL(20,6)))";
            case COUNT -> "COUNT(*)";
        };
        StringBuilder sql = new StringBuilder("SELECT ");
        if (spec.groupByFieldKey() != null) {
            requireSafeKey(spec.groupByFieldKey());
            sql.append("j.data->>'$.").append(spec.groupByFieldKey()).append("' AS grp_val, ");
        }
        sql.append(aggExpr).append(" AS agg_val FROM form_submission fs JOIN form_submission_json j ON j.submission_id = fs.id WHERE fs.form_id = ?");
        List<Object> params = new ArrayList<>(List.of(form.getId()));
        if (spec.groupByFieldKey() != null) {
            sql.append(" GROUP BY grp_val");
        }
        return jdbc.query(sql.toString(), (rs, rowNum) -> new AggregateRow(
                spec.groupByFieldKey() != null ? rs.getString("grp_val") : null,
                (Number) rs.getObject("agg_val")
        ), params.toArray());
    }

    @Override
    public long storageSizeBytes(FormDefinition form) {
        Long bytes = jdbc.queryForObject(
                "SELECT COALESCE(SUM(LENGTH(j.data)), 0) FROM form_submission_json j " +
                        "JOIN form_submission fs ON fs.id = j.submission_id WHERE fs.form_id = ?",
                Long.class, form.getId());
        return bytes == null ? 0 : bytes;
    }

    private void appendFilterPredicate(StringBuilder sql, FormField field, FilterCondition filter, List<Object> params) {
        requireSafeKey(field.getFieldKey());
        String path = "j.data->>'$." + field.getFieldKey() + "'";
        boolean numeric = switch (field.getType()) {
            case NUMBER, DECIMAL, CALCULATED -> true;
            default -> false;
        };
        String expr = numeric ? "CAST(" + path + " AS DECIMAL(20,6))" : path;

        switch (filter.op()) {
            case EQ -> {
                sql.append(" AND ").append(expr).append(" = ? ");
                params.add(filter.value());
            }
            case RANGE -> {
                if (filter.value() != null) {
                    sql.append(" AND ").append(expr).append(" >= ? ");
                    params.add(filter.value());
                }
                if (filter.value2() != null) {
                    sql.append(" AND ").append(expr).append(" <= ? ");
                    params.add(filter.value2());
                }
            }
            case LIKE -> {
                sql.append(" AND ").append(path).append(" LIKE ? ");
                params.add("%" + filter.value() + "%");
            }
        }
    }

    private boolean isIndexed(FormField field) {
        String config = field.getConfig();
        return config != null && config.contains("\"indexed\":true");
    }

    private void addGeneratedColumn(FormField field) {
        requireSafeKey(field.getFieldKey());
        String column = generatedColumnName(field);
        String sqlType = switch (field.getType()) {
            case NUMBER, DECIMAL, CALCULATED -> "DECIMAL(20,6)";
            case DATE -> "DATE";
            default -> "VARCHAR(255)";
        };
        String castExpr = switch (field.getType()) {
            case NUMBER, DECIMAL, CALCULATED -> "CAST(data->>'$." + field.getFieldKey() + "' AS DECIMAL(20,6))";
            case DATE -> "CAST(data->>'$." + field.getFieldKey() + "' AS DATE)";
            default -> "CAST(data->>'$." + field.getFieldKey() + "' AS CHAR(255))";
        };
        jdbc.execute("ALTER TABLE form_submission_json ADD COLUMN " + column + " " + sqlType +
                " GENERATED ALWAYS AS (" + castExpr + ") VIRTUAL, ADD INDEX idx_" + column + " (" + column + ")");
    }

    private String generatedColumnName(FormField field) {
        return "gc_" + field.getId();
    }

    private void requireSafeKey(String fieldKey) {
        if (!SAFE_IDENTIFIER.matcher(fieldKey).matches()) {
            throw new IllegalArgumentException("Unsafe field key for JSON path/DDL: " + fieldKey);
        }
    }

    private String safe(String fieldKey) {
        requireSafeKey(fieldKey);
        return fieldKey;
    }

    private Map<String, FormField> indexByKey(List<FormField> fields) {
        return fields.stream().collect(Collectors.toMap(FormField::getFieldKey, f -> f));
    }

    private String toJson(Map<String, Object> values) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((k, v) -> normalized.put(k, v instanceof LocalDate ld ? ld.toString() : v));
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize submission to JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> typedFromJson(String json, List<FormField> fields) {
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse submission JSON", e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (FormField field : fields) {
            Object v = raw.get(field.getFieldKey());
            if (v == null) continue;
            result.put(field.getFieldKey(), coerce(v, field.getType()));
        }
        return result;
    }

    private Object coerce(Object v, FieldType type) {
        return switch (type) {
            case NUMBER, DECIMAL, CALCULATED -> v instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(v.toString());
            case DATE -> v instanceof String s ? LocalDate.parse(s) : v;
            case BOOLEAN -> v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
            default -> v;
        };
    }
}
