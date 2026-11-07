package com.notes.notes.forms.repository;

import com.notes.notes.forms.entity.FormField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormFieldRepository extends JpaRepository<FormField, Long> {
    List<FormField> findByForm_IdOrderBySortOrderAsc(Long formId);
}
