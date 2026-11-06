package com.notes.notes.rbac.web;

import com.notes.notes.rbac.entity.PermissionScope;
import com.notes.notes.rbac.service.AuthorizationService;
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

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl principal)) {
            return null;
        }
        return principal.getId();
    }
}
