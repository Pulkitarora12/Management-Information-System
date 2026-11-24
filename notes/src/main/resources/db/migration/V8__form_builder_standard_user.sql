-- Let STANDARD_USER see/use the Custom Forms feature (Create Form, List Forms buttons);
-- access to each individual form's own data is controlled separately by that form's
-- auto-registered FORM_<id> resource (D2), granted per-user/role via the existing
-- /admin/roles/{id}/permissions grid - this only unlocks the top-level entry points.
INSERT INTO role_permission (role_id, permission_id, scope)
SELECT (SELECT id FROM app_role WHERE role_key = 'STANDARD_USER'), p.id, 'ALL'
FROM app_permission p
JOIN app_resource r ON r.id = p.resource_id
JOIN app_action a ON a.id = p.action_id
WHERE r.res_key = 'FORM_BUILDER'
  AND a.action_key IN ('VIEW', 'CREATE');
