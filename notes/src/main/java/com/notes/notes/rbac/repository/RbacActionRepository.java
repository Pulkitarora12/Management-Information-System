package com.notes.notes.rbac.repository;

import com.notes.notes.rbac.entity.RbacAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RbacActionRepository extends JpaRepository<RbacAction, Long> {
    Optional<RbacAction> findByActionKey(String actionKey);
    List<RbacAction> findAllByOrderByIdAsc();
}
