package com.notes.notes.rbac.repository;

import com.notes.notes.rbac.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, com.notes.notes.rbac.entity.UserRoleId> {
    List<UserRole> findByUser_UserId(Long userId);
    void deleteByUser_UserId(Long userId);
}
