-- Denormalizes created_by onto form_submission_json, same pattern RELATIONAL
-- already used (one column + one index), so own-scope filtering has an equally
-- indexed path for every strategy instead of JSON alone relying on a join back
-- to form_submission for this one column.
ALTER TABLE form_submission_json
  ADD COLUMN created_by BIGINT NULL,
  ADD KEY idx_form_submission_json_created_by (created_by);
