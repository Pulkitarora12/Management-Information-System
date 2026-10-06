package com.notes.notes.repository.authRepo;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.*;


@Repository
public interface UserRepository extends JpaRepository<User,Long> {
    boolean existsByUserName(String username);

    boolean existsByEmail(String email);

    Optional<User> findByUserName(String username);

    Optional<User> findByEmail(String adminEmail);

    List<User> findByRole_RoleName(AppRole roleName);
}
