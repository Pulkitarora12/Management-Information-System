package com.notes.notes.forms.service;

import com.notes.notes.forms.entity.FormDefinition;
import com.notes.notes.forms.entity.FormField;
import com.notes.notes.forms.entity.StorageType;
import com.notes.notes.forms.repository.FormDefinitionRepository;
import com.notes.notes.forms.storage.SubmissionStoreRouter;
import com.notes.notes.rbac.entity.ResourceType;
import com.notes.notes.rbac.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Creating a form: save its definition + fields, auto-register it as a resource
 * (D2 - "every new custom form auto-registers as a resource"), then tell the
 * chosen storage strategy to provision whatever it needs (D10).
 */
@Service
@RequiredArgsConstructor
public class FormDefinitionService {

    private final FormDefinitionRepository formDefinitionRepository;
    private final ResourceService resourceService;
    private final SubmissionStoreRouter submissionStoreRouter;

    @Transactional
    public FormDefinition createForm(String formKey, String name, String description,
                                      StorageType storageType, List<FormField> fields) {
        FormDefinition form = new FormDefinition();
        form.setFormKey(formKey);
        form.setName(name);
        form.setDescription(description);
        form.setStorageType(storageType);
        for (FormField field : fields) {
            field.setForm(form);
            form.getFields().add(field);
        }
        // resource_id is nullable precisely so the form can be saved once to get an id,
        // then register the real FORM_<id> resource (D2) using that id.
        FormDefinition saved = formDefinitionRepository.save(form);

        var resource = resourceService.registerResource(saved.resourceKey(), name, ResourceType.FORM);
        saved.setResource(resource);
        formDefinitionRepository.save(saved);

        submissionStoreRouter.onFormCreated(saved, saved.getFields());
        return saved;
    }

    public FormDefinition getForm(Long id) {
        return formDefinitionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Form not found: " + id));
    }

    public List<FormDefinition> listActiveForms() {
        return formDefinitionRepository.findAll().stream().filter(FormDefinition::isActive).toList();
    }
}
