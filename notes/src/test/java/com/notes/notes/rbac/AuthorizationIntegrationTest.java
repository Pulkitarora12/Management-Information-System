package com.notes.notes.rbac;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.rbac.entity.PermissionScope;
import com.notes.notes.rbac.entity.RbacRole;
import com.notes.notes.rbac.repository.RbacRoleRepository;
import com.notes.notes.rbac.service.AuthorizationService;
import com.notes.notes.rbac.service.PermissionGuard;
import com.notes.notes.rbac.service.UserRoleAssignmentService;
import com.notes.notes.repository.authRepo.RoleRepository;
import com.notes.notes.repository.authRepo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the current access model from MIS_CONTEXT.md section 4 (as refined after
 * Phase 1 review): STANDARD_USER sees all Production/RMIR records, can create freely,
 * but can only edit/delete records they created (PermissionGuard, OWN scope); has no
 * access to Masters, Task Templates, Export, or the admin area at all; SUPER_ADMIN is
 * unrestricted everywhere via the wildcard. Runs against mis_test; @Transactional
 * rolls back every write.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthorizationIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository legacyRoleRepository;

    @Autowired
    private RbacRoleRepository rbacRoleRepository;

    @Autowired
    private UserRoleAssignmentService userRoleAssignmentService;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private PermissionGuard permissionGuard;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void standardUserHasOwnScopeDeleteOnProductionAndRmir() {
        User user = persistUser("std-user-1", "std-user-1@example.com");
        assignRole(user, "STANDARD_USER");

        assertThat(authorizationService.scopeFor(user.getUserId(), "PRODUCTION", "DELETE"))
                .isEqualTo(PermissionScope.OWN);
        assertThat(authorizationService.scopeFor(user.getUserId(), "RMIR", "DELETE"))
                .isEqualTo(PermissionScope.OWN);

        // sees everything, can create freely
        assertThat(authorizationService.can(user.getUserId(), "PRODUCTION", "VIEW")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "PRODUCTION", "CREATE")).isTrue();

        // no access at all to Masters, Task Templates, or Export anywhere
        assertThat(authorizationService.can(user.getUserId(), "MASTERS", "VIEW")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "MASTERS", "DELETE")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "TASK_TEMPLATES", "VIEW")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "PRODUCTION", "EXPORT")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "RMIR", "EXPORT")).isFalse();
    }

    @Test
    void permissionGuardBlocksDeletingSomeoneElsesRecordButAllowsOwnRecord() {
        User owner = persistUser("owner-1", "owner-1@example.com");
        User otherStandardUser = persistUser("other-1", "other-1@example.com");
        assignRole(owner, "STANDARD_USER");
        assignRole(otherStandardUser, "STANDARD_USER");

        actingAs(otherStandardUser);
        assertThatThrownBy(() -> permissionGuard.requireDelete("PRODUCTION", owner.getUserId()))
                .isInstanceOf(AccessDeniedException.class);

        actingAs(owner);
        permissionGuard.requireDelete("PRODUCTION", owner.getUserId()); // does not throw

        clearActingAs();
    }

    @Test
    void permissionGuardAllowsSuperAdminToDeleteAnyRecord() {
        User superAdminUser = persistUser("super-admin-2", "super-admin-2@example.com");
        assignRole(superAdminUser, AuthorizationService.SUPER_ADMIN_ROLE_KEY);
        User someoneElse = persistUser("someone-else-1", "someone-else-1@example.com");

        actingAs(superAdminUser);
        permissionGuard.requireDelete("PRODUCTION", someoneElse.getUserId()); // does not throw
        permissionGuard.requireDelete("MASTERS", null); // SUPER_ADMIN wildcard, no owner needed

        clearActingAs();
    }

    @Test
    void permissionGuardBlocksLegacyRecordsWithNoRecordedOwnerUnderOwnScope() {
        User user = persistUser("std-user-3", "std-user-3@example.com");
        assignRole(user, "STANDARD_USER");

        actingAs(user);
        // ownerId == null simulates a pre-ownership-tracking row (V3 migration note)
        assertThatThrownBy(() -> permissionGuard.requireDelete("PRODUCTION", null))
                .isInstanceOf(AccessDeniedException.class);

        clearActingAs();
    }

    @Test
    void superAdminCanDeleteProductionRmirAndMasters() {
        User user = persistUser("super-admin-1", "super-admin-1@example.com");
        assignRole(user, AuthorizationService.SUPER_ADMIN_ROLE_KEY);

        assertThat(authorizationService.can(user.getUserId(), "PRODUCTION", "DELETE")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "RMIR", "DELETE")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "MASTERS", "DELETE")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "USERS", "ADMIN")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "TASK_TEMPLATES", "ADMIN")).isTrue();
    }

    @Test
    void standardUserCannotReachAdminArea() {
        User user = persistUser("std-user-2", "std-user-2@example.com");
        assignRole(user, "STANDARD_USER");

        assertThat(authorizationService.can(user.getUserId(), "USERS", "ADMIN")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "ROLES", "ADMIN")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "FORM_BUILDER", "ADMIN")).isFalse();
    }

    private void assignRole(User user, String roleKey) {
        RbacRole role = rbacRoleRepository.findByRoleKey(roleKey).orElseThrow();
        userRoleAssignmentService.setRolesForUser(user.getUserId(), Set.of(role.getId()));
    }

    /** PermissionGuard reads the acting user from SecurityContextHolder, same as in a real request. */
    private void actingAs(User user) {
        var principal = com.notes.notes.security.services.UserDetailsImpl.build(
                user, List.of(), List.of());
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(authentication));
    }

    private void clearActingAs() {
        SecurityContextHolder.clearContext();
    }

    private User persistUser(String username, String email) {
        Role legacyUserRole = legacyRoleRepository.findByRoleName(AppRole.ROLE_USER)
                .orElseThrow(() -> new IllegalStateException("Legacy ROLE_USER not seeded"));

        User user = new User();
        user.setUserName(username);
        user.setEmail(email);
        user.setPassword("test-password-hash");
        user.setEnabled(true);
        user.setVerified(true);
        user.setAccountNonLocked(true);
        user.setAccountNonExpired(true);
        user.setCredentialsNonExpired(true);
        user.setRole(legacyUserRole);
        return userRepository.save(user);
    }
}
