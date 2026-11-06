package com.notes.notes.rbac;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.rbac.entity.RbacRole;
import com.notes.notes.rbac.repository.RbacRoleRepository;
import com.notes.notes.rbac.service.AuthorizationService;
import com.notes.notes.rbac.service.UserRoleAssignmentService;
import com.notes.notes.repository.authRepo.RoleRepository;
import com.notes.notes.repository.authRepo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the six checks from MIS_CONTEXT.md section 2.1 keep their pre-RBAC behaviour:
 * a STANDARD_USER cannot delete production/RMIR/master entries, a SUPER_ADMIN can.
 * Runs against mis_test (never the real mis DB); @Transactional rolls back every write.
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

    @Test
    void standardUserCannotDeleteProductionRmirOrMasters() {
        User user = persistUser("std-user-1", "std-user-1@example.com");
        RbacRole standardUser = rbacRoleRepository.findByRoleKey("STANDARD_USER").orElseThrow();
        userRoleAssignmentService.setRolesForUser(user.getUserId(), Set.of(standardUser.getId()));

        assertThat(authorizationService.can(user.getUserId(), "PRODUCTION", "DELETE")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "RMIR", "DELETE")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "MASTERS", "DELETE")).isFalse();

        // but keeps the rest of the access that was open to every authenticated user before RBAC
        assertThat(authorizationService.can(user.getUserId(), "PRODUCTION", "VIEW")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "PRODUCTION", "CREATE")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "TASK_TEMPLATES", "ADMIN")).isFalse();
    }

    @Test
    void superAdminCanDeleteProductionRmirAndMasters() {
        User user = persistUser("super-admin-1", "super-admin-1@example.com");
        RbacRole superAdmin = rbacRoleRepository.findByRoleKey(AuthorizationService.SUPER_ADMIN_ROLE_KEY).orElseThrow();
        userRoleAssignmentService.setRolesForUser(user.getUserId(), Set.of(superAdmin.getId()));

        assertThat(authorizationService.can(user.getUserId(), "PRODUCTION", "DELETE")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "RMIR", "DELETE")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "MASTERS", "DELETE")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "USERS", "ADMIN")).isTrue();
        assertThat(authorizationService.can(user.getUserId(), "TASK_TEMPLATES", "ADMIN")).isTrue();
    }

    @Test
    void standardUserCannotReachAdminArea() {
        User user = persistUser("std-user-2", "std-user-2@example.com");
        RbacRole standardUser = rbacRoleRepository.findByRoleKey("STANDARD_USER").orElseThrow();
        userRoleAssignmentService.setRolesForUser(user.getUserId(), Set.of(standardUser.getId()));

        assertThat(authorizationService.can(user.getUserId(), "USERS", "ADMIN")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "ROLES", "ADMIN")).isFalse();
        assertThat(authorizationService.can(user.getUserId(), "FORM_BUILDER", "ADMIN")).isFalse();
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
