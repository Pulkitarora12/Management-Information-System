package com.notes.notes.rbac.service;

import com.notes.notes.rbac.entity.*;
import com.notes.notes.rbac.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class RoleService {

    private final RbacRoleRepository roleRepository;
    private final RbacResourceRepository resourceRepository;
    private final RbacActionRepository actionRepository;
    private final RbacPermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final AuthorizationService authorizationService;

    public List<RbacRole> listRoles() {
        return roleRepository.findAll();
    }

    public RbacRole getRole(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + id));
    }

    public RbacRole createRole(String roleKey, String name, String description) {
        if (roleRepository.existsByRoleKey(roleKey)) {
            throw new IllegalArgumentException("Role key already exists: " + roleKey);
        }
        RbacRole role = new RbacRole();
        role.setRoleKey(roleKey);
        role.setName(name);
        role.setDescription(description);
        role.setSystemRole(false);
        return roleRepository.save(role);
    }

    public void updateRoleDetails(Long roleId, String name, String description) {
        RbacRole role = getRole(roleId);
        role.setName(name);
        role.setDescription(description);
        roleRepository.save(role);
    }

    /** resources x actions, with the cell value this role currently grants (or null = none). */
    public PermissionGrid getPermissionGrid(Long roleId) {
        List<RbacResource> resources = resourceRepository.findAll().stream()
                .filter(RbacResource::isActive).toList();
        List<RbacAction> actions = actionRepository.findAllByOrderByIdAsc();

        Map<Long, PermissionScope> scopeByPermissionId = rolePermissionRepository.findByRole_Id(roleId).stream()
                .collect(java.util.stream.Collectors.toMap(rp -> rp.getPermission().getId(), RolePermission::getScope));

        List<PermissionGrid.Row> rows = resources.stream().map(resource -> {
            List<PermissionGrid.Cell> cells = actions.stream().map(action -> {
                RbacPermission permission = permissionRepository
                        .findByResourceIdAndActionId(resource.getId(), action.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing permission row for " + resource.getResKey() + ":" + action.getActionKey()));
                PermissionScope scope = scopeByPermissionId.getOrDefault(permission.getId(), PermissionScope.NONE);
                return new PermissionGrid.Cell(permission.getId(), action.getActionKey(), action.getName(), scope);
            }).toList();
            return new PermissionGrid.Row(resource.getId(), resource.getResKey(), resource.getName(), cells);
        }).toList();

        return new PermissionGrid(rows);
    }

    /**
     * Replaces every grant for this role with exactly the cells supplied. A cell absent
     * from {@code grants} (or set to null) means "no access" for that resource/action.
     */
    public void savePermissionGrid(Long roleId, Map<Long, PermissionScope> grantsByPermissionId) {
        RbacRole role = getRole(roleId);
        rolePermissionRepository.deleteByRole_Id(roleId);
        for (Map.Entry<Long, PermissionScope> entry : grantsByPermissionId.entrySet()) {
            if (entry.getValue() == null || entry.getValue() == PermissionScope.NONE) {
                continue;
            }
            RbacPermission permission = permissionRepository.findById(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown permission: " + entry.getKey()));
            rolePermissionRepository.save(new RolePermission(role, permission, entry.getValue()));
        }
        authorizationService.invalidateAll();
    }

    public Optional<RbacRole> findByKey(String roleKey) {
        return roleRepository.findByRoleKey(roleKey);
    }

    public record PermissionGrid(List<Row> rows) {
        public record Row(Long resourceId, String resKey, String resourceName, List<Cell> cells) {
        }

        public record Cell(Long permissionId, String actionKey, String actionName, PermissionScope scope) {
        }
    }
}
