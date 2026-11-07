package com.notes.notes.forms.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@NoArgsConstructor
@Table(name = "form_field", uniqueConstraints = @UniqueConstraint(columnNames = {"form_id", "field_key"}))
public class FormField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_id")
    @JsonBackReference
    @ToString.Exclude
    private FormDefinition form;

    @Column(name = "field_key", nullable = false, length = 64)
    private String fieldKey;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FieldType type;

    @Column(nullable = false)
    private boolean required = false;

    /** Metadata-only soft-remove flag - false means the field is retired but its data (if any) is untouched. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /**
     * Free-form JSON string: min/max/regex, DROPDOWN options, MASTER_REF
     * {masterId, masterFieldId}, CALCULATED {formula}, indexed flag for JSON storage.
     */
    @Column(columnDefinition = "json")
    private String config;

    /** "f_<id>" - the physical column name this field gets in the RELATIONAL strategy. */
    public String relationalColumnName() {
        return "f_" + id;
    }
}
