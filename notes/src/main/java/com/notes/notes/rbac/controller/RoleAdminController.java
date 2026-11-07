package com.notes.notes.rbac.controller;

import com.notes.notes.rbac.entity.PermissionScope;
import com.notes.notes.rbac.entity.RbacRole;
import com.notes.notes.rbac.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin UI for D6/D2: roles list/create/edit with a resource x action permission grid.
 * Reachable because SecurityConfig's /admin/** rule allows @authz.can('ROLES','ADMIN');
 * gated again here at the controller level for defense in depth.
 */
@Controller
@RequestMapping("/admin/roles")
@PreAuthorize("@authz.can('ROLES','ADMIN')")
@RequiredArgsConstructor
public class RoleAdminController {

    private final RoleService roleService;

    @GetMapping
    public String listRoles(Model model) {
        model.addAttribute("roles", roleService.listRoles());
        return "auth/RolesList";
    }

    @GetMapping("/new")
    public String newRoleForm() {
        return "auth/RoleForm";
    }

    @PostMapping("/new")
    public String createRole(@RequestParam String roleKey, @RequestParam String name,
                              @RequestParam(required = false) String description) {
        RbacRole role = roleService.createRole(roleKey.trim().toUpperCase().replace(' ', '_'), name, description);
        return "redirect:/admin/roles/" + role.getId() + "/permissions";
    }

    @GetMapping("/{id}/permissions")
    public String editPermissions(@PathVariable Long id, Model model) {
        RbacRole role = roleService.getRole(id);
        model.addAttribute("role", role);
        model.addAttribute("grid", roleService.getPermissionGrid(id));
        return "auth/RolePermissions";
    }

    @PostMapping("/{id}/permissions")
    public String savePermissions(@PathVariable Long id, @RequestParam Map<String, String> form) {
        Map<Long, PermissionScope> grants = new HashMap<>();
        form.forEach((key, value) -> {
            if (key.startsWith("perm_")) {
                Long permissionId = Long.valueOf(key.substring("perm_".length()));
                PermissionScope scope = (value == null || value.isBlank())
                        ? PermissionScope.NONE
                        : PermissionScope.valueOf(value);
                grants.put(permissionId, scope);
            }
        });
        roleService.savePermissionGrid(id, grants);
        return "redirect:/admin/roles/" + id + "/permissions?success";
    }
}
