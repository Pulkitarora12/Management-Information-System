package com.notes.notes.security.services;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Single place to read who's making the current request. The id comes straight off
 * the authenticated principal already held in memory for this request - no repository
 * call needed just to find out "who am I", so callers (services doing ownership
 * checks, @authz.can() etc.) never need to re-fetch the User entity just for its id.
 */
@Component
public class CurrentUserProvider {

    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl principal)) {
            return null;
        }
        return principal.getId();
    }

    public boolean isCurrentUser(Long userId) {
        Long currentUserId = currentUserId();
        return currentUserId != null && currentUserId.equals(userId);
    }
}
