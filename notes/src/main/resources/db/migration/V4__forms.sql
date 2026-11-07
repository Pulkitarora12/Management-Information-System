-- Custom form builder (MIS_CONTEXT.md section 5, D6-D8, D10).
-- form_definition/form_field/form_submission are shared by every storage strategy.
-- form_value (EAV) and form_submission_json (JSON) are the two fixed-schema storage
-- backends; the third (RELATIONAL) creates one physical table per form at runtime,
-- so it has no table here.

CREATE TABLE form_definition (
  id BIGINT NOT NULL AUTO_INCREMENT,
  form_key VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(1000) NULL,
  resource_id BIGINT NULL,
  storage_type ENUM('EAV','JSON','RELATIONAL') NOT NULL,
  version INT NOT NULL DEFAULT 1,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_by BIGINT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_form_definition_form_key (form_key),
  CONSTRAINT fk_form_definition_resource FOREIGN KEY (resource_id) REFERENCES app_resource (id),
  CONSTRAINT fk_form_definition_created_by FOREIGN KEY (created_by) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE form_field (
  id BIGINT NOT NULL AUTO_INCREMENT,
  form_id BIGINT NOT NULL,
  field_key VARCHAR(64) NOT NULL,
  label VARCHAR(255) NOT NULL,
  type ENUM('TEXT','NUMBER','DECIMAL','DATE','BOOLEAN','DROPDOWN','MASTER_REF','FILE','CALCULATED') NOT NULL,
  required TINYINT(1) NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  config JSON NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_form_field_form_key (form_id, field_key),
  CONSTRAINT fk_form_field_form FOREIGN KEY (form_id) REFERENCES form_definition (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE form_submission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  form_id BIGINT NOT NULL,
  form_version INT NOT NULL,
  created_by BIGINT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by BIGINT NULL,
  updated_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  KEY idx_form_submission_form_id (form_id),
  CONSTRAINT fk_form_submission_form FOREIGN KEY (form_id) REFERENCES form_definition (id),
  CONSTRAINT fk_form_submission_created_by FOREIGN KEY (created_by) REFERENCES users (user_id),
  CONSTRAINT fk_form_submission_updated_by FOREIGN KEY (updated_by) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- EAV storage: one row per (submission, field). Typed columns so numeric/date
-- filters and aggregates can use real column types instead of string comparison.
CREATE TABLE form_value (
  id BIGINT NOT NULL AUTO_INCREMENT,
  submission_id BIGINT NOT NULL,
  field_id BIGINT NOT NULL,
  value_text VARCHAR(1000) NULL,
  value_num DECIMAL(20,6) NULL,
  value_date DATE NULL,
  value_bool TINYINT(1) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_form_value_submission_field (submission_id, field_id),
  KEY idx_form_value_field_num (field_id, value_num),
  KEY idx_form_value_field_text (field_id, value_text(255)),
  KEY idx_form_value_field_date (field_id, value_date),
  CONSTRAINT fk_form_value_submission FOREIGN KEY (submission_id) REFERENCES form_submission (id),
  CONSTRAINT fk_form_value_field FOREIGN KEY (field_id) REFERENCES form_field (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- JSON storage: one row per submission, every field value in one JSON document.
-- Generated columns + indexes are added per-field at runtime for fields flagged
-- "indexed" in form_field.config (see JsonSubmissionStore.onFieldAdded).
CREATE TABLE form_submission_json (
  submission_id BIGINT NOT NULL,
  data JSON NOT NULL,
  PRIMARY KEY (submission_id),
  CONSTRAINT fk_form_submission_json_submission FOREIGN KEY (submission_id) REFERENCES form_submission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
