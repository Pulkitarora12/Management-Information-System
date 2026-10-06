package com.notes.notes.repository.authRepo;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role,Long> {
    Optional<Role> findByRoleName(AppRole roleName);

}
