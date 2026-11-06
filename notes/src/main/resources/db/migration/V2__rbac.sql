-- Dynamic RBAC (MIS_CONTEXT.md section 4, D2-D7).
-- Resources, actions, permissions, roles and grants are all data, not code.

CREATE TABLE app_resource (
  id BIGINT NOT NULL AUTO_INCREMENT,
  res_key VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  type ENUM('SYSTEM','FORM') NOT NULL DEFAULT 'SYSTEM',
  parent_id BIGINT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_resource_res_key (res_key),
  CONSTRAINT fk_app_resource_parent FOREIGN KEY (parent_id) REFERENCES app_resource (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE app_action (
  id BIGINT NOT NULL AUTO_INCREMENT,
  action_key VARCHAR(32) NOT NULL,
  name VARCHAR(255) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_action_action_key (action_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE app_permission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  resource_id BIGINT NOT NULL,
  action_id BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_permission_resource_action (resource_id, action_id),
  CONSTRAINT fk_app_permission_resource FOREIGN KEY (resource_id) REFERENCES app_resource (id),
  CONSTRAINT fk_app_permission_action FOREIGN KEY (action_id) REFERENCES app_action (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE app_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  role_key VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(500) NULL,
  system_role TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_role_role_key (role_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_permission (
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  scope ENUM('ALL','OWN') NOT NULL DEFAULT 'ALL',
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES app_role (id),
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES app_permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES app_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Seed actions (D3)
-- ---------------------------------------------------------------------------
INSERT INTO app_action (action_key, name) VALUES
  ('VIEW',   'View'),
  ('CREATE', 'Create'),
  ('EDIT',   'Edit'),
  ('DELETE', 'Delete'),
  ('EXPORT', 'Export'),
  ('ADMIN',  'Admin (implies every action on the resource)');

-- ---------------------------------------------------------------------------
-- Seed SYSTEM resources (MIS_CONTEXT.md section 4)
-- ---------------------------------------------------------------------------
INSERT INTO app_resource (res_key, name, type) VALUES
  ('PRODUCTION',      'Production Entries',       'SYSTEM'),
  ('RMIR',             'Raw Material Inspection',  'SYSTEM'),
  ('TASKS',            'Tasks',                    'SYSTEM'),
  ('TASK_TEMPLATES',   'Task Templates',           'SYSTEM'),
  ('MASTERS',          'Masters',                  'SYSTEM'),
  ('HR_EMPLOYEE',      'HR Employee Master',       'SYSTEM'),
  ('HR_INOUT',         'HR In/Out',                'SYSTEM'),
  ('USERS',            'User Management',          'SYSTEM'),
  ('ROLES',            'Roles & Permissions',      'SYSTEM'),
  ('FORM_BUILDER',     'Form Builder',             'SYSTEM');

-- Every resource gets a permission row for every action (D2/D3).
INSERT INTO app_permission (resource_id, action_id)
SELECT r.id, a.id FROM app_resource r CROSS JOIN app_action a;

-- ---------------------------------------------------------------------------
-- Seed roles (MIS_CONTEXT.md section 4)
-- ---------------------------------------------------------------------------
INSERT INTO app_role (role_key, name, description, system_role) VALUES
  ('SUPER_ADMIN',   'Super Admin',
   'Full access to every resource, including resources created after this role was seeded.', 1),
  ('STANDARD_USER', 'Standard User',
   'Default role for every non-admin user; mirrors the access that was open to all authenticated users before dynamic RBAC.', 1);

-- SUPER_ADMIN: ADMIN action on every current resource (ADMIN implies every action on that
-- resource, D3). Resources created in the future are covered by a wildcard rule in
-- AuthorizationService, not by rows here.
INSERT INTO role_permission (role_id, permission_id, scope)
SELECT (SELECT id FROM app_role WHERE role_key = 'SUPER_ADMIN'), p.id, 'ALL'
FROM app_permission p
JOIN app_action a ON a.id = p.action_id
WHERE a.action_key = 'ADMIN';

-- STANDARD_USER: every action on PRODUCTION/RMIR/TASKS/TASK_TEMPLATES/MASTERS/HR_* except
-- the actions that map to today's six admin-only checks (MIS_CONTEXT.md section 2.1):
--   - DELETE on PRODUCTION, RMIR, MASTERS (today's @PreAuthorize('ROLE_ADMIN') delete endpoints)
--   - ADMIN on TASK_TEMPLATES (gates the admin-only "tasks created from template" view)
-- ADMIN is never granted to STANDARD_USER on any resource: ADMIN implies every other
-- action (D3), so granting it on PRODUCTION/RMIR/MASTERS would silently re-grant the
-- DELETE this role is meant to lack. Nothing on USERS/ROLES/FORM_BUILDER (the admin area).
INSERT INTO role_permission (role_id, permission_id, scope)
SELECT (SELECT id FROM app_role WHERE role_key = 'STANDARD_USER'), p.id, 'ALL'
FROM app_permission p
JOIN app_resource r ON r.id = p.resource_id
JOIN app_action a ON a.id = p.action_id
WHERE r.res_key IN ('PRODUCTION', 'RMIR', 'TASKS', 'TASK_TEMPLATES', 'MASTERS', 'HR_EMPLOYEE', 'HR_INOUT')
  AND a.action_key <> 'ADMIN'
  AND NOT (r.res_key = 'PRODUCTION' AND a.action_key = 'DELETE')
  AND NOT (r.res_key = 'RMIR' AND a.action_key = 'DELETE')
  AND NOT (r.res_key = 'MASTERS' AND a.action_key = 'DELETE');

-- ---------------------------------------------------------------------------
-- Data migration: map every existing user's legacy single role (D5).
-- users.role_id is left untouched; it stays in sync going forward via application code.
-- ---------------------------------------------------------------------------
INSERT INTO user_role (user_id, role_id)
SELECT u.user_id, (SELECT id FROM app_role WHERE role_key = 'SUPER_ADMIN')
FROM users u
JOIN roles legacy ON legacy.role_id = u.role_id
WHERE legacy.role_name = 'ROLE_ADMIN';

INSERT INTO user_role (user_id, role_id)
SELECT u.user_id, (SELECT id FROM app_role WHERE role_key = 'STANDARD_USER')
FROM users u
JOIN roles legacy ON legacy.role_id = u.role_id
WHERE legacy.role_name = 'ROLE_USER';
