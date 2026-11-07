-- Soft field removal becomes a metadata-only flag rather than a row DELETE, so it
-- no longer collides with form_value's FK on field_id (EAV) when value rows still
-- exist for the field being "removed". JSON/RELATIONAL already behaved this way in
-- effect (their generated columns/physical columns stay orphaned, not FK-enforced);
-- this just gives EAV the same metadata-only path instead of attempting a row delete.
ALTER TABLE form_field ADD COLUMN active TINYINT(1) NOT NULL DEFAULT 1;
