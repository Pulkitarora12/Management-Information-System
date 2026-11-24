package com.notes.notes.forms.storage;

import com.notes.notes.forms.entity.FormDefinition;
import com.notes.notes.forms.entity.StorageType;
import com.notes.notes.forms.storage.eav.EavSubmissionStore;
import com.notes.notes.forms.storage.json.JsonSubmissionStore;
import com.notes.notes.forms.storage.relational.RelationalSubmissionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The one thing every caller actually depends on (D10): pick the right
 * {@link SubmissionStore} for a form's storage_type and delegate. Swapping which
 * concrete implementation is used for testing/benchmarking means instantiating a
 * different router, or in tests, calling the implementation directly - callers
 * never hardcode EAV/JSON/RELATIONAL themselves.
 */
@Component
@RequiredArgsConstructor
public class SubmissionStoreRouter implements SubmissionStore {

    private final EavSubmissionStore eavSubmissionStore;
    private final JsonSubmissionStore jsonSubmissionStore;
    private final RelationalSubmissionStore relationalSubmissionStore;

    private SubmissionStore storeFor(FormDefinition form) {
        return storeFor(form.getStorageType());
    }

    public SubmissionStore storeFor(StorageType type) {
        return switch (type) {
            case EAV -> eavSubmissionStore;
            case JSON -> jsonSubmissionStore;
            case RELATIONAL -> relationalSubmissionStore;
        };
    }

    @Override
    public void onFormCreated(FormDefinition form, java.util.List<com.notes.notes.forms.entity.FormField> fields) {
        storeFor(form).onFormCreated(form, fields);
    }

    @Override
    public void onFieldAdded(FormDefinition form, com.notes.notes.forms.entity.FormField field) {
        storeFor(form).onFieldAdded(form, field);
    }

    @Override
    public void onFieldRemoved(FormDefinition form, com.notes.notes.forms.entity.FormField field) {
        storeFor(form).onFieldRemoved(form, field);
    }

    @Override
    public void save(FormDefinition form, java.util.List<com.notes.notes.forms.entity.FormField> fields, Long submissionId, Map<String, Object> values) {
        storeFor(form).save(form, fields, submissionId, values);
    }

    @Override
    public void update(FormDefinition form, java.util.List<com.notes.notes.forms.entity.FormField> fields, Long submissionId, Map<String, Object> values) {
        storeFor(form).update(form, fields, submissionId, values);
    }

    @Override
    public void delete(FormDefinition form, Long submissionId) {
        storeFor(form).delete(form, submissionId);
    }

    @Override
    public Map<String, Object> findById(FormDefinition form, java.util.List<com.notes.notes.forms.entity.FormField> fields, Long submissionId) {
        return storeFor(form).findById(form, fields, submissionId);
    }

    @Override
    public java.util.List<SubmissionRecord> query(FormDefinition form, java.util.List<com.notes.notes.forms.entity.FormField> fields, QuerySpec spec) {
        return storeFor(form).query(form, fields, spec);
    }

    @Override
    public long count(FormDefinition form, java.util.List<com.notes.notes.forms.entity.FormField> fields, QuerySpec spec) {
        return storeFor(form).count(form, fields, spec);
    }

    @Override
    public java.util.List<AggregateRow> aggregate(FormDefinition form, java.util.List<com.notes.notes.forms.entity.FormField> fields, AggregateSpec spec) {
        return storeFor(form).aggregate(form, fields, spec);
    }

    @Override
    public long storageSizeBytes(FormDefinition form) {
        return storeFor(form).storageSizeBytes(form);
    }
}
