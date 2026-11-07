package com.notes.notes.forms.storage;

/** aggregate() input: e.g. SUM(qty) GROUP BY line, or plain COUNT(*) when groupByFieldKey is null. */
public record AggregateSpec(AggregateOp op, String fieldKey, String groupByFieldKey) {
}
