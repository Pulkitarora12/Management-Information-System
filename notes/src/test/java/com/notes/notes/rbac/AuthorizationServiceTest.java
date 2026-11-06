package com.notes.notes.rbac;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.rbac.entity.*;
import com.notes.notes.rbac.repository.RolePermissionRepository;
import com.notes.notes.rbac.repository.UserRoleRepository;
import com.notes.notes.rbac.service.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    private AuthorizationService authorizationService;

    private RbacResource production;
    private RbacAction view;
    private RbacAction delete;
    private RbacAction admin;

    @BeforeEach
    void setUp() {
        authorizationService = new AuthorizationService(userRoleRepository, rolePermissionRepository);

        production = resource(1L, "PRODUCTION");
        view = action(1L, "VIEW");
        delete = action(2L, "DELETE");
        admin = action(3L, "ADMIN");
    }

    @Test
    void standardUserGetsExactlyTheGrantedActions() {
        Long userId = 10L;
        RbacRole standardUser = role(1L, "STANDARD_USER");
        stubUserRoles(userId, standardUser);

        RbacPermission viewPermission = permission(100L, production, view);
        stubRolePermissions(standardUser, List.of(
                rolePermission(standardUser, viewPermission, PermissionScope.ALL)
        ));

        assertThat(authorizationService.can(userId, "PRODUCTION", "VIEW")).isTrue();
        assertThat(authorizationService.can(userId, "PRODUCTION", "DELETE")).isFalse();
        assertThat(authorizationService.scopeFor(userId, "PRODUCTION", "VIEW")).isEqualTo(PermissionScope.ALL);
        assertThat(authorizationService.scopeFor(userId, "PRODUCTION", "DELETE")).isEqualTo(PermissionScope.NONE);
    }

    @Test
    void adminActionImpliesEveryOtherActionOnThatResource() {
        Long userId = 11L;
        RbacRole roleWithAdmin = role(2L, "RESOURCE_ADMIN");
        stubUserRoles(userId, roleWithAdmin);

        RbacPermission adminPermission = permission(101L, production, admin);
        stubRolePermissions(roleWithAdmin, List.of(
                rolePermission(roleWithAdmin, adminPermission, PermissionScope.ALL)
        ));

        assertThat(authorizationService.can(userId, "PRODUCTION", "VIEW")).isTrue();
        assertThat(authorizationService.can(userId, "PRODUCTION", "DELETE")).isTrue();
        assertThat(authorizationService.can(userId, "PRODUCTION", "ADMIN")).isTrue();
    }

    @Test
    void superAdminWildcardCoversResourcesWithNoPermissionRowsAtAll() {
        Long userId = 12L;
        RbacRole superAdmin = role(3L, AuthorizationService.SUPER_ADMIN_ROLE_KEY);
        stubUserRoles(userId, superAdmin);

        // A resource that doesn't exist in app_permission yet (e.g. a form created after seeding).
        assertThat(authorizationService.can(userId, "FORM_999", "DELETE")).isTrue();
        assertThat(authorizationService.scopeFor(userId, "ANYTHING", "ANYTHING")).isEqualTo(PermissionScope.ALL);
        verifyNoInteractions(rolePermissionRepository);
    }

    @Test
    void ownScopeIsReturnedAsIs() {
        Long userId = 13L;
        RbacRole roleWithOwnScope = role(4L, "OWN_SCOPE_ROLE");
        stubUserRoles(userId, roleWithOwnScope);

        RbacPermission viewPermission = permission(102L, production, view);
        stubRolePermissions(roleWithOwnScope, List.of(
                rolePermission(roleWithOwnScope, viewPermission, PermissionScope.OWN)
        ));

        assertThat(authorizationService.scopeFor(userId, "PRODUCTION", "VIEW")).isEqualTo(PermissionScope.OWN);
    }

    @Test
    void userWithNoRolesAtAllHasNoGrants() {
        Long userId = 14L;
        when(userRoleRepository.findByUser_UserId(userId)).thenReturn(List.of());

        assertThat(authorizationService.can(userId, "PRODUCTION", "VIEW")).isFalse();
        assertThat(authorizationService.scopeFor(userId, "PRODUCTION", "VIEW")).isEqualTo(PermissionScope.NONE);
    }

    @Test
    void resultIsCachedUntilInvalidated() {
        Long userId = 15L;
        RbacRole standardUser = role(5L, "STANDARD_USER");
        stubUserRoles(userId, standardUser);
        RbacPermission viewPermission = permission(103L, production, view);
        stubRolePermissions(standardUser, List.of(
                rolePermission(standardUser, viewPermission, PermissionScope.ALL)
        ));

        authorizationService.can(userId, "PRODUCTION", "VIEW");
        authorizationService.can(userId, "PRODUCTION", "VIEW");
        verify(userRoleRepository, times(1)).findByUser_UserId(userId);

        authorizationService.invalidateUser(userId);
        authorizationService.can(userId, "PRODUCTION", "VIEW");
        verify(userRoleRepository, times(2)).findByUser_UserId(userId);
    }

    @Test
    void invalidateAllClearsEveryCachedUser() {
        Long userId = 16L;
        RbacRole standardUser = role(6L, "STANDARD_USER");
        stubUserRoles(userId, standardUser);
        RbacPermission viewPermission = permission(104L, production, view);
        stubRolePermissions(standardUser, List.of(
                rolePermission(standardUser, viewPermission, PermissionScope.ALL)
        ));

        authorizationService.can(userId, "PRODUCTION", "VIEW");
        authorizationService.invalidateAll();
        authorizationService.can(userId, "PRODUCTION", "VIEW");

        verify(userRoleRepository, times(2)).findByUser_UserId(userId);
    }

    private void stubUserRoles(Long userId, RbacRole role) {
        User user = new User();
        user.setUserId(userId);
        UserRole userRole = new UserRole();
        userRole.setUser(user);
        userRole.setRole(role);
        when(userRoleRepository.findByUser_UserId(userId)).thenReturn(List.of(userRole));
    }

    private void stubRolePermissions(RbacRole role, List<RolePermission> permissions) {
        when(rolePermissionRepository.findByRole_IdIn(List.of(role.getId()))).thenReturn(permissions);
    }

    private static RbacResource resource(Long id, String key) {
        RbacResource r = new RbacResource();
        r.setId(id);
        r.setResKey(key);
        r.setName(key);
        return r;
    }

    private static RbacAction action(Long id, String key) {
        RbacAction a = new RbacAction();
        a.setId(id);
        a.setActionKey(key);
        a.setName(key);
        return a;
    }

    private static RbacRole role(Long id, String key) {
        RbacRole r = new RbacRole();
        r.setId(id);
        r.setRoleKey(key);
        r.setName(key);
        return r;
    }

    private static RbacPermission permission(Long id, RbacResource resource, RbacAction action) {
        RbacPermission p = new RbacPermission();
        p.setId(id);
        p.setResource(resource);
        p.setAction(action);
        return p;
    }

    private static RolePermission rolePermission(RbacRole role, RbacPermission permission, PermissionScope scope) {
        RolePermission rp = new RolePermission();
        rp.setRole(role);
        rp.setPermission(permission);
        rp.setScope(scope);
        return rp;
    }
}
