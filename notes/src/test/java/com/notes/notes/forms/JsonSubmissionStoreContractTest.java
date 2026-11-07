package com.notes.notes.forms;

import com.notes.notes.forms.entity.StorageType;
import com.notes.notes.forms.storage.SubmissionStore;
import com.notes.notes.forms.storage.json.JsonSubmissionStore;
import org.springframework.beans.factory.annotation.Autowired;

class JsonSubmissionStoreContractTest extends AbstractSubmissionStoreContractTest {

    @Autowired
    private JsonSubmissionStore jsonSubmissionStore;

    @Override
    protected SubmissionStore store() {
        return jsonSubmissionStore;
    }

    @Override
    protected StorageType storageType() {
        return StorageType.JSON;
    }
}
