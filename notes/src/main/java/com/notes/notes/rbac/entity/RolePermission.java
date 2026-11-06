package com.notes.notes.rbac.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@Table(name = "role_permission")
public class RolePermission {

    @EmbeddedId
    private RolePermissionId id = new RolePermissionId();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("roleId")
    @JoinColumn(name = "role_id")
    private RbacRole role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("permissionId")
    @JoinColumn(name = "permission_id")
    private RbacPermission permission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PermissionScope scope = PermissionScope.ALL;

    public RolePermission(RbacRole role, RbacPermission permission, PermissionScope scope) {
        this.role = role;
        this.permission = permission;
        this.scope = scope;
        this.id = new RolePermissionId(role.getId(), permission.getId());
    }
}
