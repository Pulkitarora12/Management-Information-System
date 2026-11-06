package com.notes.notes.rbac.repository;

import com.notes.notes.rbac.entity.RbacPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RbacPermissionRepository extends JpaRepository<RbacPermission, Long> {
    List<RbacPermission> findByResourceId(Long resourceId);
    Optional<RbacPermission> findByResourceIdAndActionId(Long resourceId, Long actionId);
}
