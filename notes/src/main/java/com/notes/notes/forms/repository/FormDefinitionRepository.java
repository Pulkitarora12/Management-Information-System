package com.notes.notes.forms.repository;

import com.notes.notes.forms.entity.FormDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FormDefinitionRepository extends JpaRepository<FormDefinition, Long> {
    Optional<FormDefinition> findByFormKey(String formKey);
}
