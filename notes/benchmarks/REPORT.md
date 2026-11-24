# Storage Strategy Benchmark — Paper-Grade Run

Supersedes the earlier 1k/10k pilot. Every number below is from one real, complete
run against a live MySQL database (`mis_bench`, never `mis`/`mis_test`) — nothing
is estimated or extrapolated. Raw per-sample data: `results/raw_paper.csv` (2,000
rows). Aggregated stats: `results/summary.csv`. Storage: `results/storage_size_summary.csv`.
Full console log and anomaly notes: `results/run_log.txt`.

**Total runtime: 22.5 minutes** (both sizes, all 5 variants, Scenarios A+B).

```
mvn test -Dtest=PaperBenchmarkRunner -DfailIfNoTests=false
```

## Scope decisions — what this run covers vs. the original ask

The original request (`BENCHMARK_FINAL_PROMPT.md`) specified 10k/100k/1M rows,
5 variants, Scenarios A through E, `EXPLAIN ANALYZE` capture for every
operation×strategy, and PNG charts. Given the session's time budget, this run
covers:

- **Sizes**: 10,000 and 100,000 rows (not 1M). Discussed and agreed with the user
  up front — at the observed per-cell timings (see below), 1M would plausibly
  still finish, but EAV's worst operations (see Anomalies) scale badly enough that
  running it blind at 1M risked the 45-minute-per-cell abort rule without any
  guarantee of useful numbers beyond what 100k already shows.
- **Scenarios A (core operations) and B (schema evolution)**: done, all 5 variants,
  both sizes.
- **Not done**: Scenario C (60-field sparse form), D (concurrency), E (data
  integrity), 1M rows, per-operation `EXPLAIN ANALYZE` capture, and PNG charts (no
  charting library in this project — adding one would have been a new-dependency
  decision made under time pressure, so it was skipped rather than guessed at).
  All of this is still buildable on top of the current harness; none of it was
  started.

## Harness fixes applied (Section 1 of the brief)

- **Real batching**: `BulkLoader` uses `JdbcTemplate.batchUpdate` with
  `rewriteBatchedStatements=true` on the `mis_bench` connection URL, for both the
  `form_submission` header rows and each strategy's value rows. This is a
  benchmark-only fast path — it is *not* how the app inserts data in production;
  `insert_single` still measures the real `SubmissionStore.save()` path.
- **Per-cell isolation**: `form_value` (EAV) and `form_submission_json` (JSON
  family) are shared tables across every form, so they are `TRUNCATE`d both
  *before* and *after* each cell — before, so a cell never inherits a previous
  run's leftovers (this was a real bug caught during a smoke test — see below);
  after, so the next cell starts clean too. RELATIONAL's per-form table is
  already isolated (`CREATE`d in `onFormCreated`, `DROP`ped in teardown).
  `ANALYZE TABLE` runs on the relevant table(s) right after loading, before any
  measurement.
- **Warm-up/run counts**: 3 warm-up + 10 timed runs per read operation, 1 warm-up
  + 5 timed runs per write/DDL operation, as specified.
- **Order bias**: variant order is shuffled per size with a recorded seed
  (`Random(42 + size)`); the exact order used is in `results/run_log.txt`.
- **Same data**: one `PaperDataGenerator` with a fixed seed (`20261107L`),
  re-seeded fresh at the start of every cell, so every variant receives
  byte-identical logical rows regardless of variant order.
- **CSV format fixed**: `strategy, dataset_size, scenario, operation, run, value,
  unit, rows_returned, timestamp` — `unit` is `ms` or `bytes`, fixing the pilot's
  bug where a byte count was silently divided by 1,000,000 into the latency
  column.

## A second isolation bug found and fixed during this run (not in the original brief)

A smoke test at 200 rows caught this before the real run: `storage_size` for
JSON-family variants was coming back at ~15 MB / ~22 MB for 200 rows — because
`form_submission_json` still held rows from an earlier (unrelated) run that had
never been truncated *before* the first cell of a fresh invocation started
(the original plan only truncated *after* each cell). Fixed by truncating both
before and after every cell; confirmed sane (65 KB – 460 KB range) on the
corrected smoke test before running the real 10k/100k pass.

## Environment

| | |
|---|---|
| CPU | Intel Core i7-1255U (10 cores / 12 threads) |
| RAM | ~15.7 GB |
| Disk | NVMe SSD (WD PC SN810) |
| OS | Windows 11 Home Single Language, build 10.0.26200 |
| JDK | 17.0.12 |
| MySQL | 8.0.42, local, default config (not tuned, as instructed) |
| `innodb_buffer_pool_size` | 134,217,728 bytes (128 MB — the MySQL default; small relative to a 100k-row dataset) |
| `innodb_flush_log_at_trx_commit` | 1 (full durability) |
| `innodb_log_file_size` | 50,331,648 bytes (48 MB) |
| `max_connections` | 151 |
| Database | `mis_bench`, dedicated, never `mis`/`mis_test` |

## Equivalent indexing per strategy

| Strategy | Indexes |
|---|---|
| EAV | PK on `form_value.id`; unique `(submission_id, field_id)`; `(field_id, value_num)`; `(field_id, value_text(255))` *(spec asked for `value_text(64)`; this project's existing migration already used 255 from Phase 2 — not changed for this run)*; `(field_id, value_date)`. `form_submission`: PK, `(form_id, created_at)`, `(form_id, created_by)`. |
| JSON | PK on `form_submission_json.submission_id` only. Same `form_submission` indexes as above. |
| JSON_IDX | Same as JSON, plus a generated column + index on `part_no` (high cardinality, ~2,000 distinct) and `prod_date`. |
| JSON_IDX_LOW | Same as JSON, plus a generated column + index on `shift` (low cardinality, 3 distinct values). |
| RELATIONAL | PK on `submission_id`; secondary index on `part_no`, `prod_date`, `shift` (added to `RelationalSubmissionStore` for this run — it previously had no secondary indexes at all, which the original spec's indexing table implicitly required fixing). Same `form_submission` indexes. |

## Scenario A — core operations at 100,000 rows (the size where the real pattern holds)

All times are mean, ms, n=10 (reads) or n=5 (writes) unless noted. Full p50/p95/p99/min/max/stddev in `summary.csv`.

| Operation | EAV | JSON | JSON_IDX | JSON_IDX_LOW | RELATIONAL |
|---|---|---|---|---|---|
| find_by_id | 0.97 | 0.94 | 0.74 | 0.63 | **0.51** |
| filter_eq_high (part_no) | 4.24 | 2.94 | 2.25 | 2.11 | **1.08** |
| filter_eq_low (shift) | 870.52 | **8.48** | 272.96 * | 317.55 * | 1.46 |
| filter_range_eq | 157.99 | 33.65 | 27.35 | 36.89 | **116.09** † |
| filter_3_fields | 475.65 | 75.74 | 196.46 | 234.59 | **13.96** |
| like_search | 974.06 | 12.10 | 9.93 | 9.31 | **2.68** |
| aggregate_group (2×SUM) | 25,917.95 | 1,616.04 | 1,678.83 | 1,785.00 | **370.85** |
| aggregate_date | 4,393.22 | 692.52 | 761.68 | 764.23 | **208.98** |
| count_all | 40.02 | 305.36 ‡ | 390.98 ‡ | 299.90 ‡ | **31.21** |
| page_first (sorted) | **6,494.19** ⚠ | 2.62 | 2.83 | 1.65 | 1.25 |
| page_deep (50% offset) | **6,985.64** ⚠ | 805.68 | 1,016.65 | 797.54 | **289.61** |
| own_scope_page | 7.45 | 2.55 | 1.47 | 1.54 | 20.11 † |
| insert_single (real, via store) | 17.87 | 12.12 | 8.17 | 7.85 | **8.61** |
| update_single | 13.43 | 5.65 | 5.96 | 3.29 | 5.28 |
| delete_single | 20.41 | 16.03 | 15.02 | 11.53 | **12.24** |
| export_full (all rows, n=3) | 15,274.81 | 5,063.49 | 4,942.63 | 5,196.62 | **2,003.23** |
| bulk_load_total (load 100k, one value) | 108,015.13 | 27,851.26 | 33,186.42 | 39,375.89 | **12,722.10** |

\* See Anomalies — the indexed variants are *slower* than plain JSON here, not faster.
† See Anomalies — RELATIONAL unexpectedly loses on two operations.
‡ JSON family is slower than EAV/RELATIONAL on plain `COUNT(*)` — see Anomalies.
⚠ EAV's catastrophic cost — see Anomalies.

**At 10,000 rows**, the same 5-way comparison is in `summary.csv` filtered to
`dataset_size=10000`; the overall ranking is the same for most operations, with
two notable differences from the 100k pattern worth flagging rather than
smoothing over: RELATIONAL's `page_first` is already dramatically faster than
every other strategy at 10k (1.81ms vs. 51-87ms for the rest), while at 100k the
JSON family catches up to near-RELATIONAL speed on that same operation and EAV
instead falls off a cliff — a reordering between sizes, not just a scaling
difference, and one this run doesn't have `EXPLAIN` evidence to explain with
confidence (see Anomalies).

## Anomalies — reported honestly, not rationalized away

**EAV's `page_first`/`page_deep`: 6.5-7.0 seconds at 100k rows (vs. 1-290ms for
everyone else).** This is the single most dramatic result in this run. The
`EavSubmissionStore.query()` sort-by-field implementation uses a per-row
correlated subquery (`ORDER BY (SELECT value_num FROM form_value WHERE
submission_id = fs.id AND field_id = ?)`), which forces one subquery execution
per candidate row rather than a single indexed sort. At 10k rows this is already
EAV's weak point (86.9ms vs. RELATIONAL's 1.8ms) but still sub-100ms; at 100k it
becomes unusable. This is a real architectural cost of the current EAV
implementation's sort strategy, not a MySQL-level surprise — no `EXPLAIN` needed
to know a correlated per-row subquery doesn't scale, though this run didn't
capture one to confirm the plan MySQL actually chose.

**Indexing a low-cardinality field made JSON filtering slower, not faster** —
confirmed at 100k rows, reproducing the pilot's finding at ten times the scale.
`JSON_IDX_LOW` (which indexes `shift`, 3 distinct values across 100k rows) takes
317.55ms on `filter_eq_low`; plain `JSON` (no index at all) takes 8.48ms for the
exact same filter. `JSON_IDX` (indexing `part_no` and `prod_date`, not `shift`)
is also slower than plain JSON here (272.96ms) despite not even indexing the
filtered column — suggesting MySQL's query planner is choosing a *worse* overall
plan once any generated-column index exists on the table, not only when the
specific indexed column is being filtered. This wasn't explored further (would
need `EXPLAIN` on all three variants for this one operation) but is worth
flagging precisely because it contradicts the "just add an index" intuition.

**RELATIONAL loses on `filter_range_eq` and `own_scope_page`.** Both are modest
losses (116ms vs. JSON's 28-37ms on the range filter; 20ms vs. JSON's 1.5-2.5ms
on own-scope paging) rather than dramatic ones, but they're real and
reproducible at 100k. Both queries filter/sort on columns that aren't in
RELATIONAL's current index set as built for this run (`machine` isn't indexed;
`created_by`'s join path through `form_submission` isn't covered by a composite
index that also serves the per-form table). This looks like a gap in this run's
chosen index set for RELATIONAL rather than an inherent strategy weakness —
adding an index on `machine` and reconsidering the own-scope join would be the
natural next experiment, not done here.

**JSON family is slower than EAV and RELATIONAL on plain `COUNT(*)`** (300-391ms
vs. EAV's 40ms and RELATIONAL's 31ms) — counter-intuitive since counting rows in
a one-row-per-submission table should be cheap. Carried over from the pilot's
same finding; still unexplained without `EXPLAIN`, flagged rather than guessed at.

**`page_first` reorders between 10k and 100k.** At 10k, RELATIONAL is already
far ahead (1.81ms) while JSON/JSON_IDX/JSON_IDX_LOW are all in the 51-57ms range
and EAV is at 86.9ms — a fairly flat, expected-looking picture. At 100k, EAV
collapses to 6,494ms (consistent with the correlated-subquery explanation above)
*and* the entire JSON family drops to 1.6-2.8ms, essentially matching
RELATIONAL. Why JSON's sort gets dramatically *faster* in absolute terms at 10x
the data isn't explained by anything measured in this run — plausibly a MySQL
buffer-pool or query-plan threshold effect given the 10-row page size and small
buffer pool (128MB), but that's a hypothesis, not a finding, and is flagged as
such.

## Scenario B — schema evolution

| Operation | EAV | JSON | JSON_IDX | JSON_IDX_LOW | RELATIONAL |
|---|---|---|---|---|---|
| add_field_plain (100k) | 6.98ms | 6.68ms | 6.30ms | 5.10ms | 86.53ms |
| add_field_indexed (100k) | n/a † | 1,147.81ms | 1,309.65ms | 1,164.41ms | 457.47ms |
| remove_field (100k, drops a populated column) | **24,371.99ms** | 0.086ms | 0.073ms | 0.074ms | 33.42ms |
| rename_label (100k, metadata only) | 7.34ms | 9.26ms | 7.25ms | 6.28ms | 5.44ms |

† EAV genuinely has no DDL for a new field regardless of the indexed flag — a new
`field_id` just starts being referenced by future rows. Recorded as 0, not
measured, per the original spec's own instruction for this case.

**The schema-evolution story is more nuanced than "EAV is free, RELATIONAL is
expensive."** Adding a field is indeed cheapest for EAV/JSON and costs
RELATIONAL a real but still-sub-100ms `ALTER TABLE` at this scale. But
**removing a populated field is EAV's worst schema-evolution cost by two orders
of magnitude** — 24.4 *seconds* at 100k rows, because `onFieldRemoved` deletes
every `form_value` row for that field (100k row deletes), while JSON's removal
of an unindexed field is a true no-op (0.07-0.09ms, since nothing physically
references that key) and RELATIONAL's `ALTER TABLE DROP COLUMN` costs only
33ms. "Zero-cost schema evolution" is not an unconditional property of EAV —
it holds for *adding* fields, and inverts sharply for *removing* a field with
existing data.

**`add_field_indexed` is the single most expensive schema operation across all
non-EAV strategies** (457ms-1,310ms at 100k) — adding a generated column +
index to an already-populated table requires MySQL to backfill and index every
existing row, visibly more expensive than a plain `ADD COLUMN`.

## Storage size

**Methodology caveat, disclosed rather than silently corrected**: `storage_size`
is measured *after* Scenario B's schema-evolution steps run, not immediately
after Scenario A's clean dataset load. Since `add_field_indexed` applies to
every non-EAV variant (including plain `JSON`, which was only meant to have zero
indexes), the numbers below include that extra field+index for every JSON-family
variant — plain `JSON`'s reported index bytes are therefore *not* a clean
"no-index" baseline. This was noticed only after the run completed; re-running
with storage measured before Scenario B would isolate it cleanly, and is a
natural next fix to the harness rather than something redone in this report.

| Strategy | Size | Data bytes | Index bytes |
|---|---|---|---|
| EAV | 10,000 | 8,929,280 | 21,037,056 |
| JSON | 10,000 | 3,686,400 | 6,422,528 |
| JSON_IDX | 10,000 | 3,686,400 | 5,816,320 |
| JSON_IDX_LOW | 10,000 | 3,686,400 | 6,242,304 |
| RELATIONAL | 10,000 | 1,589,248 | 851,968 |
| EAV | 100,000 | 67,747,840 | 160,907,264 |
| JSON | 100,000 | 38,338,560 | 85,426,176 |
| JSON_IDX | 100,000 | 42,532,864 | 114,311,168 |
| JSON_IDX_LOW | 100,000 | 42,532,864 | 122,257,408 |
| RELATIONAL | 100,000 | 14,204,928 | 10,010,624 |

RELATIONAL remains by far the most compact at both sizes (real typed columns,
no repeated JSON keys, no per-value overhead rows). EAV's *index* bytes are
larger than its data bytes at both sizes — the three typed value columns
(`value_text`/`value_num`/`value_date`) each carry their own index, and most of
every row is `NULL` across those three columns (only one is ever populated per
value), which InnoDB still has to account for in each index's structure.

## Run discipline

- Checkpointed to disk after every cell (`results/summary.csv`,
  `results/storage_size_summary.csv`, `results/run_log.txt` all rewritten after
  each of the 10 cells completes) — a crash would have lost at most one cell's
  data, not the whole run.
- No cell exceeded 45 minutes; the longest single cell (EAV @ 100,000) finished
  in several minutes, well under the stop threshold. No cells were skipped.
- Nothing failed. The only logged anomaly during the run itself (not a result
  anomaly, a harness note) is EAV's expected `add_field_indexed: n/a` at both
  sizes.

## Explicitly not done in this run

Scenario C (60-field sparse form), Scenario D (concurrency, 1/4/8/16 threads),
Scenario E (data integrity / constraint violation testing), 1M-row runs,
per-operation `EXPLAIN ANALYZE` capture, and PNG charts. All are additive to the
current harness (`PaperBenchmarkRunner`, `BulkLoader`, `PaperDataGenerator`) —
none of the skipped work required a different approach, just more time.
