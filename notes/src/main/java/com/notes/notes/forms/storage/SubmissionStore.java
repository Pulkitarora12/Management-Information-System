package com.notes.notes.forms.storage;

import com.notes.notes.forms.entity.FormDefinition;
import com.notes.notes.forms.entity.FormField;

import java.util.List;
import java.util.Map;

/**
 * D10: every storage strategy (EAV, JSON, RELATIONAL - and whatever gets added later)
 * implements exactly this interface. Nothing outside the forms.storage.* packages
 * ever talks SQL for field values directly; it goes through whichever
 * implementation {@link SubmissionStoreRouter} picks for a given form.
 *
 * {@code fields} is passed on every call instead of looked up internally so a
 * caller that already has them (the usual case - they come with the FormDefinition)
 * doesn't pay for a repeated query, and so a store never needs its own repository
 * dependency on form_field.
 */
public interface SubmissionStore {

    /** Called once when a form is first created, after its fields exist. */
    void onFormCreated(FormDefinition form, List<FormField> fields);

    /** Called when a field is added to an existing, possibly already-populated form. */
    void onFieldAdded(FormDefinition form, FormField field);

    /** Called when a field is removed from an existing form. */
    void onFieldRemoved(FormDefinition form, FormField field);

    /** Persists the values for a new submission (the form_submission header row already exists). */
    void save(FormDefinition form, List<FormField> fields, Long submissionId, Map<String, Object> values);

    void update(FormDefinition form, List<FormField> fields, Long submissionId, Map<String, Object> values);

    /** Deletes only the field values; the form_submission header row is deleted by the caller. */
    void delete(FormDefinition form, Long submissionId);

    Map<String, Object> findById(FormDefinition form, List<FormField> fields, Long submissionId);

    List<SubmissionRecord> query(FormDefinition form, List<FormField> fields, QuerySpec spec);

    long count(FormDefinition form, List<FormField> fields, QuerySpec spec);

    List<AggregateRow> aggregate(FormDefinition form, List<FormField> fields, AggregateSpec spec);

    /** Total bytes this form's data currently occupies (information_schema), for the benchmark's storage-size metric. */
    long storageSizeBytes(FormDefinition form);
}
