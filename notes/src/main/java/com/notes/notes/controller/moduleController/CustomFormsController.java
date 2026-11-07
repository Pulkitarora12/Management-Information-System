package com.notes.notes.controller.moduleController;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.forms.entity.FieldType;
import com.notes.notes.forms.entity.FormDefinition;
import com.notes.notes.forms.entity.FormField;
import com.notes.notes.forms.entity.FormSubmission;
import com.notes.notes.forms.entity.StorageType;
import com.notes.notes.forms.repository.FormSubmissionRepository;
import com.notes.notes.forms.service.FormDefinitionService;
import com.notes.notes.forms.storage.QuerySpec;
import com.notes.notes.forms.storage.SubmissionRecord;
import com.notes.notes.forms.storage.SubmissionStoreRouter;
import com.notes.notes.repository.authRepo.UserRepository;
import com.notes.notes.security.services.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin screens to design a custom form (RELATIONAL storage only - the strategy the
 * benchmark showed is fastest and smallest; EAV/JSON remain available in the storage
 * layer for comparison but are not offered here), plus per-form screens to view and
 * submit entries. Each form auto-registers its own FORM_&lt;id&gt; resource on creation
 * (FormDefinitionService) and is gated by it here, independent of the top-level
 * FORM_BUILDER gate that only controls whether the Create/List buttons are visible.
 */
@Controller
@RequestMapping("/custom-forms")
@RequiredArgsConstructor
public class CustomFormsController {

    private final FormDefinitionService formDefinitionService;
    private final SubmissionStoreRouter submissionStoreRouter;
    private final FormSubmissionRepository formSubmissionRepository;
    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;

    @PreAuthorize("@authz.can('FORM_BUILDER','VIEW') or @authz.can('FORM_BUILDER','CREATE')")
    @GetMapping
    public String listForms(Model model) {
        model.addAttribute("forms", formDefinitionService.listActiveForms());
        return "customforms/List";
    }

    @PreAuthorize("@authz.can('FORM_BUILDER','CREATE')")
    @GetMapping("/new")
    public String newFormDesigner(Model model) {
        model.addAttribute("fieldTypes", fieldTypesForDesigner());
        return "customforms/Designer";
    }

    @PreAuthorize("@authz.can('FORM_BUILDER','CREATE')")
    @PostMapping("/new")
    public String createForm(@RequestParam String formKey, @RequestParam String name,
                              @RequestParam(required = false) String description,
                              HttpServletRequest request) {
        List<FormField> fields = parseDesignerFields(request);
        FormDefinition form = formDefinitionService.createForm(
                formKey.trim().toLowerCase().replace(' ', '_'), name, description,
                StorageType.RELATIONAL, fields);
        return "redirect:/custom-forms?created=" + form.getId();
    }

    @PreAuthorize("@authz.can('FORM_' + #id, 'VIEW')")
    @GetMapping("/{id}/view")
    public String viewSubmissions(@PathVariable Long id, Model model) {
        FormDefinition form = formDefinitionService.getForm(id);
        QuerySpec spec = new QuerySpec().page(0, 100).sortBy(null, true);
        List<SubmissionRecord> records = submissionStoreRouter.query(form, form.getFields(), spec);
        model.addAttribute("form", form);
        model.addAttribute("records", records);
        return "customforms/View";
    }

    @PreAuthorize("@authz.can('FORM_' + #id, 'CREATE')")
    @GetMapping("/{id}/entry")
    public String newEntryForm(@PathVariable Long id, Model model) {
        FormDefinition form = formDefinitionService.getForm(id);
        model.addAttribute("form", form);
        model.addAttribute("values", Map.of());
        model.addAttribute("submissionId", (Long) null);
        return "customforms/Entry";
    }

    @PreAuthorize("@authz.can('FORM_' + #id, 'CREATE')")
    @PostMapping("/{id}/entry")
    public String saveEntry(@PathVariable Long id, HttpServletRequest request) {
        FormDefinition form = formDefinitionService.getForm(id);

        FormSubmission submission = new FormSubmission();
        submission.setForm(form);
        submission.setFormVersion(form.getVersion());
        Long userId = currentUserProvider.currentUserId();
        if (userId != null) {
            User user = userRepository.findById(userId).orElse(null);
            submission.setCreatedBy(user);
        }
        submission = formSubmissionRepository.save(submission);

        Map<String, Object> values = parseEntryValues(request, form.getFields());
        submissionStoreRouter.save(form, form.getFields(), submission.getId(), values);
        return "redirect:/custom-forms/" + id + "/view";
    }

    @PreAuthorize("@authz.can('FORM_' + #id, 'EDIT')")
    @GetMapping("/{id}/entry/{submissionId}")
    public String editEntryForm(@PathVariable Long id, @PathVariable Long submissionId, Model model) {
        FormDefinition form = formDefinitionService.getForm(id);
        Map<String, Object> values = submissionStoreRouter.findById(form, form.getFields(), submissionId);
        model.addAttribute("form", form);
        model.addAttribute("values", values);
        model.addAttribute("submissionId", submissionId);
        return "customforms/Entry";
    }

    @PreAuthorize("@authz.can('FORM_' + #id, 'EDIT')")
    @PostMapping("/{id}/entry/{submissionId}")
    public String updateEntry(@PathVariable Long id, @PathVariable Long submissionId, HttpServletRequest request) {
        FormDefinition form = formDefinitionService.getForm(id);
        Map<String, Object> values = parseEntryValues(request, form.getFields());
        submissionStoreRouter.update(form, form.getFields(), submissionId, values);
        return "redirect:/custom-forms/" + id + "/view";
    }

    @PreAuthorize("@authz.can('FORM_' + #id, 'DELETE')")
    @PostMapping("/{id}/entry/{submissionId}/delete")
    public String deleteEntry(@PathVariable Long id, @PathVariable Long submissionId) {
        FormDefinition form = formDefinitionService.getForm(id);
        submissionStoreRouter.delete(form, submissionId);
        formSubmissionRepository.deleteById(submissionId);
        return "redirect:/custom-forms/" + id + "/view";
    }

    private List<FieldType> fieldTypesForDesigner() {
        return List.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DECIMAL, FieldType.DATE,
                FieldType.BOOLEAN, FieldType.DROPDOWN, FieldType.MASTER_REF, FieldType.FILE);
    }

    /** Designer form posts field_key[]/field_label[]/field_type[]/field_required[] as parallel arrays. */
    private List<FormField> parseDesignerFields(HttpServletRequest request) {
        String[] keys = request.getParameterValues("field_key");
        String[] labels = request.getParameterValues("field_label");
        String[] types = request.getParameterValues("field_type");
        String[] required = request.getParameterValues("field_required");
        List<FormField> fields = new ArrayList<>();
        if (keys == null) {
            return fields;
        }
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null || keys[i].isBlank()) {
                continue;
            }
            FormField field = new FormField();
            field.setFieldKey(keys[i].trim().toLowerCase().replace(' ', '_'));
            field.setLabel(labels[i]);
            field.setType(FieldType.valueOf(types[i]));
            field.setRequired("true".equals(required[i]) || "on".equals(required[i]));
            field.setSortOrder(i);
            fields.add(field);
        }
        return fields;
    }

    private Map<String, Object> parseEntryValues(HttpServletRequest request, List<FormField> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FormField field : fields) {
            String raw = request.getParameter("f_" + field.getFieldKey());
            if (raw == null || raw.isBlank()) {
                continue;
            }
            values.put(field.getFieldKey(), convert(raw, field.getType()));
        }
        return values;
    }

    private Object convert(String raw, FieldType type) {
        return switch (type) {
            case NUMBER -> Long.parseLong(raw);
            case DECIMAL -> new BigDecimal(raw);
            case DATE -> LocalDate.parse(raw);
            case BOOLEAN -> "on".equals(raw) || "true".equals(raw);
            case TEXT, DROPDOWN, MASTER_REF, FILE, CALCULATED -> raw;
        };
    }
}
