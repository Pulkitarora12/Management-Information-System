package com.notes.notes.forms;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.forms.entity.*;
import com.notes.notes.forms.repository.FormDefinitionRepository;
import com.notes.notes.forms.repository.FormSubmissionRepository;
import com.notes.notes.forms.service.FormDefinitionService;
import com.notes.notes.forms.storage.*;
import com.notes.notes.repository.authRepo.RoleRepository;
import com.notes.notes.repository.authRepo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 step 6: the same test suite run against EAV, JSON and RELATIONAL (one
 * concrete subclass each) to prove they behave identically from the caller's
 * point of view, before any benchmark numbers are trusted.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractSubmissionStoreContractTest {

    @Autowired
    private FormDefinitionService formDefinitionService;
    @Autowired
    private FormDefinitionRepository formDefinitionRepository;
    @Autowired
    private FormSubmissionRepository formSubmissionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;

    protected abstract SubmissionStore store();

    protected abstract StorageType storageType();

    private FormDefinition form;
    private User ownerA;
    private User ownerB;

    @BeforeEach
    void setUpForm() {
        ownerA = persistUser("ca" + uniqueSuffix());
        ownerB = persistUser("cb" + uniqueSuffix());

        List<FormField> fields = List.of(
                field("name", FieldType.TEXT, 0),
                field("qty", FieldType.NUMBER, 1),
                field("price", FieldType.DECIMAL, 2),
                field("due_date", FieldType.DATE, 3),
                field("active", FieldType.BOOLEAN, 4)
        );
        form = formDefinitionService.createForm(
                "contract_" + storageType() + "_" + uniqueSuffix(),
                "Contract Test Form (" + storageType() + ")",
                "created by AbstractSubmissionStoreContractTest",
                storageType(),
                fields
        );
    }

    @Test
    void saveThenFindByIdRoundTripsTypedValues() {
        Long submissionId = insertSubmission(ownerA, "Alpha", 10, "19.99", LocalDate.of(2026, 1, 15), true);

        Map<String, Object> result = store().findById(form, form.getFields(), submissionId);

        assertThat(result.get("name")).isEqualTo("Alpha");
        assertThat(((Number) result.get("qty")).intValue()).isEqualTo(10);
        assertThat(((BigDecimal) result.get("price")).doubleValue()).isCloseTo(19.99, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.get("due_date")).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(result.get("active")).isEqualTo(true);
    }

    @Test
    void updateOverwritesPreviousValues() {
        Long submissionId = insertSubmission(ownerA, "Before", 1, "1.00", LocalDate.of(2026, 1, 1), false);

        store().update(form, form.getFields(), submissionId, Map.of(
                "name", "After", "qty", 2, "price", new BigDecimal("2.00"),
                "due_date", LocalDate.of(2026, 2, 2), "active", true));

        Map<String, Object> result = store().findById(form, form.getFields(), submissionId);
        assertThat(result.get("name")).isEqualTo("After");
        assertThat(result.get("active")).isEqualTo(true);
    }

    @Test
    void deleteRemovesTheSubmissionValues() {
        Long submissionId = insertSubmission(ownerA, "ToDelete", 1, "1.00", LocalDate.of(2026, 1, 1), false);

        store().delete(form, submissionId);

        Map<String, Object> result = store().findById(form, form.getFields(), submissionId);
        assertThat(result).isEmpty();
    }

    @Test
    void queryFiltersByEquality() {
        insertSubmission(ownerA, "Match", 42, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerA, "NoMatch", 7, "1.00", LocalDate.of(2026, 1, 1), false);

        QuerySpec spec = new QuerySpec().addFilter(FilterCondition.eq("qty", 42)).page(0, 10);
        List<SubmissionRecord> results = store().query(form, form.getFields(), spec);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).values().get("name")).isEqualTo("Match");
    }

    @Test
    void queryFiltersByRange() {
        insertSubmission(ownerA, "Low", 5, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerA, "Mid", 50, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerA, "High", 500, "1.00", LocalDate.of(2026, 1, 1), false);

        QuerySpec spec = new QuerySpec().addFilter(FilterCondition.range("qty", 10, 100)).page(0, 10);
        List<SubmissionRecord> results = store().query(form, form.getFields(), spec);

        assertThat(results).extracting(r -> r.values().get("name")).containsExactly("Mid");
    }

    @Test
    void queryFiltersByLike() {
        insertSubmission(ownerA, "Production Entry", 1, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerA, "RMIR Entry", 1, "1.00", LocalDate.of(2026, 1, 1), false);

        QuerySpec spec = new QuerySpec().addFilter(FilterCondition.like("name", "Production")).page(0, 10);
        List<SubmissionRecord> results = store().query(form, form.getFields(), spec);

        assertThat(results).extracting(r -> r.values().get("name")).containsExactly("Production Entry");
    }

    @Test
    void queryFiltersByOwner() {
        insertSubmission(ownerA, "OwnedByA", 1, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerB, "OwnedByB", 1, "1.00", LocalDate.of(2026, 1, 1), false);

        QuerySpec spec = new QuerySpec().ownerId(ownerA.getUserId()).page(0, 10);
        List<SubmissionRecord> results = store().query(form, form.getFields(), spec);

        assertThat(results).extracting(r -> r.values().get("name")).containsExactly("OwnedByA");
    }

    @Test
    void querySortsAndPaginates() {
        insertSubmission(ownerA, "A", 1, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerA, "B", 2, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerA, "C", 3, "1.00", LocalDate.of(2026, 1, 1), false);

        QuerySpec firstPage = new QuerySpec().sortBy("qty", true).page(0, 2);
        List<SubmissionRecord> page1 = store().query(form, form.getFields(), firstPage);
        QuerySpec secondPage = new QuerySpec().sortBy("qty", true).page(1, 2);
        List<SubmissionRecord> page2 = store().query(form, form.getFields(), secondPage);

        assertThat(page1).extracting(r -> r.values().get("name")).containsExactly("C", "B");
        assertThat(page2).extracting(r -> r.values().get("name")).containsExactly("A");
    }

    @Test
    void countMatchesFilteredResultSize() {
        insertSubmission(ownerA, "X", 99, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerA, "Y", 1, "1.00", LocalDate.of(2026, 1, 1), false);

        QuerySpec spec = new QuerySpec().addFilter(FilterCondition.eq("qty", 99));
        assertThat(store().count(form, form.getFields(), spec)).isEqualTo(1L);
    }

    @Test
    void aggregateSumsAndCountsCorrectly() {
        insertSubmission(ownerA, "A", 10, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerA, "B", 20, "1.00", LocalDate.of(2026, 1, 1), false);
        insertSubmission(ownerA, "C", 30, "1.00", LocalDate.of(2026, 1, 1), false);

        List<AggregateRow> sum = store().aggregate(form, form.getFields(), new AggregateSpec(AggregateOp.SUM, "qty", null));
        assertThat(sum).hasSize(1);
        assertThat(sum.get(0).aggregateValue().doubleValue()).isEqualTo(60.0);

        List<AggregateRow> count = store().aggregate(form, form.getFields(), new AggregateSpec(AggregateOp.COUNT, null, null));
        assertThat(count.get(0).aggregateValue().longValue()).isEqualTo(3L);
    }

    @Test
    void addingAFieldAfterDataExistsDoesNotBreakExistingRows() {
        Long submissionId = insertSubmission(ownerA, "PreExisting", 1, "1.00", LocalDate.of(2026, 1, 1), false);

        FormField newField = field("notes", FieldType.TEXT, 5);
        newField.setForm(form);
        newField = saveNewFieldDirectly(newField);
        store().onFieldAdded(form, newField);

        // saveNewFieldDirectly already appended newField into form.getFields() via cascade
        List<FormField> fieldsWithNew = new ArrayList<>(form.getFields());

        // existing row: old fields still readable, new field simply absent until set
        Map<String, Object> existing = store().findById(form, fieldsWithNew, submissionId);
        assertThat(existing.get("name")).isEqualTo("PreExisting");

        // new row can use the new field right away
        store().update(form, fieldsWithNew, submissionId, Map.of("notes", "added later"));
        Map<String, Object> updated = store().findById(form, fieldsWithNew, submissionId);
        assertThat(updated.get("notes")).isEqualTo("added later");
    }

    @Test
    void removingAFieldDoesNotBreakOtherFieldsOnExistingRows() {
        Long submissionId = insertSubmission(ownerA, "KeepMe", 1, "1.00", LocalDate.of(2026, 1, 1), false);
        FormField activeField = form.getFields().stream().filter(f -> f.getFieldKey().equals("active")).findFirst().orElseThrow();

        store().onFieldRemoved(form, activeField);

        List<FormField> remaining = form.getFields().stream()
                .filter(f -> !f.getFieldKey().equals("active")).toList();
        Map<String, Object> result = store().findById(form, remaining, submissionId);
        assertThat(result.get("name")).isEqualTo("KeepMe");
    }

    // --- helpers -------------------------------------------------------------

    private Long insertSubmission(User owner, String name, int qty, String price, LocalDate dueDate, boolean active) {
        FormSubmission header = new FormSubmission();
        header.setForm(form);
        header.setFormVersion(form.getVersion());
        header.setCreatedBy(owner);
        FormSubmission savedHeader = formSubmissionRepository.save(header);

        store().save(form, form.getFields(), savedHeader.getId(), Map.of(
                "name", name, "qty", qty, "price", new BigDecimal(price), "due_date", dueDate, "active", active));
        return savedHeader.getId();
    }

    private FormField saveNewFieldDirectly(FormField field) {
        form.getFields().add(field);
        // save() on a detached entity merges and returns a different (managed) copy -
        // reassign so the rest of the test sees the field with its real generated id.
        form = formDefinitionRepository.save(form);
        return form.getFields().stream()
                .filter(f -> f.getFieldKey().equals(field.getFieldKey())).findFirst().orElseThrow();
    }

    private FormField field(String key, FieldType type, int order) {
        FormField field = new FormField();
        field.setFieldKey(key);
        field.setLabel(key);
        field.setType(type);
        field.setSortOrder(order);
        return field;
    }

    private User persistUser(String username) {
        Role legacyUserRole = roleRepository.findByRoleName(AppRole.ROLE_USER)
                .orElseThrow(() -> new IllegalStateException("Legacy ROLE_USER not seeded"));
        User user = new User();
        user.setUserName(username);
        user.setEmail(username + "@example.com");
        user.setPassword("test-password-hash");
        user.setEnabled(true);
        user.setVerified(true);
        user.setAccountNonLocked(true);
        user.setAccountNonExpired(true);
        user.setCredentialsNonExpired(true);
        user.setRole(legacyUserRole);
        return userRepository.save(user);
    }

    private String uniqueSuffix() {
        return Long.toHexString(System.nanoTime());
    }
}
