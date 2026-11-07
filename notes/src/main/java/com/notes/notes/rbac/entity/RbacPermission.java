package com.notes.notes.rbac.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@Table(name = "app_permission", uniqueConstraints = @UniqueConstraint(columnNames = {"resource_id", "action_id"}))
public class RbacPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id")
    private RbacResource resource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id")
    private RbacAction action;

    public RbacPermission(RbacResource resource, RbacAction action) {
        this.resource = resource;
        this.action = action;
    }
}
