package com.notes.notes.forms.storage;

import java.util.Map;

/** One submission's field values, keyed by field_key, as returned by a store. */
public record SubmissionRecord(Long submissionId, Map<String, Object> values) {
}
