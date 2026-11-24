# Manual Browser Test Checklist — Form Builder + RBAC

## Form Builder (3 storage strategies)

- [ ] Create a new form with fields of each type (TEXT, NUMBER, DECIMAL, DATE, BOOLEAN, DROPDOWN, MASTER_REF, CALCULATED, FILE) under each storage strategy (EAV, JSON, RELATIONAL) — confirm the form saves and the fields render correctly on the entry screen.
- [ ] Submit a new entry for each strategy, filling every field type — confirm it saves and reopening the submission shows the same values back (round-trip check).
- [ ] Edit an existing submission — change a few field values, save, reload, confirm the new values persisted and old ones are gone.
- [ ] Delete a submission — confirm it disappears from the list view.
- [ ] Filter the submission list by a field value (equality and range, if the UI exposes both) — confirm results match what you'd expect by eye.
- [ ] Sort the submission list by a field — confirm ascending/descending both work.
- [ ] Add a new field to an existing form (schema evolution) — confirm existing submissions don't break and the new field appears for new entries.
- [ ] Rename a field label — confirm old submissions still display correctly under the new label.
- [ ] Soft-remove a field (if exposed in the UI) — confirm the field disappears from new-entry forms but old data isn't visibly lost/corrupted.
- [ ] Hard-remove a field (if exposed in the UI) — confirm existing submissions no longer show that field's data at all.
- [ ] Export a form's submissions (CSV/Excel, whatever the UI offers) — confirm the exported file opens and contains the expected rows/columns.
- [ ] Try submitting invalid data (wrong type into a NUMBER field, a date outside format, missing a required field) — confirm the UI either blocks it client-side or the server returns a sensible error rather than silently accepting garbage or crashing.

## RBAC — module-level permission enforcement

For each of the 7 modules (PRODUCTION, RMIR, TASKS, TASK_TEMPLATES, MASTERS, HR_EMPLOYEE, HR_INOUT), test with at least two accounts: an admin/STANDARD_USER (should retain full existing access) and a custom role holding only partial permissions (e.g. VIEW-only).

- [ ] **STANDARD_USER regression check**: log in as the existing primary role and confirm nothing that worked before now 403s or is hidden — all buttons/nav links that were visible before are still visible, all actions that worked before still work.
- [ ] **VIEW-only custom role**: create/assign a role with only `{MODULE}:VIEW` for one module (e.g. PRODUCTION). Confirm:
  - [ ] The module's nav link is visible.
  - [ ] The list/detail pages load (200).
  - [ ] The "New Entry" / "Create" button is **not visible** on the page.
  - [ ] Directly hitting the create endpoint (e.g. via URL or a saved form) returns 403, not a silent success or a crash.
  - [ ] Edit/Export/Delete buttons are **not visible**.
  - [ ] Directly hitting edit/export/delete endpoints returns 403.
- [ ] **Cross-module isolation**: with that same VIEW-only-on-PRODUCTION role, confirm:
  - [ ] RMIR's nav link is hidden (or visible but every RMIR page/action 403s if nav hiding isn't implemented that granularly).
  - [ ] Directly navigating to any RMIR URL returns 403.
  - [ ] Masters' nav link is hidden, and directly navigating to any Masters URL returns 403.
- [ ] Repeat the VIEW-only spot-check above for at least 2 more of the remaining modules (e.g. TASKS and HR_EMPLOYEE) to confirm the fix wasn't just applied correctly to PRODUCTION.
- [ ] **EXPORT-specific check**: for a module with export (PRODUCTION, RMIR, at least), confirm a role without `{MODULE}:EXPORT` cannot see or use the export button/link, and hitting the export endpoint directly 403s.
- [ ] **EDIT-specific check**: for TASKS/TASK_TEMPLATES (which have status/assign/comment actions beyond a simple "edit" button), confirm a role without `{MODULE}:EDIT` can view a task but cannot change its status, reassign it, or comment, and that the corresponding buttons are hidden.
- [ ] **DELETE-specific check**: confirm a role without `{MODULE}:DELETE` doesn't see delete buttons and 403s if the delete endpoint is hit directly, for every module that supports deletion (TASK_TEMPLATES, HR_INOUT, and whichever others expose it).
- [ ] **New integration test sanity check**: the automated test (`AuthorizationIntegrationTest`) covers PRODUCTION:VIEW-only → RMIR/Masters denial via direct service calls, not a full HTTP round-trip — this manual pass is what actually confirms the HTTP layer (`@PreAuthorize` + controller wiring) and the Thymeleaf `sec:authorize` gating work together correctly in a real browser session, which the automated test can't see.

## Buffer-pool sensitivity (once you've run it from an elevated terminal)

- [ ] After running the bp1g rerun (see REPORT.md's "Open item" section for the exact commands), confirm `summary.csv` has `_BP1G`-suffixed rows for sizes 50000/100000 across all 6 variants (not just one, unlike the partial/invalid run that was cleaned up during this session).
- [ ] Confirm `innodb_buffer_pool_size` was restored to 128M afterward (`SHOW VARIABLES LIKE 'innodb_buffer_pool_size';` should show 134217728) so the MySQL instance isn't left misconfigured for normal use.
