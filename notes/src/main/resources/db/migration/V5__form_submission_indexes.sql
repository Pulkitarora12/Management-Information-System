-- Benchmark v2 (paper-grade run): form_submission needs these for own-scope
-- paging and created_at-sorted history queries to use an index instead of a
-- full scan, at any dataset size.
ALTER TABLE form_submission ADD INDEX idx_form_submission_form_created_at (form_id, created_at);
ALTER TABLE form_submission ADD INDEX idx_form_submission_form_created_by (form_id, created_by);
