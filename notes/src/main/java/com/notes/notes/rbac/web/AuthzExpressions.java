package com.notes.notes.rbac.web;

import com.notes.notes.rbac.entity.PermissionScope;
import com.notes.notes.rbac.service.AuthorizationService;
import com.notes.notes.rbac.service.PermissionGuard;
import com.notes.notes.security.services.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Bean named "authz": the single entry point used from @PreAuthorize and
 * Thymeleaf sec:authorize (both support "@authz.method(...)" SpEL).
 */
@Component("authz")
@RequiredArgsConstructor
public class AuthzExpressions {

    private final AuthorizationService authorizationService;
    private final PermissionGuard permissionGuard;

    /** Coarse check: does this user hold actionKey on resKey at all (ALL or OWN)? */
    public boolean can(String resKey, String actionKey) {
        Long userId = currentUserId();
        return userId != null && authorizationService.can(userId, resKey, actionKey);
    }

    public String scope(String resKey, String actionKey) {
        Long userId = currentUserId();
        if (userId == null) {
            return PermissionScope.NONE.name();
        }
        return authorizationService.scopeFor(userId, resKey, actionKey).name();
    }

    public boolean isOwnScope(String resKey, String actionKey) {
        Long userId = currentUserId();
        return userId != null && authorizationService.scopeFor(userId, resKey, actionKey) == PermissionScope.OWN;
    }

    /**
     * Fine check for per-row UI (e.g. a Delete link in a history table): true if ALL,
     * or OWN and {@code ownerId} is them. Same rule {@link PermissionGuard} enforces
     * server-side - this is its read-only twin for deciding what to render.
     */
    public boolean canOwn(String resKey, String actionKey, Long ownerId) {
        return permissionGuard.allows(resKey, actionKey, ownerId);
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl principal)) {
            return null;
        }
        return principal.getId();
    }
}
