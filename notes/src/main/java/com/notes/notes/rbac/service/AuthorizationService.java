package com.notes.notes.rbac.service;

import com.notes.notes.rbac.entity.PermissionScope;
import com.notes.notes.rbac.entity.RolePermission;
import com.notes.notes.rbac.entity.UserRole;
import com.notes.notes.rbac.repository.RolePermissionRepository;
import com.notes.notes.rbac.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for "can this user do this action on this resource".
 * Permissions are resolved per user and cached (D2); the cache is evicted in full
 * whenever any role, permission or user-role assignment changes, since those are
 * rare, admin-driven writes.
 *
 * SUPER_ADMIN is a wildcard (D4/D2): it is not expressed as rows in app_permission for
 * every possible resource, because resources (custom forms) can be created after the
 * role was seeded. A user holding SUPER_ADMIN is authorized for anything, including
 * resources that do not exist yet when this check runs.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    public static final String SUPER_ADMIN_ROLE_KEY = "SUPER_ADMIN";

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    private final Map<Long, UserGrants> cache = new ConcurrentHashMap<>();

    public boolean can(Long userId, String resKey, String actionKey) {
        if (userId == null || resKey == null || actionKey == null) {
            return false;
        }
        return scopeFor(userId, resKey, actionKey) != PermissionScope.NONE;
    }

    public PermissionScope scopeFor(Long userId, String resKey, String actionKey) {
        if (userId == null || resKey == null || actionKey == null) {
            return PermissionScope.NONE;
        }
        UserGrants grants = cache.computeIfAbsent(userId, this::loadGrants);
        if (grants.superAdmin()) {
            return PermissionScope.ALL;
        }
        Map<String, PermissionScope> resourceGrants = grants.grantsByResource().get(resKey);
        if (resourceGrants == null) {
            return PermissionScope.NONE;
        }
        PermissionScope scope = resourceGrants.get(actionKey);
        return scope == null ? PermissionScope.NONE : scope;
    }

    /**
     * "RES:ACTION" strings for every grant this user holds, for loading into
     * UserDetailsImpl (D2 step 4). Empty for SUPER_ADMIN: enumerating every current
     * resource would still miss resources created later, and @authz.can() already
     * grants SUPER_ADMIN access independent of authorities.
     */
    public Set<String> grantedAuthoritiesFor(Long userId) {
        UserGrants grants = cache.computeIfAbsent(userId, this::loadGrants);
        if (grants.superAdmin()) {
            return Set.of();
        }
        Set<String> authorities = new HashSet<>();
        grants.grantsByResource().forEach((resKey, actions) ->
                actions.keySet().forEach(actionKey -> authorities.add(resKey + ":" + actionKey)));
        return authorities;
    }

    public void invalidateAll() {
        cache.clear();
    }

    public void invalidateUser(Long userId) {
        cache.remove(userId);
    }

    private UserGrants loadGrants(Long userId) {
        List<UserRole> userRoles = userRoleRepository.findByUser_UserId(userId);
        if (userRoles.isEmpty()) {
            return new UserGrants(false, Map.of());
        }

        boolean superAdmin = userRoles.stream()
                .anyMatch(ur -> SUPER_ADMIN_ROLE_KEY.equals(ur.getRole().getRoleKey()));
        if (superAdmin) {
            return new UserGrants(true, Map.of());
        }

        List<Long> roleIds = userRoles.stream().map(ur -> ur.getRole().getId()).toList();
        List<RolePermission> rolePermissions = rolePermissionRepository.findByRole_IdIn(roleIds);

        // Two passes: ADMIN grants on a resource first (they imply every action on it),
        // then specific-action grants fill in anything ADMIN didn't already cover.
        Map<String, Map<String, PermissionScope>> byResource = new HashMap<>();
        Map<String, PermissionScope> adminGrantByResource = new HashMap<>();

        for (RolePermission rp : rolePermissions) {
            String resKey = rp.getPermission().getResource().getResKey();
            String actionKey = rp.getPermission().getAction().getActionKey();
            if ("ADMIN".equals(actionKey)) {
                adminGrantByResource.merge(resKey, rp.getScope(), AuthorizationService::widerScope);
            }
        }

        for (RolePermission rp : rolePermissions) {
            String resKey = rp.getPermission().getResource().getResKey();
            String actionKey = rp.getPermission().getAction().getActionKey();
            Map<String, PermissionScope> actionGrants = byResource.computeIfAbsent(resKey, k -> new HashMap<>());
            actionGrants.merge(actionKey, rp.getScope(), AuthorizationService::widerScope);
        }

        for (Map.Entry<String, PermissionScope> entry : adminGrantByResource.entrySet()) {
            String resKey = entry.getKey();
            PermissionScope adminScope = entry.getValue();
            Map<String, PermissionScope> actionGrants = byResource.computeIfAbsent(resKey, k -> new HashMap<>());
            for (String actionKey : List.of("VIEW", "CREATE", "EDIT", "DELETE", "EXPORT", "ADMIN")) {
                actionGrants.merge(actionKey, adminScope, AuthorizationService::widerScope);
            }
        }

        return new UserGrants(false, byResource);
    }

    private static PermissionScope widerScope(PermissionScope a, PermissionScope b) {
        return a == PermissionScope.ALL || b == PermissionScope.ALL ? PermissionScope.ALL : PermissionScope.OWN;
    }

    private record UserGrants(boolean superAdmin, Map<String, Map<String, PermissionScope>> grantsByResource) {
    }
}
