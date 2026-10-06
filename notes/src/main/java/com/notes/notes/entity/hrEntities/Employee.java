package com.notes.notes.entity.hrEntities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "employees")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is mandatory")
    @Column(nullable = false)
    private String name;

    @Column(name = "father_name")
    private String fatherName;

    @Column(name = "employee_code", unique = true)
    private String employeeCode;

    @Column(name = "contractor_name")
    private String contractorName;

    @NotBlank(message = "Phone number is mandatory")
    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @NotBlank(message = "Gender is mandatory")
    @Column(nullable = false)
    private String gender;

    @NotBlank(message = "Address is mandatory")
    @Column(nullable = false, length = 500)
    private String address;

    private String email;

    private String roles;

    private Double salary;

    @Column(name = "bank_account")
    private String bankAccount;

    @Column(name = "esi_number")
    private String esiNumber;

    @Column(name = "psi_number")
    private String psiNumber;

    @Column(name = "cheque_path")
    private String chequePath;

    @Column(name = "photo_path")
    private String photoPath;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdDate;

    @UpdateTimestamp
    private LocalDateTime updatedDate;
}
