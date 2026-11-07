package com.notes.notes.forms.storage.relational;

import com.notes.notes.forms.entity.FieldType;
import com.notes.notes.forms.entity.FormDefinition;
import com.notes.notes.forms.entity.FormField;
import com.notes.notes.forms.storage.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * One physical table per form (form_data_&lt;formId&gt;), one column per field
 * (f_&lt;fieldId&gt;). Table and column names are built ONLY from auto-generated
 * numeric ids, never from admin-entered text (form_key/label/field_key), so there is
 * no SQL-identifier-injection surface even though this strategy is the one that
 * builds DDL/SQL by string concatenation.
 */
@Component
@RequiredArgsConstructor
public class RelationalSubmissionStore implements SubmissionStore {

    private final JdbcTemplate jdbc;

    private String tableName(FormDefinition form) {
        return "form_data_" + form.getId();
    }

    private String columnName(FormField field) {
        return field.relationalColumnName();
    }

    private String sqlType(FieldType type) {
        return switch (type) {
            case TEXT, DROPDOWN, MASTER_REF -> "VARCHAR(500)";
            case NUMBER -> "BIGINT";
            case DECIMAL, CALCULATED -> "DECIMAL(20,6)";
            case DATE -> "DATE";
            case BOOLEAN -> "TINYINT(1)";
            case FILE -> "VARCHAR(500)";
        };
    }

    @Override
    public void onFormCreated(FormDefinition form, List<FormField> fields) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(tableName(form))
                .append(" (submission_id BIGINT NOT NULL PRIMARY KEY");
        for (FormField field : fields) {
            sql.append(", ").append(columnName(field)).append(" ").append(sqlType(field.getType()));
        }
        for (FormField field : fields) {
            if (isIndexed(field)) {
                sql.append(", KEY idx_").append(columnName(field)).append(" (").append(columnName(field)).append(")");
            }
        }
        sql.append(", CONSTRAINT fk_").append(tableName(form))
                .append("_submission FOREIGN KEY (submission_id) REFERENCES form_submission (id)")
                .append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute(sql.toString());
    }

    /** Same "indexed":true config convention JSON storage uses - a field opts into a secondary index. */
    private boolean isIndexed(FormField field) {
        String config = field.getConfig();
        return config != null && config.contains("\"indexed\":true");
    }

    @Override
    public void onFieldAdded(FormDefinition form, FormField field) {
        jdbc.execute("ALTER TABLE " + tableName(form) + " ADD COLUMN " + columnName(field) + " " + sqlType(field.getType()));
        if (isIndexed(field)) {
            jdbc.execute("ALTER TABLE " + tableName(form) + " ADD INDEX idx_" + columnName(field) + " (" + columnName(field) + ")");
        }
    }

    @Override
    public void onFieldRemoved(FormDefinition form, FormField field) {
        jdbc.execute("ALTER TABLE " + tableName(form) + " DROP COLUMN " + columnName(field));
    }

    @Override
    @Transactional
    public void save(FormDefinition form, List<FormField> fields, Long submissionId, Map<String, Object> values) {
        Map<String, FormField> byKey = indexByKey(fields);
        List<String> columns = new ArrayList<>(List.of("submission_id"));
        List<Object> params = new ArrayList<>(List.of(submissionId));
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            FormField field = byKey.get(entry.getKey());
            if (field == null) continue;
            columns.add(columnName(field));
            params.add(convert(entry.getValue(), field.getType()));
        }
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        jdbc.update("INSERT INTO " + tableName(form) + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")",
                params.toArray());
    }

    @Override
    @Transactional
    public void update(FormDefinition form, List<FormField> fields, Long submissionId, Map<String, Object> values) {
        Map<String, FormField> byKey = indexByKey(fields);
        List<String> assignments = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            FormField field = byKey.get(entry.getKey());
            if (field == null) continue;
            assignments.add(columnName(field) + " = ?");
            params.add(convert(entry.getValue(), field.getType()));
        }
        if (assignments.isEmpty()) return;
        params.add(submissionId);
        jdbc.update("UPDATE " + tableName(form) + " SET " + String.join(", ", assignments) + " WHERE submission_id = ?",
                params.toArray());
    }

    @Override
    public void delete(FormDefinition form, Long submissionId) {
        jdbc.update("DELETE FROM " + tableName(form) + " WHERE submission_id = ?", submissionId);
    }

    @Override
    public Map<String, Object> findById(FormDefinition form, List<FormField> fields, Long submissionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + tableName(form) + " WHERE submission_id = ?", submissionId);
        if (rows.isEmpty()) return Map.of();
        return toValuesMap(rows.get(0), fields);
    }

    @Override
    public List<SubmissionRecord> query(FormDefinition form, List<FormField> fields, QuerySpec spec) {
        Map<String, FormField> byKey = indexByKey(fields);
        StringBuilder sql = new StringBuilder("SELECT t.* FROM ").append(tableName(form)).append(" t ");
        List<Object> params = new ArrayList<>();
        boolean needsSubmissionJoin = spec.getOwnerId() != null;
        if (needsSubmissionJoin) {
            sql.append("JOIN form_submission fs ON fs.id = t.submission_id ");
        }
        sql.append("WHERE 1=1 ");
        for (FilterCondition filter : spec.getFilters()) {
            FormField field = byKey.get(filter.fieldKey());
            if (field == null) continue;
            appendFilterPredicate(sql, field, filter, params);
        }
        if (needsSubmissionJoin) {
            sql.append(" AND fs.created_by = ? ");
            params.add(spec.getOwnerId());
        }
        if (spec.getSortFieldKey() != null && byKey.containsKey(spec.getSortFieldKey())) {
            sql.append(" ORDER BY t.").append(columnName(byKey.get(spec.getSortFieldKey())))
                    .append(" ").append(spec.isSortDescending() ? "DESC" : "ASC");
        } else {
            sql.append(" ORDER BY t.submission_id ").append(spec.isSortDescending() ? "DESC" : "ASC");
        }
        sql.append(" LIMIT ? OFFSET ?");
        params.add(spec.getSize());
        params.add(spec.getOffset());

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        return rows.stream()
                .map(row -> new SubmissionRecord((Long) row.get("submission_id"), toValuesMap(row, fields)))
                .toList();
    }

    @Override
    public long count(FormDefinition form, List<FormField> fields, QuerySpec spec) {
        Map<String, FormField> byKey = indexByKey(fields);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(tableName(form)).append(" t ");
        List<Object> params = new ArrayList<>();
        boolean needsSubmissionJoin = spec.getOwnerId() != null;
        if (needsSubmissionJoin) {
            sql.append("JOIN form_submission fs ON fs.id = t.submission_id ");
        }
        sql.append("WHERE 1=1 ");
        for (FilterCondition filter : spec.getFilters()) {
            FormField field = byKey.get(filter.fieldKey());
            if (field == null) continue;
            appendFilterPredicate(sql, field, filter, params);
        }
        if (needsSubmissionJoin) {
            sql.append(" AND fs.created_by = ? ");
            params.add(spec.getOwnerId());
        }
        Long result = jdbc.queryForObject(sql.toString(), params.toArray(), Long.class);
        return result == null ? 0 : result;
    }

    @Override
    public List<AggregateRow> aggregate(FormDefinition form, List<FormField> fields, AggregateSpec spec) {
        Map<String, FormField> byKey = indexByKey(fields);
        FormField valueField = spec.fieldKey() == null ? null : byKey.get(spec.fieldKey());
        FormField groupField = spec.groupByFieldKey() == null ? null : byKey.get(spec.groupByFieldKey());

        String aggExpr = switch (spec.op()) {
            case SUM -> "SUM(" + (valueField != null ? columnName(valueField) : "0") + ")";
            case AVG -> "AVG(" + (valueField != null ? columnName(valueField) : "0") + ")";
            case COUNT -> "COUNT(*)";
        };
        StringBuilder sql = new StringBuilder("SELECT ");
        if (groupField != null) {
            sql.append(columnName(groupField)).append(" AS grp_val, ");
        }
        sql.append(aggExpr).append(" AS agg_val FROM ").append(tableName(form));
        if (groupField != null) {
            sql.append(" GROUP BY ").append(columnName(groupField));
        }
        return jdbc.query(sql.toString(), (rs, rowNum) -> new AggregateRow(
                groupField != null ? rs.getObject("grp_val") : null,
                (Number) rs.getObject("agg_val")
        ));
    }

    @Override
    public long storageSizeBytes(FormDefinition form) {
        Long bytes = jdbc.queryForObject(
                "SELECT (DATA_LENGTH + INDEX_LENGTH) FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Long.class, tableName(form));
        return bytes == null ? 0 : bytes;
    }

    private void appendFilterPredicate(StringBuilder sql, FormField field, FilterCondition filter, List<Object> params) {
        String column = "t." + columnName(field);
        switch (filter.op()) {
            case EQ -> {
                sql.append(" AND ").append(column).append(" = ? ");
                params.add(convert(filter.value(), field.getType()));
            }
            case RANGE -> {
                if (filter.value() != null) {
                    sql.append(" AND ").append(column).append(" >= ? ");
                    params.add(convert(filter.value(), field.getType()));
                }
                if (filter.value2() != null) {
                    sql.append(" AND ").append(column).append(" <= ? ");
                    params.add(convert(filter.value2(), field.getType()));
                }
            }
            case LIKE -> {
                sql.append(" AND ").append(column).append(" LIKE ? ");
                params.add("%" + filter.value() + "%");
            }
        }
    }

    private Map<String, Object> toValuesMap(Map<String, Object> row, List<FormField> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (FormField field : fields) {
            Object v = row.get(columnName(field));
            if (v == null) continue;
            result.put(field.getFieldKey(), v instanceof Date d ? d.toLocalDate() : v);
        }
        return result;
    }

    private Object convert(Object value, FieldType type) {
        if (value == null) return null;
        return switch (type) {
            case NUMBER -> value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
            case DECIMAL, CALCULATED -> value instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(value));
            case DATE -> value instanceof LocalDate ld ? Date.valueOf(ld) : value;
            case BOOLEAN -> (value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value))) ? 1 : 0;
            default -> value;
        };
    }

    private Map<String, FormField> indexByKey(List<FormField> fields) {
        return fields.stream().collect(Collectors.toMap(FormField::getFieldKey, f -> f));
    }
}
