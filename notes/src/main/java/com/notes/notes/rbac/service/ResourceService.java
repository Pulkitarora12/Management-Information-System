package com.notes.notes.rbac.service;

import com.notes.notes.rbac.entity.RbacAction;
import com.notes.notes.rbac.entity.RbacPermission;
import com.notes.notes.rbac.entity.RbacResource;
import com.notes.notes.rbac.entity.ResourceType;
import com.notes.notes.rbac.repository.RbacActionRepository;
import com.notes.notes.rbac.repository.RbacPermissionRepository;
import com.notes.notes.rbac.repository.RbacResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Every resource (system module or custom form, D2) is registered here, which also
 * auto-creates an app_permission row for every known action on it (D3) so the
 * permission grid always has a full set of cells to grant.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ResourceService {

    private final RbacResourceRepository resourceRepository;
    private final RbacActionRepository actionRepository;
    private final RbacPermissionRepository permissionRepository;
    private final AuthorizationService authorizationService;

    public RbacResource registerResource(String resKey, String name, ResourceType type) {
        return resourceRepository.findByResKey(resKey).orElseGet(() -> {
            RbacResource resource = resourceRepository.save(new RbacResource(resKey, name, type));
            createMissingPermissions(resource);
            authorizationService.invalidateAll();
            return resource;
        });
    }

    public void deactivateResource(String resKey) {
        RbacResource resource = resourceRepository.findByResKey(resKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown resource: " + resKey));
        resource.setActive(false);
        resourceRepository.save(resource);
        authorizationService.invalidateAll();
    }

    public List<RbacResource> listActiveResources() {
        return resourceRepository.findAll().stream().filter(RbacResource::isActive).toList();
    }

    private void createMissingPermissions(RbacResource resource) {
        List<RbacAction> actions = actionRepository.findAllByOrderByIdAsc();
        for (RbacAction action : actions) {
            permissionRepository.findByResourceIdAndActionId(resource.getId(), action.getId())
                    .orElseGet(() -> permissionRepository.save(new RbacPermission(resource, action)));
        }
    }
}
