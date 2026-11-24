package com.notes.notes.forms;

import com.notes.notes.forms.entity.StorageType;
import com.notes.notes.forms.storage.SubmissionStore;
import com.notes.notes.forms.storage.eav.EavSubmissionStore;
import org.springframework.beans.factory.annotation.Autowired;

class EavSubmissionStoreContractTest extends AbstractSubmissionStoreContractTest {

    @Autowired
    private EavSubmissionStore eavSubmissionStore;

    @Override
    protected SubmissionStore store() {
        return eavSubmissionStore;
    }

    @Override
    protected StorageType storageType() {
        return StorageType.EAV;
    }
}
