package com.notes.notes.rbac.repository;

import com.notes.notes.rbac.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, com.notes.notes.rbac.entity.RolePermissionId> {
    List<RolePermission> findByRole_Id(Long roleId);
    List<RolePermission> findByRole_IdIn(List<Long> roleIds);
    void deleteByRole_Id(Long roleId);
}
