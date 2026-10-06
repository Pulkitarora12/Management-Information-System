package com.notes.notes.repository.hrRepo;

import com.notes.notes.entity.hrEntities.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByPhoneNumber(String phoneNumber);
}
