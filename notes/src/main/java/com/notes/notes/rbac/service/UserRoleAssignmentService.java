package com.notes.notes.rbac.service;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.rbac.entity.RbacRole;
import com.notes.notes.rbac.entity.UserRole;
import com.notes.notes.rbac.repository.RbacRoleRepository;
import com.notes.notes.rbac.repository.UserRoleRepository;
import com.notes.notes.repository.authRepo.RoleRepository;
import com.notes.notes.repository.authRepo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Assigns dynamic app_role rows to a user (D2 user_role), and keeps the legacy
 * single-role users.role_id column in sync (D5: kept for now, not dropped).
 * Legacy role mirrors the new roles: SUPER_ADMIN present -> ROLE_ADMIN, else ROLE_USER.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserRoleAssignmentService {

    private final UserRepository userRepository;
    private final RoleRepository legacyRoleRepository;
    private final RbacRoleRepository rbacRoleRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuthorizationService authorizationService;

    public List<RbacRole> getRolesForUser(Long userId) {
        return userRoleRepository.findByUser_UserId(userId).stream().map(UserRole::getRole).toList();
    }

    public void setRolesForUser(Long userId, Set<Long> roleIds) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        userRoleRepository.deleteByUser_UserId(userId);
        boolean superAdmin = false;
        for (Long roleId : roleIds) {
            RbacRole role = rbacRoleRepository.findById(roleId)
                    .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
            userRoleRepository.save(new UserRole(user, role));
            if (AuthorizationService.SUPER_ADMIN_ROLE_KEY.equals(role.getRoleKey())) {
                superAdmin = true;
            }
        }

        AppRole legacyRoleName = superAdmin ? AppRole.ROLE_ADMIN : AppRole.ROLE_USER;
        Role legacyRole = legacyRoleRepository.findByRoleName(legacyRoleName)
                .orElseThrow(() -> new IllegalStateException("Legacy role not seeded: " + legacyRoleName));
        user.setRole(legacyRole);
        userRepository.save(user);

        authorizationService.invalidateUser(userId);
    }

    /** Called from the legacy single-role flows (signup, old update-role) to keep user_role in sync. */
    public void syncFromLegacyRole(User user) {
        if (user.getRole() == null) {
            return;
        }
        String targetRoleKey = user.getRole().getRoleName() == AppRole.ROLE_ADMIN
                ? AuthorizationService.SUPER_ADMIN_ROLE_KEY
                : "STANDARD_USER";
        RbacRole target = rbacRoleRepository.findByRoleKey(targetRoleKey)
                .orElseThrow(() -> new IllegalStateException("RBAC role not seeded: " + targetRoleKey));

        userRoleRepository.deleteByUser_UserId(user.getUserId());
        userRoleRepository.save(new UserRole(user, target));
        authorizationService.invalidateUser(user.getUserId());
    }
}
