package com.notes.notes.forms.storage.eav;

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
 * Typed entity-attribute-value storage: one row per (submission, field) in
 * form_value, with value_text/value_num/value_date/value_bool columns so filters
 * and aggregates can use real indexes instead of string comparison. Adding a field
 * to an existing form is free (no DDL) - it's just a new field_id that future rows
 * reference; existing rows are untouched.
 */
@Component
@RequiredArgsConstructor
public class EavSubmissionStore implements SubmissionStore {

    private final JdbcTemplate jdbc;

    @Override
    public void onFormCreated(FormDefinition form, List<FormField> fields) {
        // No DDL needed: form_value already exists and is shared by every EAV-backed form.
    }

    @Override
    public void onFieldAdded(FormDefinition form, FormField field) {
        // Nothing to do - a new field_id just starts being referenced by future rows.
    }

    @Override
    public void onFieldRemoved(FormDefinition form, FormField field) {
        jdbc.update("DELETE FROM form_value WHERE field_id = ?", field.getId());
    }

    @Override
    @Transactional
    public void save(FormDefinition form, List<FormField> fields, Long submissionId, Map<String, Object> values) {
        Map<String, FormField> byKey = indexByKey(fields);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            FormField field = byKey.get(entry.getKey());
            if (field == null) {
                continue;
            }
            insertOne(submissionId, field, entry.getValue());
        }
    }

    @Override
    @Transactional
    public void update(FormDefinition form, List<FormField> fields, Long submissionId, Map<String, Object> values) {
        delete(form, submissionId);
        save(form, fields, submissionId, values);
    }

    @Override
    public void delete(FormDefinition form, Long submissionId) {
        jdbc.update("DELETE FROM form_value WHERE submission_id = ?", submissionId);
    }

    @Override
    public Map<String, Object> findById(FormDefinition form, List<FormField> fields, Long submissionId) {
        Map<Long, FormField> byId = fields.stream().collect(Collectors.toMap(FormField::getId, f -> f));
        Map<String, Object> result = new LinkedHashMap<>();
        jdbc.query("SELECT field_id, value_text, value_num, value_date, value_bool FROM form_value WHERE submission_id = ?",
                rs -> {
                    FormField field = byId.get(rs.getLong("field_id"));
                    if (field != null) {
                        result.put(field.getFieldKey(), readTypedValue(rs, field.getType()));
                    }
                }, submissionId);
        return result;
    }

    @Override
    public List<SubmissionRecord> query(FormDefinition form, List<FormField> fields, QuerySpec spec) {
        Map<String, FormField> byKey = indexByKey(fields);
        StringBuilder sql = new StringBuilder(
                "SELECT fs.id AS submission_id FROM form_submission fs ");
        List<Object> params = new ArrayList<>();
        int joinIndex = 0;

        for (FilterCondition filter : spec.getFilters()) {
            FormField field = byKey.get(filter.fieldKey());
            if (field == null) continue;
            String alias = "fv" + (joinIndex++);
            sql.append("JOIN form_value ").append(alias).append(" ON ").append(alias)
                    .append(".submission_id = fs.id AND ").append(alias).append(".field_id = ? ");
            params.add(field.getId());
            appendFilterPredicate(sql, alias, filter, field.getType(), params);
        }

        // Sort via a joined alias on the sort field, not a correlated per-row subquery
        // (the EAV_NAIVE comparison variant keeps the subquery form deliberately).
        // LEFT JOIN: a submission missing this field must still appear in the result,
        // just sorted as if its value were 0/NULL, matching the subquery version's
        // COALESCE(value_num, 0) behavior.
        String sortAlias = null;
        FormField sortField = spec.getSortFieldKey() == null ? null : byKey.get(spec.getSortFieldKey());
        if (sortField != null) {
            sortAlias = "sortv";
            sql.append("LEFT JOIN form_value ").append(sortAlias).append(" ON ").append(sortAlias)
                    .append(".submission_id = fs.id AND ").append(sortAlias).append(".field_id = ")
                    .append(sortField.getId()).append(" ");
        }

        sql.append("WHERE fs.form_id = ?");
        params.add(form.getId());
        if (spec.getOwnerId() != null) {
            sql.append(" AND fs.created_by = ?");
            params.add(spec.getOwnerId());
        }

        if (sortAlias != null) {
            sql.append(" ORDER BY COALESCE(").append(sortAlias).append(".value_num, 0) ")
                    .append(spec.isSortDescending() ? "DESC" : "ASC");
        } else {
            sql.append(" ORDER BY fs.id ").append(spec.isSortDescending() ? "DESC" : "ASC");
        }
        sql.append(" LIMIT ? OFFSET ?");
        params.add(spec.getSize());
        params.add(spec.getOffset());

        List<Long> submissionIds = jdbc.queryForList(sql.toString(), params.toArray(), Long.class);
        if (submissionIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Map<String, Object>> valuesBySubmission = new LinkedHashMap<>();
        for (Long id : submissionIds) {
            valuesBySubmission.put(id, new LinkedHashMap<>());
        }
        String inClause = submissionIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, FormField> byId = fields.stream().collect(Collectors.toMap(FormField::getId, f -> f));
        jdbc.query("SELECT submission_id, field_id, value_text, value_num, value_date, value_bool FROM form_value WHERE submission_id IN (" + inClause + ")",
                rs -> {
                    Long sid = rs.getLong("submission_id");
                    FormField field = byId.get(rs.getLong("field_id"));
                    Map<String, Object> target = valuesBySubmission.get(sid);
                    if (field != null && target != null) {
                        target.put(field.getFieldKey(), readTypedValue(rs, field.getType()));
                    }
                });

        return submissionIds.stream()
                .map(id -> new SubmissionRecord(id, valuesBySubmission.get(id)))
                .toList();
    }

    @Override
    public long count(FormDefinition form, List<FormField> fields, QuerySpec spec) {
        Map<String, FormField> byKey = indexByKey(fields);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM form_submission fs ");
        List<Object> params = new ArrayList<>();
        int joinIndex = 0;
        for (FilterCondition filter : spec.getFilters()) {
            FormField field = byKey.get(filter.fieldKey());
            if (field == null) continue;
            String alias = "fv" + (joinIndex++);
            sql.append("JOIN form_value ").append(alias).append(" ON ").append(alias)
                    .append(".submission_id = fs.id AND ").append(alias).append(".field_id = ? ");
            params.add(field.getId());
            appendFilterPredicate(sql, alias, filter, field.getType(), params);
        }
        sql.append("WHERE fs.form_id = ?");
        params.add(form.getId());
        if (spec.getOwnerId() != null) {
            sql.append(" AND fs.created_by = ?");
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
            case SUM -> "SUM(val.value_num)";
            case AVG -> "AVG(val.value_num)";
            case COUNT -> "COUNT(*)";
        };

        StringBuilder sql = new StringBuilder("SELECT ");
        List<Object> params = new ArrayList<>();
        if (groupField != null) {
            sql.append("grp.value_text AS grp_val, ");
        }
        sql.append(aggExpr).append(" AS agg_val FROM form_submission fs ");

        if (valueField != null) {
            sql.append("JOIN form_value val ON val.submission_id = fs.id AND val.field_id = ? ");
            params.add(valueField.getId());
        } else {
            sql.append("LEFT JOIN form_value val ON 1=0 ");
        }
        if (groupField != null) {
            sql.append("JOIN form_value grp ON grp.submission_id = fs.id AND grp.field_id = ? ");
            params.add(groupField.getId());
        }
        sql.append("WHERE fs.form_id = ?");
        params.add(form.getId());
        if (groupField != null) {
            sql.append(" GROUP BY grp.value_text");
        }

        return jdbc.query(sql.toString(), (rs, rowNum) -> new AggregateRow(
                groupField != null ? rs.getString("grp_val") : null,
                (Number) rs.getObject("agg_val")
        ), params.toArray());
    }

    @Override
    public long storageSizeBytes(FormDefinition form) {
        Long bytes = jdbc.queryForObject(
                "SELECT COALESCE(SUM(LENGTH(COALESCE(value_text,'')) + 12), 0) FROM form_value fv " +
                        "JOIN form_submission fs ON fs.id = fv.submission_id WHERE fs.form_id = ?",
                Long.class, form.getId());
        return bytes == null ? 0 : bytes;
    }

    private void insertOne(Long submissionId, FormField field, Object value) {
        if (value == null) {
            return;
        }
        String text = null;
        BigDecimal num = null;
        Date date = null;
        Boolean bool = null;

        switch (field.getType()) {
            case TEXT, DROPDOWN, MASTER_REF -> text = String.valueOf(value);
            case NUMBER, DECIMAL, CALCULATED -> num = toBigDecimal(value);
            case DATE -> date = toSqlDate(value);
            case BOOLEAN -> bool = toBoolean(value);
            case FILE -> text = String.valueOf(value);
        }

        jdbc.update("INSERT INTO form_value (submission_id, field_id, value_text, value_num, value_date, value_bool) VALUES (?, ?, ?, ?, ?, ?)",
                submissionId, field.getId(), text, num, date, bool == null ? null : (bool ? 1 : 0));
    }

    private void appendFilterPredicate(StringBuilder sql, String alias, FilterCondition filter, FieldType type, List<Object> params) {
        String column = switch (type) {
            case NUMBER, DECIMAL, CALCULATED -> "value_num";
            case DATE -> "value_date";
            case BOOLEAN -> "value_bool";
            default -> "value_text";
        };
        switch (filter.op()) {
            case EQ -> {
                sql.append("AND ").append(alias).append(".").append(column).append(" = ? ");
                params.add(filter.value());
            }
            case RANGE -> {
                if (filter.value() != null) {
                    sql.append("AND ").append(alias).append(".").append(column).append(" >= ? ");
                    params.add(filter.value());
                }
                if (filter.value2() != null) {
                    sql.append("AND ").append(alias).append(".").append(column).append(" <= ? ");
                    params.add(filter.value2());
                }
            }
            case LIKE -> {
                sql.append("AND ").append(alias).append(".value_text LIKE ? ");
                params.add("%" + filter.value() + "%");
            }
        }
    }

    private Object readTypedValue(java.sql.ResultSet rs, FieldType type) throws java.sql.SQLException {
        return switch (type) {
            case NUMBER, DECIMAL, CALCULATED -> rs.getBigDecimal("value_num");
            case DATE -> rs.getDate("value_date") == null ? null : rs.getDate("value_date").toLocalDate();
            case BOOLEAN -> {
                Object v = rs.getObject("value_bool");
                yield v == null ? null : rs.getBoolean("value_bool");
            }
            default -> rs.getString("value_text");
        };
    }

    private Map<String, FormField> indexByKey(List<FormField> fields) {
        return fields.stream().collect(Collectors.toMap(FormField::getFieldKey, f -> f));
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(String.valueOf(value));
    }

    private Date toSqlDate(Object value) {
        if (value instanceof LocalDate ld) return Date.valueOf(ld);
        if (value instanceof Date d) return d;
        return Date.valueOf(LocalDate.parse(String.valueOf(value)));
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
