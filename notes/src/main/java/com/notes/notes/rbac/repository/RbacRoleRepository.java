package com.notes.notes.rbac.repository;

import com.notes.notes.rbac.entity.RbacRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RbacRoleRepository extends JpaRepository<RbacRole, Long> {
    Optional<RbacRole> findByRoleKey(String roleKey);
    boolean existsByRoleKey(String roleKey);
}
