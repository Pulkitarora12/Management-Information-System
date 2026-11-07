package com.notes.notes.forms;

import com.notes.notes.forms.entity.StorageType;
import com.notes.notes.forms.storage.SubmissionStore;
import com.notes.notes.forms.storage.relational.RelationalSubmissionStore;
import org.springframework.beans.factory.annotation.Autowired;

class RelationalSubmissionStoreContractTest extends AbstractSubmissionStoreContractTest {

    @Autowired
    private RelationalSubmissionStore relationalSubmissionStore;

    @Override
    protected SubmissionStore store() {
        return relationalSubmissionStore;
    }

    @Override
    protected StorageType storageType() {
        return StorageType.RELATIONAL;
    }
}
