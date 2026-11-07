-- Track who created a production/RMIR entry, so OWN-scope permissions can be
-- enforced for real instead of only existing as a value in role_permission.scope.
-- Nullable and no backfill: every row that exists before this migration has no
-- recorded creator, so only ALL-scope roles (SUPER_ADMIN) can edit/delete it going
-- forward - STANDARD_USER (OWN scope) can still see it (VIEW stays ALL) but can't
-- claim ownership of something created before ownership was tracked.

ALTER TABLE production_entries ADD COLUMN created_by BIGINT NULL;
ALTER TABLE production_entries ADD CONSTRAINT fk_production_entries_created_by
    FOREIGN KEY (created_by) REFERENCES users (user_id);

ALTER TABLE rmir_entries ADD COLUMN created_by BIGINT NULL;
ALTER TABLE rmir_entries ADD CONSTRAINT fk_rmir_entries_created_by
    FOREIGN KEY (created_by) REFERENCES users (user_id);

-- ---------------------------------------------------------------------------
-- Re-point STANDARD_USER's grants at the access model actually wanted:
--   - PRODUCTION / RMIR: can see everything (VIEW=ALL), create their own
--     (CREATE=ALL - "own" doesn't mean anything for creating a new row), but can
--     only EDIT/DELETE records they created (OWN).
--   - MASTERS: no access at all - admin (SUPER_ADMIN) only.
--   - TASK_TEMPLATES: no access at all (unchanged in spirit from V2, just now also
--     excludes VIEW/CREATE/EDIT/DELETE, not only ADMIN).
--   - EXPORT: never granted to STANDARD_USER on any resource.
--   - TASKS / HR_EMPLOYEE / HR_INOUT: unchanged (VIEW/CREATE/EDIT/DELETE = ALL).
-- ---------------------------------------------------------------------------
DELETE rp FROM role_permission rp
JOIN app_role r ON r.id = rp.role_id
WHERE r.role_key = 'STANDARD_USER';

INSERT INTO role_permission (role_id, permission_id, scope)
SELECT (SELECT id FROM app_role WHERE role_key = 'STANDARD_USER'), p.id, 'ALL'
FROM app_permission p
JOIN app_resource r ON r.id = p.resource_id
JOIN app_action a ON a.id = p.action_id
WHERE r.res_key IN ('TASKS', 'HR_EMPLOYEE', 'HR_INOUT')
  AND a.action_key IN ('VIEW', 'CREATE', 'EDIT', 'DELETE');

INSERT INTO role_permission (role_id, permission_id, scope)
SELECT (SELECT id FROM app_role WHERE role_key = 'STANDARD_USER'), p.id, 'ALL'
FROM app_permission p
JOIN app_resource r ON r.id = p.resource_id
JOIN app_action a ON a.id = p.action_id
WHERE r.res_key IN ('PRODUCTION', 'RMIR')
  AND a.action_key IN ('VIEW', 'CREATE');

INSERT INTO role_permission (role_id, permission_id, scope)
SELECT (SELECT id FROM app_role WHERE role_key = 'STANDARD_USER'), p.id, 'OWN'
FROM app_permission p
JOIN app_resource r ON r.id = p.resource_id
JOIN app_action a ON a.id = p.action_id
WHERE r.res_key IN ('PRODUCTION', 'RMIR')
  AND a.action_key IN ('EDIT', 'DELETE');
