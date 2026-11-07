package com.notes.notes.rbac.repository;

import com.notes.notes.rbac.entity.RbacResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RbacResourceRepository extends JpaRepository<RbacResource, Long> {
    Optional<RbacResource> findByResKey(String resKey);
    boolean existsByResKey(String resKey);
}
