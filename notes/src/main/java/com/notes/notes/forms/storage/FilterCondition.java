package com.notes.notes.forms.storage;

/**
 * One filter on one field. EQ/RANGE compare value_num or value_date depending on the
 * field's type; LIKE always compares as text. RANGE uses value and value2 as the
 * inclusive lower/upper bound (either may be null for an open-ended range).
 */
public record FilterCondition(String fieldKey, FilterOp op, Object value, Object value2) {
    public static FilterCondition eq(String fieldKey, Object value) {
        return new FilterCondition(fieldKey, FilterOp.EQ, value, null);
    }

    public static FilterCondition range(String fieldKey, Object from, Object to) {
        return new FilterCondition(fieldKey, FilterOp.RANGE, from, to);
    }

    public static FilterCondition like(String fieldKey, String pattern) {
        return new FilterCondition(fieldKey, FilterOp.LIKE, pattern, null);
    }
}
