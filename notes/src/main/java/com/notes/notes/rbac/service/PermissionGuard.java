package com.notes.notes.rbac.service;

import com.notes.notes.rbac.entity.PermissionScope;
import com.notes.notes.security.services.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * One place every module (system or custom form) calls to enforce access on a single
 * record, instead of each service hand-rolling its own "is this ALL or is it mine"
 * check. Callers only ever supply: which resource (module/form key), which action,
 * and who owns the specific record (null when the action isn't record-scoped, e.g.
 * CREATE or a module-wide VIEW/EXPORT). Everything else - who the current user is,
 * what scope they hold, whether SUPER_ADMIN's wildcard applies - lives in
 * {@link AuthorizationService} and is resolved once here.
 */
@Component
@RequiredArgsConstructor
public class PermissionGuard {

    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;

    /** True if the current user may perform actionKey on resKey for this specific record. */
    public boolean allows(String resKey, String actionKey, Long ownerId) {
        Long userId = currentUserProvider.currentUserId();
        if (userId == null) {
            return false;
        }
        PermissionScope scope = authorizationService.scopeFor(userId, resKey, actionKey);
        if (scope == PermissionScope.ALL) {
            return true;
        }
        return scope == PermissionScope.OWN && ownerId != null && ownerId.equals(userId);
    }

    /** Same as {@link #allows}, but throws instead of returning false - for service-layer enforcement. */
    public void require(String resKey, String actionKey, Long ownerId) {
        if (!allows(resKey, actionKey, ownerId)) {
            throw new AccessDeniedException(
                    "Not permitted: " + actionKey + " on " + resKey
                            + (ownerId != null ? " (not your record)" : ""));
        }
    }

    public void requireView(String resKey, Long ownerId) {
        require(resKey, "VIEW", ownerId);
    }

    public void requireCreate(String resKey) {
        require(resKey, "CREATE", null);
    }

    public void requireEdit(String resKey, Long ownerId) {
        require(resKey, "EDIT", ownerId);
    }

    public void requireDelete(String resKey, Long ownerId) {
        require(resKey, "DELETE", ownerId);
    }

    public void requireExport(String resKey) {
        require(resKey, "EXPORT", null);
    }
}
