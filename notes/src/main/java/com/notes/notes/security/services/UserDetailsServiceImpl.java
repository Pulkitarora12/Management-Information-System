package com.notes.notes.security.services;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.rbac.entity.RbacRole;
import com.notes.notes.rbac.service.AuthorizationService;
import com.notes.notes.rbac.service.UserRoleAssignmentService;
import com.notes.notes.repository.authRepo.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    @Autowired
    UserRepository userRepository;

    @Autowired
    UserRoleAssignmentService userRoleAssignmentService;

    @Autowired
    AuthorizationService authorizationService;

    @Override
    @Transactional
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUserName(username)
                .orElseThrow(() -> new UsernameNotFoundException("User Not Found with username: " + username));

        List<String> dynamicRoleKeys = userRoleAssignmentService.getRolesForUser(user.getUserId())
                .stream().map(RbacRole::getRoleKey).toList();
        var resourceActionGrants = authorizationService.grantedAuthoritiesFor(user.getUserId());

        return UserDetailsImpl.build(user, dynamicRoleKeys, resourceActionGrants);
    }

}
