# Storage Strategies for Custom Form Submissions

Three implementations of `SubmissionStore` (`com.notes.notes.forms.storage`), all
proven functionally equivalent by `AbstractSubmissionStoreContractTest` (36 tests,
12 per strategy). This doc explains what each one actually *is*, and why their
behavior and performance characteristics differ — the basis for the benchmark in
`benchmarks/` (Phase 3).

## EAV (Entity-Attribute-Value)

**Table shape**: one shared table, `form_value(submission_id, field_id, value_text,
value_num, value_date, value_bool)`. A submission with 12 fields is 12 rows.

**Why it looks like this**: the table never changes shape no matter how many forms
or fields exist. Adding a field to a form is a no-op at the database level — it's
just a new `field_id` that future rows reference; nothing is altered, no lock is
taken on existing data.

**What it costs**: every field read/write is its own row, so a single submission
touches 12 rows instead of 1. Filtering on two fields means two joins back to the
same table under different aliases. Aggregates (`SUM`, `GROUP BY`) join in `field_id`
by id, which is simple but means the query planner is working with the same
physical table for every different "column" in the data.

**Where it should win**: schemas that change constantly, or forms with very many
optional/sparse fields where most submissions don't fill in most fields (EAV never
pays for a column that's empty — a RELATIONAL table does, if even as `NULL`).

## JSON

**Table shape**: one row per submission, `form_submission_json(submission_id,
data)`, where `data` is a MySQL `JSON` column holding every field value as one
document (`{"qty": 10, "price": 19.99, ...}`).

**Why it looks like this**: a submission is naturally one document. Reading a whole
submission is a single-row fetch, no joins. Adding a field means nothing changes in
already-written documents — they just don't have that key until a new submission
(or an update) sets it.

**What it costs**: filtering or sorting on a field means extracting it from JSON on
every row scanned (`data->>'$.qty'`), which by default has no index to use — MySQL
has to parse the document to compare. This store adds a real, indexed **generated
column** per field flagged `"indexed": true` in its config
(`JsonSubmissionStore.onFieldAdded`), which is the standard way to get JSON-backed
filtering back to real index performance — at the cost of one more physical column
(and its index) per field actually worth indexing. The benchmark measures this
store twice: with and without that index, specifically to show the gap.

**Where it should win**: read-heavy "fetch one submission and show it" patterns
(dashboards, detail views), and forms whose shape genuinely varies submission to
submission.

## RELATIONAL

**Table shape**: one physical table per form, `form_data_<formId>`, with one real
column per field, `f_<fieldId>`, typed to match the field (`BIGINT`, `DECIMAL(20,6)`,
`DATE`, `TINYINT(1)`, `VARCHAR(500)`).

**Why it looks like this**: this is what you'd hand-design a dedicated table for.
Every column is a real, typed, indexable SQL column — filters, sorts, and aggregates
all run exactly the way they would on `production_entries` or `rmir_entries` today.

**What it costs**: adding a field is `ALTER TABLE ... ADD COLUMN`, which on a large
existing table is the one genuinely expensive schema-change operation among the
three strategies (the benchmark's "add-field on a populated form" scenario exists
specifically to put a number on this). Removing a field drops a column, same cost
class. A form is also not fully dynamic until its table exists — `onFormCreated`
must run the `CREATE TABLE` before any submission can be saved.

**Table/column naming safety**: `form_data_<formId>` and `f_<fieldId>` are built
*only* from database-generated numeric ids — never from the admin-entered
`form_key`, `label`, or `field_key` — so there is no SQL-identifier-injection
surface despite this being the one strategy that builds DDL by string
concatenation.

**Where it should win**: everything else — this is the one strategy where every
operation (filter, sort, aggregate, storage size) gets to use ordinary indexed SQL
columns with no extraction/casting step, which is exactly what the benchmark should
confirm with real numbers.

## The one thing all three share

`form_submission(id, form_id, form_version, created_by, created_at, updated_by,
updated_at)` is the single header row every strategy writes to on save, regardless
of where the field values themselves end up. Ownership (`created_by`), auditing, and
`OWN`-scope permission filtering (`PermissionGuard`, same mechanism as every other
module) all read this one table — a caller never needs to know or care which
strategy is behind a given form to answer "do I own this submission."
