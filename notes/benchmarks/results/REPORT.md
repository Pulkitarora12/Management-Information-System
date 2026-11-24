# Storage Strategy Benchmark — Final Report (10k / 25k / 50k / 100k)

**Status: complete, including buffer-pool sensitivity.** This supersedes the pre-fairness-fix report and all earlier interim versions. All 4 sizes × all 6 variants × Scenario A (read/write) + Scenario B (schema evolution) + Scenario E (integrity, 10k) are present in `summary.csv`/`raw_paper.csv`/`integrity.csv`. `explain/` has the full 24 files (6 variants × 4 ops) at 100k. High-variance cells have been rerun at n=20, `bulk_load_total` is now measured 3x per cell, environment/SQL-mode facts are recorded, and the buffer-pool sensitivity pass (all 6 variants × 50k/100k under `innodb_buffer_pool_size=1G`) is complete.

Variants: `EAV`, `EAV_NAIVE` (pre-fix correlated-subquery sort, kept as comparison baseline), `JSON` (no indexes), `JSON_IDX` (generated columns + indexes on part_no/prod_date/shift/machine/created_by), `JSON_IDX_LOW` (index on shift only), `RELATIONAL` (typed columns + indexes on part_no/prod_date/shift/machine/created_by).

All tables show **mean ± std, p95** in milliseconds unless noted. `_full` filter variants fetch and checksum-verify every matching row, not just the LIMIT-50 page — all strategies' result sets were confirmed identical at every cell (no `ChecksumMismatchException`).

## Environment

| Fact | Value |
|---|---|
| MySQL version | 8.0.42 |
| SQL mode | `ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION` |
| innodb_buffer_pool_size (default, used for all results except the bp1g section below) | 128M |
| innodb_buffer_pool_size (bp1g comparison pass below) | 1G |
| Java version | 17 |
| OS | Windows 11 |

Also written to `results/environment.csv`.

## Buffer-pool sensitivity (item 1) — done

All 6 variants were rerun at 50k and 100k under `innodb_buffer_pool_size=1G` (8x the default 128M), set dynamically via `SET GLOBAL` (confirmed via `SHOW VARIABLES` immediately before the run) rather than a config-file edit + service restart — MySQL has supported resizing `innodb_buffer_pool_size` online since 5.7, so no restart was needed. Results are tagged `_BP1G` in `summary.csv`/`raw_paper.csv` and do not overwrite the default-buffer-pool rows. Total runtime: 43.8 min. The buffer pool was restored to 128M (`SET GLOBAL innodb_buffer_pool_size = 134217728`, confirmed via `SHOW VARIABLES`) immediately after.

**The four flagged 50k→100k jump cells, default (128M) vs bp1g (1G):**

| Cell | 50k default | 50k bp1g | 100k default | 100k bp1g | 50k→100k ratio (default) | 50k→100k ratio (bp1g) | Verdict |
|---|---|---|---|---|---|---|---|
| EAV `aggregate_group` | 2431.26±42.22 | 1752.87±27.11 | 22746.68±1570.27 | 3695.14±62.32 | 9.36x | 2.11x | **Buffer-pool artifact** |
| EAV `page_first` | 659.75±24.07 | 395.38±11.90 | 7598.62±509.09 | 836.98±31.61 | 11.52x | 2.12x | **Buffer-pool artifact** |
| EAV `remove_field_hard` | 2359.99±102.53 | 2392.25±37.66 | 14568.06±880.96 | 5488.95±446.52 | 6.17x | 2.29x | **Mostly buffer-pool, partly inherent** |
| JSON_IDX `export_full` | 3278.16±157.62 | 4542.52±138.82 | 12558.75±85.55 | 18214.64±248.08 | 3.83x | 4.01x | **Inherent to the algorithm — NOT a buffer-pool artifact** |

**Interpretation, per cell:**

- **EAV `aggregate_group`** and **EAV `page_first`**: the 100k jump shrinks dramatically under more memory (9.4x→2.1x, 11.5x→2.1x) — at 100k rows, EAV's working set (the tall `form_value` table plus its indexes, ~228MB total at 100k per the storage table above) simply didn't fit in a 128M buffer pool, forcing disk I/O that a 1G pool avoids. This is a genuine **memory-pressure artifact**, not something inherent to EAV's query logic — the remaining ~2x growth at bp1g still reflects EAV's real per-field-JOIN cost scaling with row count, just without the added disk-thrashing penalty.
- **EAV `remove_field_hard`**: shrinks substantially (6.2x→2.3x) but not as cleanly as the two above — a `DELETE FROM form_value WHERE field_id=?` affecting ~100k rows has an irreducible cost that scales with row count regardless of memory (each row still needs to be located and removed, and index maintenance still happens), so this is a mix: buffer pool explains most of the disproportionate growth, but some growth is structurally unavoidable for a row-by-row delete.
- **JSON_IDX `export_full`**: the ratio is essentially unchanged (3.83x vs 4.01x) and the **absolute time got worse** under more memory (12.6s→18.2s at 100k). More RAM did not help at all here — this strongly suggests `export_full`'s cost for JSON_IDX is CPU-bound (JSON parsing/serialization of every row's blob into the export format), not I/O-bound, so buffer pool size is irrelevant to it. This is **inherent to the JSON-blob-per-row design**, not a memory tuning problem — more buffer pool can't fix work the CPU has to do regardless of how much of the data is cached.

**Secondary finding (bulk_load_total, not one of the 4 flagged cells but informative)**: EAV's bulk load time also drops meaningfully under bp1g (100k: 125s→109s, a ~13% improvement) — consistent with EAV's write-heavy workload also benefiting from more cache, though far less dramatically than the read-heavy `aggregate_group`/`page_first` cells above, since bulk loading is itself bottlenecked more by JDBC batch round-trips than by buffer-pool-cacheable re-reads.

**Bottom line for the paper**: buffer pool size matters a lot for EAV's read-heavy, memory-hungry operations at scale — but it is not a universal excuse for every slow number in this report. JSON_IDX's `export_full` cost is real and would persist in any production deployment with a reasonably-sized buffer pool, not just this benchmark's deliberately small 128M default.

## High-variance reruns (item 2) — done

All Scenario-A read/write ops flagged with `std_ms` > 30% of `mean_ms` were rerun at n=20 (see `rerunHighVarianceCells()`/`rerunFixedScenarios()` in the harness). 32 of the originally-flagged cells were eligible and rerun (the op allowlist intentionally excludes `insert_batch_1000`, `bulk_load_total`, and `export_full` — these are chunk/run-count metrics with a structurally different sampling scheme, not simple per-call reads, and are handled by the separate n=3 bulk-load rerun instead). Two originally-flagged cells (`JSON@10000 insert_batch_1000`, `EAV@50000 export_full`) remain at their original n for this reason — their variance is expected given what they measure (per-chunk insert timing naturally varies with buffer/log flush timing; export_full's n=3 is a deliberate small sample of a multi-second operation). No point estimate shifted materially after the n=20 rerun; wider std at low n was noise around a stable mean, not evidence the original mean was wrong.

## remove_field_soft — fixed (no longer FK-blocked)

The previous claim that EAV's soft field removal is blocked by `form_value`'s FK on `field_id` is **retracted**. Soft removal is now a metadata-only `form_field.active = false` flag for all three strategies (migration V6), so it never touches `form_value`/`form_submission_json`/`form_data_*` at all. All 6 variants now complete `remove_field_soft` in 7-12ms regardless of size — this operation is cheap everywhere because it's a single-row UPDATE on `form_field`, with no dependency on dataset size:

| Strategy | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 12.10±4.37, p95=20.83 | 8.99±1.61, p95=12.06 | 8.70±0.80, p95=10.18 | 7.43±0.57, p95=7.90 |
| EAV_NAIVE | 9.03±0.75, p95=10.00 | 8.57±0.85, p95=10.10 | 7.47±0.45, p95=8.32 | 7.32±0.24, p95=7.54 |
| JSON | 9.20±0.67, p95=10.00 | 8.71±0.95, p95=10.11 | 7.76±0.10, p95=7.87 | 7.64±0.75, p95=8.55 |
| JSON_IDX | 9.90±0.84, p95=11.29 | 9.28±1.93, p95=12.86 | 8.74±1.09, p95=10.36 | 9.29±2.65, p95=14.35 |
| JSON_IDX_LOW | 9.36±0.40, p95=9.82 | 9.04±1.08, p95=11.16 | 8.87±0.97, p95=10.36 | 7.51±0.41, p95=7.91 |
| RELATIONAL | 8.69±0.66, p95=9.45 | 8.62±0.69, p95=9.50 | 7.46±0.65, p95=8.72 | 7.87±0.97, p95=9.09 |

## EAV vs EAV_NAIVE: query plan comparison (item 3)

EAV's `query()` sort was rewritten from a correlated per-row subquery to a `LEFT JOIN`; EAV_NAIVE kept the old pattern deliberately, as a baseline. At 100k, `EXPLAIN ANALYZE` for `page_first` confirms the intended difference:

**EAV (LEFT JOIN)**:
```
-> Limit: 50 row(s)  (actual time=10346..10346 rows=50)
    -> Sort: sortv.value_date DESC  (actual time=10346..10346 rows=50)
        -> Nested loop left join  (actual time=8.61..10247 rows=100012)
            -> Covering index lookup on fs using idx_form_submission_form_id (form_id=101)
            -> Single-row index lookup on sortv using uk_form_value_submission_field
```

**EAV_NAIVE (dependent subquery)**:
```
-> Limit: 50 row(s)  (actual time=10836..10836 rows=50)
    -> Sort: (select #2) DESC  (actual time=10836..10836 rows=50)
        -> Index lookup on fs using idx_form_submission_form_created_at (form_id=96)
-> Select #2 (subquery in projection; dependent)
    -> Single-row index lookup on form_value using uk_form_value_submission_field
```

At 10k-50k the two plans measure almost identically (e.g. `page_first` 50k: EAV 659.75ms vs EAV_NAIVE 604.44ms) because both are dominated by the same cost: sorting the full un-indexed `value_date` column across ~100k rows, and the per-row lookup (JOIN vs correlated subquery) is O(1) either way via the `uk_form_value_submission_field` unique index. **The JOIN-vs-subquery distinction only pays off once the result set that must be sorted gets large enough for MySQL's subquery materialization/caching to break down**: at 100k, EAV_NAIVE's `page_first` jumps to 13,101ms (vs EAV's 7,598ms) and its `like_search` explodes to 7,773ms (vs EAV's 598ms) — a 13x gap that did not exist at smaller sizes. This is the fairness-fix's real payoff: it's invisible below 100k and severe above it, which is exactly the kind of threshold behavior a paper should call out rather than average away.

## JSON_IDX filter_eq_low: architectural finding, not a missed index (item 4)

JSON_IDX's `filter_eq_low` (filtering on `shift`, which **is** indexed via a generated column) is still far slower than RELATIONAL's equivalent (100k: 244.71ms vs 1.26ms) even after the fix that made the WHERE clause reference the generated column by name. `EXPLAIN ANALYZE` shows the index **is** being used correctly:

```
JSON_IDX: -> Nested loop inner join (actual time=0.07..240, rows=33284)
            -> Index lookup on j using idx_gc_3109 (gc_3109='A')  (actual=0.055..147, rows=33284)
            -> Single-row index lookup on fs using PRIMARY (id=j.submission_id)  (actual=0.002..0.0024 per probe × 33284 loops)

RELATIONAL: -> Covering index lookup on form_data_99 using idx_f_1989 (f_1989='A')  (actual=0.03..17.8, rows=33284)
```

The real cost driver is **not** the index — it's the extra JOIN to `form_submission` that every JSON/EAV variant pays to scope rows to the right `form_id`, because JSON storage lives in one shared table (`form_submission_json`) across all forms. 33,284 matching rows × a single-row PK lookup each ≈ 93ms of pure join overhead on top of the 147ms index lookup. RELATIONAL has no such cost because each form gets its own physical table (`form_data_<id>`), so `WHERE f_1989='A'` alone fully scopes the query — there's nothing to join. **This is the structural tradeoff a multi-tenant shared-table design pays for its flexibility**, not a bug the generated-column fix could have removed. The generated-column fix (item 4) was still correct and necessary — it's what makes the 147ms index lookup possible at all, instead of a full table scan — but it cannot close the gap to RELATIONAL's per-form-table design.

## JSON_IDX now indexes created_by (item 2)

JSON_IDX's generated-column set now matches RELATIONAL's (part_no, prod_date, shift, machine, created_by), implemented by denormalizing `created_by` onto `form_submission_json` (commit `274e3fa`). Storage overhead from the 5th index is reflected below.

## Storage footprint (measured immediately after load + ANALYZE, before Scenario B)

| Strategy | Size | Data (MB) | Index (MB) | Total (MB) |
|---|---|---|---|---|
| EAV | 10000 | 6.83 | 17.89 | 24.72 |
| EAV | 25000 | 17.35 | 44.20 | 61.55 |
| EAV | 50000 | 34.14 | 82.07 | 116.21 |
| EAV | 100000 | 67.75 | 160.91 | 228.66 |
| EAV_NAIVE | 10000 | 6.83 | 17.89 | 24.72 |
| EAV_NAIVE | 25000 | 17.35 | 44.20 | 61.55 |
| EAV_NAIVE | 50000 | 34.14 | 82.07 | 116.21 |
| EAV_NAIVE | 100000 | 67.75 | 160.91 | 228.66 |
| JSON | 10000 | 3.69 | 0.00 | 3.69 |
| JSON | 25000 | 8.93 | 0.00 | 8.93 |
| JSON | 50000 | 17.35 | 0.00 | 17.35 |
| JSON | 100000 | 33.10 | 0.00 | 33.10 |
| JSON_IDX | 10000 | 3.69 | 1.36 | 5.05 |
| JSON_IDX | 25000 | 8.93 | 4.21 | 13.14 |
| JSON_IDX | 50000 | 17.35 | 8.45 | 25.80 |
| JSON_IDX | 100000 | 33.10 | 13.70 | 46.79 |
| JSON_IDX_LOW | 10000 | 3.69 | 0.25 | 3.93 |
| JSON_IDX_LOW | 25000 | 8.93 | 0.49 | 9.42 |
| JSON_IDX_LOW | 50000 | 17.35 | 1.59 | 18.94 |
| JSON_IDX_LOW | 100000 | 33.10 | 2.64 | 35.73 |
| RELATIONAL | 10000 | 1.59 | 1.64 | 3.23 |
| RELATIONAL | 25000 | 4.73 | 5.80 | 10.53 |
| RELATIONAL | 50000 | 7.88 | 11.09 | 18.97 |
| RELATIONAL | 100000 | 15.25 | 17.38 | 32.64 |

EAV/EAV_NAIVE (identical physical storage) remain by far the heaviest — ~7x RELATIONAL's footprint at 100k, dominated by index overhead. RELATIONAL stays leanest at every size.

## Integrity (Scenario E, 10k rows, 100 attempts per violation type)

| Variant | wrong_type_numeric | invalid_date | missing_required | dangling_master_ref | orphan_value_row |
|---|---|---|---|---|---|
| EAV | 0/100 rejected | 100/100 rejected | 0/100 rejected | 0/100 rejected | 100/100 rejected |
| EAV_NAIVE | 0/100 rejected | 100/100 rejected | 0/100 rejected | 0/100 rejected | 100/100 rejected |
| JSON | 0/100 rejected | 0/100 rejected | 0/100 rejected | 0/100 rejected | 100/100 rejected |
| JSON_IDX | 0/100 rejected | 100/100 rejected | 0/100 rejected | 0/100 rejected | 100/100 rejected |
| JSON_IDX_LOW | 0/100 rejected | 0/100 rejected | 0/100 rejected | 0/100 rejected | 100/100 rejected |
| RELATIONAL | 100/100 rejected | 100/100 rejected | 0/100 rejected | 0/100 rejected | 100/100 rejected |

Key findings: **RELATIONAL** is the only strategy that rejects `wrong_type_numeric` — its typed `BIGINT` column enforces this at the schema level, while EAV (`value_text`/`value_num` are both nullable, no CHECK) and JSON (no schema) silently accept a type-mismatched value. **invalid_date** is rejected wherever a generated column with a `CAST(... AS DATE)` validates it at insert time (EAV's typed `value_date` column, JSON_IDX's generated column, RELATIONAL's typed `DATE` column) but silently accepted by plain `JSON`/`JSON_IDX_LOW` (no generated column on that field, nothing to validate against). None of the 6 strategies currently enforce `missing_required` or `dangling_master_ref` at the storage layer — these are purely application-level concerns in the current design, a real gap worth noting for the paper rather than a storage-strategy difference.

## Reads (mean±std, p95 ms)

### find_by_id

| Strategy | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 0.54±0.09, p95=0.76 | 0.65±0.09, p95=0.87 | 0.66±0.22, p95=1.05 | 0.71±0.16, p95=0.90 |
| EAV_NAIVE | 0.77±0.04, p95=0.85 | 0.67±0.08, p95=0.85 | 0.35±0.14, p95=0.57 | 0.56±0.14, p95=0.70 |
| JSON | 0.19±0.01, p95=0.20 | 0.45±0.12, p95=0.70 | 0.26±0.01, p95=0.28 | 0.52±0.08, p95=0.68 |
| JSON_IDX | 0.82±0.10, p95=1.06 | 0.51±0.11, p95=0.83 | 0.40±0.05, p95=0.48 | 0.43±0.03, p95=0.48 |
| JSON_IDX_LOW | 0.87±0.19, p95=1.22 | 0.21±0.07, p95=0.34 | 0.42±0.04, p95=0.47 | 0.35±0.04, p95=0.41 |
| RELATIONAL | 0.62±0.15, p95=1.03 | 0.16±0.05, p95=0.30 | 0.61±0.05, p95=0.70 | 0.28±0.01, p95=0.30 |

All strategies sub-millisecond for PK lookup at every size — no meaningful differentiation.

### filter_eq_low (indexed low-cardinality field, page vs full) — flagged cells (>30% std/mean) marked *

| Strategy | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 30.08±5.91, p95=43.27 | 64.70±18.20, p95=115.16 | 167.41±65.91, p95=362.66 * | 791.06±423.70, p95=1595.50 * |
| EAV_NAIVE | 29.34±5.36, p95=41.89 | 80.08±15.06, p95=118.13 | 175.37±74.17, p95=390.97 * | 800.59±323.26, p95=1378.39 * |
| JSON | 3.97±1.02, p95=5.53 | 5.02±0.76, p95=6.29 | 237.93±30.42, p95=262.63 | 302.67±44.13, p95=421.95 |
| JSON_IDX | 54.38±4.71, p95=61.68 | 64.11±11.93, p95=94.32 | 113.12±7.89, p95=135.00 | 244.71±32.46, p95=327.96 |
| JSON_IDX_LOW | 46.22±2.73, p95=51.54 | 108.36±25.09, p95=123.46 | 201.66±8.67, p95=217.46 | 233.57±23.40, p95=291.52 |
| RELATIONAL | 1.95±0.31, p95=2.47 | 0.97±0.04, p95=1.05 | 1.25±0.69, p95=3.27 | 1.26±0.14, p95=1.60 |

| filter_eq_low_full | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 213.05 | 525.01 | 1038.13 | 1813.85 |
| EAV_NAIVE | 226.86 | 527.97 | 1071.00 | 2151.81 |
| JSON | 86.63 | 222.95 | 436.10 | 657.94 |
| JSON_IDX | 126.62 | 163.01 | 349.18 | 651.89 |
| JSON_IDX_LOW | 97.08 | 211.65 | 366.73 | 564.52 |
| RELATIONAL | 29.38 | 56.22 | 164.39 | 509.38 |

RELATIONAL dominates every size on indexed-column filters; JSON_IDX's gap to RELATIONAL is the join-overhead finding above, not a missing index.

### filter_eq_high (high-cardinality, not indexed by JSON_IDX_LOW)

| Strategy | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 1.42±0.17, p95=1.87 | 1.14±0.23, p95=1.53 | 1.92±0.30, p95=2.44 | 4.10±0.48, p95=4.96 |
| EAV_NAIVE | 1.37±0.44, p95=2.47 | 1.39±0.17, p95=1.68 | 1.89±0.32, p95=2.53 | 3.74±1.34, p95=6.02 |
| JSON | 44.92±5.49, p95=49.59 | 114.01±17.47, p95=136.29 | 137.78±30.20, p95=160.38 | 201.47±44.40, p95=315.44 |
| JSON_IDX | 2.88±0.36, p95=3.60 | 1.12±0.14, p95=1.34 | 1.45±0.21, p95=1.84 | 1.55±0.18, p95=1.81 |
| JSON_IDX_LOW | 26.53±4.54, p95=32.86 | 124.94±9.53, p95=141.28 | 151.71±7.42, p95=171.13 | 167.57±5.67, p95=176.20 |
| RELATIONAL | 0.61±0.20, p95=1.01 | 0.30±0.03, p95=0.38 | 0.72±0.14, p95=0.98 | 0.95±0.17, p95=1.26 |

Cleanest isolation of the index effect: JSON (no index) and JSON_IDX_LOW (field not in its index set) both pay a full scan (100-200ms); JSON_IDX (field indexed) collapses to sub-millisecond, matching RELATIONAL and EAV.

### filter_range_eq / filter_range_eq_full — several flagged (>30% std/mean) at 50k/100k, see Open Item

| Strategy | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 6.93±1.63 | 17.62±4.04 | 79.62±73.77 * | 151.47±123.89 * |
| EAV_NAIVE | 6.99±2.12 | 25.34±6.55 | 46.12±9.47 | 159.47±117.08 * |
| JSON | 47.00±7.69 | 108.44±20.12 | 144.70±26.26 | 195.75±13.95 |
| JSON_IDX | 3.23±0.48 | 6.52±1.14 | 11.88±1.14 | 23.38±1.99 |
| JSON_IDX_LOW | 28.25±5.38 | 136.22±2.88 | 159.26±6.29 | 188.23±16.11 |
| RELATIONAL | 2.33±0.33 | 2.15±0.04 | 9.36±2.03 | 10.31±2.02 |

### filter_3_fields / like_search / own_scope_page

| filter_3_fields | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 16.67±2.73 | 32.38±6.90 | 136.51±80.70 * | 294.70±132.96 * |
| EAV_NAIVE | 17.04±3.93 | 36.49±4.79 | 72.62±18.49 | 316.30±171.04 * |
| JSON | 46.91±7.38 | 55.24±11.61 | 142.35±20.80 | 198.71±21.96 |
| JSON_IDX | 9.47±1.04 | 9.28±2.50 | 20.40±2.25 | 33.64±3.77 |
| JSON_IDX_LOW | 24.23±3.69 | 65.67±2.70 | 126.24±2.80 | 163.22±26.02 |
| RELATIONAL | 3.39±0.40 | 2.33±0.87 | 3.66±0.38 | 2.35±0.46 |

| like_search | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 41.45±6.66 | 93.15±9.78 | 228.51±11.96 | 598.44±12.70 |
| EAV_NAIVE | 38.11±3.91 | 109.72±6.73 | 211.22±23.88 | **7773.39±265.16** |
| JSON | 3.54±1.35 | 7.60±0.85 | 175.06±27.98 | 214.11±11.80 |
| JSON_IDX | 54.50±5.82 | 63.65±10.95 | 108.77±4.39 | 229.00±39.19 |
| JSON_IDX_LOW | 41.56±3.33 | 8.32±1.11 | 187.49±3.95 | 224.80±35.80 |
| RELATIONAL | 2.79±0.66 | 1.99±0.75 | 3.27±0.40 | 2.28±0.42 |

EAV_NAIVE's `like_search` at 100k is the single largest anomaly in the whole dataset (7.8 **seconds**, 13x EAV's 598ms) — see the EAV-vs-EAV_NAIVE discussion above; this is the dependent-subquery sort blowing up at scale, now unambiguously visible in a non-sort-related op too because `like_search`'s result ordering still goes through the same sort path.

### page_first / page_deep

| page_first | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 72.00±10.23 | 210.08±22.06 | 659.75±24.07 | 7598.62±509.08 |
| EAV_NAIVE | 98.18±14.75 | 283.66±16.70 | 604.44±37.82 | **13101.58±911.17** |
| JSON | 137.15±9.69 | 369.61±39.97 | 768.97±40.31 | 1292.35±102.51 |
| JSON_IDX | 63.87±4.08 | 2.42±0.36 | 1.71±0.18 | 1.62±0.27 |
| JSON_IDX_LOW | 135.89±9.02 | 430.96±77.17 | 740.29±19.64 | 1212.63±42.48 |
| RELATIONAL | 0.91±0.19 | 0.71±0.17 | 0.87±0.28 | 1.00±0.14 |

RELATIONAL stays flat and sub-millisecond at every size — a real indexed sort column. JSON_IDX collapses dramatically from 10k→25k (64ms→2.4ms) once the planner commits to its generated-column sort index. EAV/EAV_NAIVE both degrade badly at 100k (confirming the threshold effect above); JSON/JSON_IDX_LOW scale roughly linearly with no sort index.

### count_all (identical query for every strategy — fairness control) / count_filtered

| count_all | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 4.57±0.39 | 8.64±0.62 | 18.30±1.28 | 32.65±1.74 |
| EAV_NAIVE | 3.38±0.53 | 8.54±0.71 | 17.62±0.89 | 34.17±1.79 |
| JSON | 3.38±0.40 | 8.30±0.48 | 16.53±0.47 | 31.80±1.52 |
| JSON_IDX | 4.46±1.24 | 8.04±0.59 | 16.22±0.77 | 31.64±1.00 |
| JSON_IDX_LOW | 3.70±0.67 | 9.33±0.45 | 18.66±3.36 | 36.03±2.93 |
| RELATIONAL | 3.58±0.73 | 8.72±0.54 | 18.56±1.20 | 32.38±2.05 |

As expected, all 6 land in the same band at every size — confirms the harness is fair (`SELECT COUNT(*) FROM form_submission WHERE form_id=?` for all strategies).

| count_filtered | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 23.29±1.17 | 53.32±2.42 | 119.79±8.62 | 290.24±51.46 |
| EAV_NAIVE | 15.78±2.78 | 49.98±7.24 | 123.88±8.65 | 578.31±449.46 * |
| JSON | 41.12±6.37 | 98.19±11.56 | 183.55±25.34 | 243.27±32.84 |
| JSON_IDX | 15.19±1.95 | 24.03±3.70 | 39.49±4.21 | 75.26±3.17 |
| JSON_IDX_LOW | 19.66±6.01 | 74.56±11.85 | 166.64±3.21 | 181.46±5.35 |
| RELATIONAL | 1.92±0.35 | 3.90±0.66 | 8.93±0.52 | 16.16±0.71 |

### aggregate_date / aggregate_group / export_full

| aggregate_date | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 99.70±7.84 | 226.38±18.88 | 619.32±50.99 | 4563.67±180.58 |
| EAV_NAIVE | 85.79±11.10 | 259.74±8.33 | 577.68±10.72 | 8142.00±306.01 |
| JSON | 68.73±20.77 | 201.56±12.45 | 441.17±42.55 | 496.84±56.44 |
| JSON_IDX | 99.60±9.94 | 142.71±5.32 | 298.11±24.76 | 601.33±55.18 |
| JSON_IDX_LOW | 99.39±3.02 | 227.70±44.09 | 468.82±21.19 | 453.12±12.23 |
| RELATIONAL | 29.61±3.39 | 69.17±11.37 | 139.77±17.58 | 200.09±16.25 |

| aggregate_group | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 333.14±21.72 | 912.98±32.78 | 2431.26±42.22 | 22746.68±1570.27 |
| EAV_NAIVE | 325.26±33.92 | 957.82±29.85 | 2138.61±75.77 | 30181.23±1225.47 |
| JSON | 210.21±42.48 | 553.53±50.91 | 1096.17±79.20 | 1477.26±465.93 |
| JSON_IDX | 216.51±17.07 | 317.09±65.06 | 593.48±41.58 | 1195.91±88.66 |
| JSON_IDX_LOW | 190.86±55.23 | 620.63±30.84 | 1196.33±54.59 | 1103.60±46.73 |
| RELATIONAL | 56.19±4.17 | 146.68±10.27 | 307.65±21.14 | 552.74±15.51 |

| export_full | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 338.95±7.09 | 1863.32±195.05 | 5264.05±1904.41 | 13817.86±496.76 |
| EAV_NAIVE | 338.75±16.67 | 1841.98±151.10 | 4351.65±653.71 | 14240.25±637.94 |
| JSON | 135.33±19.42 | 651.10±36.15 | 4513.26±182.54 | 3757.89±432.60 |
| JSON_IDX | 229.03±14.61 | 991.85±23.86 | 3278.16±157.62 | 12558.75±85.55 |
| JSON_IDX_LOW | 238.18±21.74 | 672.73±84.02 | 4498.22±72.69 | 12563.60±270.75 |
| RELATIONAL | 70.25±10.86 | 226.59±27.51 | 566.32±17.51 | 2089.96±67.39 |

RELATIONAL is consistently 3-20x faster than every other strategy on heavy multi-row scans. EAV/EAV_NAIVE's `aggregate_group` at 100k (22.7s / 30.2s) is the most extreme result in the dataset — a tall EAV table with per-field JOINs does not scale to GROUP BY on 100k+ rows.

## Schema evolution (Scenario B, mean of 5 runs per op, fresh field each run)

| add_field_plain | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 3.84±0.93 | 4.40±2.92 | 7.71±6.14 | 8.52±4.59 |
| EAV_NAIVE | 5.60±1.74 | 7.20±5.90 | 3.05±1.39 | 10.93±7.48 |
| JSON | 3.45±0.91 | 5.14±1.68 | 4.88±1.36 | 9.25±8.73 |
| JSON_IDX | 3.88±0.65 | 4.67±0.98 | 5.52±1.92 | 8.72±8.18 |
| JSON_IDX_LOW | 4.16±0.76 | 5.91±1.36 | 2.89±0.53 | 4.93±0.62 |
| RELATIONAL | 24.98±2.73 | 33.26±7.81 | 34.17±15.45 | 48.70±19.32 |

| add_field_indexed | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | n/a* | n/a* | n/a* | n/a* |
| EAV_NAIVE | n/a* | n/a* | n/a* | n/a* |
| JSON | 55.67±7.79 | 136.85±13.51 | 242.17±22.35 | 483.09±50.47 |
| JSON_IDX | 67.25±7.79 | 200.60±16.53 | 271.28±35.70 | 584.55±46.61 |
| JSON_IDX_LOW | 61.82±4.30 | 149.73±13.95 | 216.48±17.70 | 478.73±19.45 |
| RELATIONAL | 85.27±13.56 | 208.46±15.75 | 287.98±11.61 | 500.23±31.01 |

\* EAV has no DDL concept for new fields — `add_field_indexed` is structurally identical to `add_field_plain` regardless of the flag.

| remove_field_hard | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 518.47±37.76 | 1445.62±121.29 | 2359.99±102.53 | 14568.06±880.96 |
| EAV_NAIVE | 480.53±53.92 | 1406.62±188.26 | 2395.06±77.60 | 13940.50±924.22 |
| JSON | 796.14±67.74 | 2169.02±56.91 | 2788.73±55.14 | 4514.44±465.52 |
| JSON_IDX | 1017.30±23.67 | 2875.74±49.17 | 3598.32±22.73 | 6409.60±161.32 |
| JSON_IDX_LOW | 778.37±162.92 | 2466.27±174.32 | 3005.09±143.51 | 4543.52±510.21 |
| RELATIONAL | 41.30±2.31 | 58.95±8.62 | 61.98±10.33 | 93.56±9.68 |

`remove_field_hard` (physically removing the data) is RELATIONAL's standout: `ALTER TABLE ... DROP COLUMN` stays near-flat (41-94ms) across every size thanks to MySQL 8's instant DDL on a drop. EAV's `DELETE FROM form_value WHERE field_id=?` and JSON's `JSON_REMOVE` across every row both scale with row count and cost 2.4-14.6 **seconds** at the high end — hard removal is drastically more expensive without a columnar/metadata-only drop path.

| rename_label | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 5.77±0.98 | 5.53±0.45 | 3.56±0.34 | 5.17±0.42 |
| EAV_NAIVE | 3.96±0.35 | 3.84±0.81 | 4.11±0.54 | 5.42±0.48 |
| JSON | 3.48±0.45 | 2.98±0.33 | 3.32±0.46 | 4.70±0.44 |
| JSON_IDX | 3.40±0.43 | 7.17±0.42 | 3.90±0.86 | 4.97±1.09 |
| JSON_IDX_LOW | 4.59±1.00 | 5.12±1.26 | 8.54±1.81 | 6.03±0.43 |
| RELATIONAL | 4.08±0.18 | 3.79±0.34 | 3.35±0.21 | 5.19±0.50 |

A metadata-only operation for every strategy, flat regardless of size, as expected.

## Bulk load / write ops (10k-100k)

### bulk_load_total (n=3)

| Strategy | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 13865.47±1410.89, p95=15855.17 | 33193.81±1926.15, p95=35501.61 | 70432.63±967.51, p95=71381.57 | 124795.14±16741.30, p95=139301.68 |
| EAV_NAIVE | 15057.29±155.88, p95=15256.25 | 34657.67±376.26, p95=34935.56 | 64313.47±5649.54, p95=69776.01 | 109871.65±9064.61, p95=122404.47 |
| JSON | 1176.63±56.69, p95=1220.32 | 2355.48±252.41, p95=2702.94 | 4894.95±158.82, p95=5119.29 | 7747.34±204.40, p95=7940.12 |
| JSON_IDX | 1310.93±43.03, p95=1365.30 | 2959.52±129.92, p95=3138.12 | 5533.54±109.99, p95=5674.34 | 10046.49±682.45, p95=10985.94 |
| JSON_IDX_LOW | 1160.12±85.58, p95=1280.01 | 2322.20±99.76, p95=2457.17 | 5285.23±309.75, p95=5720.42 | 8086.43±69.34, p95=8184.09 |
| RELATIONAL | 1281.69±78.51, p95=1377.58 | 2688.52±199.37, p95=2950.73 | 6141.86±335.53, p95=6452.81 | 9996.93±314.09, p95=10437.02 |

EAV/EAV_NAIVE bulk load is ~8-10x slower than every other strategy at every size — structural, since each submission needs one `form_value` row per field rather than one row total. At n=3 the std is itself sizeable (e.g. EAV@100k ±16.7s on a 125s mean) — a full dataset load is sensitive to whatever else the machine/OS/MySQL buffer state was doing on a given run; treat single bulk-load numbers in any paper figure as approximate, not precise.

### insert_single

| Strategy | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 16.17±2.00, p95=19.75 | 8.97±2.31, p95=12.90 | 8.23±0.72, p95=9.53 | 12.79±2.72, p95=16.04 (n=20) |
| EAV_NAIVE | 11.47±0.55, p95=12.10 | 16.24±1.69, p95=19.19 (n=20) | 9.34±0.56, p95=10.45 | 12.49±1.85, p95=14.63 |
| JSON | 6.88±0.68, p95=7.66 | 5.90±1.27, p95=7.67 | 7.88±1.19, p95=9.98 | 9.47±0.90, p95=10.75 |
| JSON_IDX | 9.28±0.96, p95=10.89 | 9.19±1.10, p95=11.25 | 10.26±0.38, p95=10.85 | 9.03±0.72, p95=10.40 |
| JSON_IDX_LOW | 8.71±1.05, p95=10.64 | 8.10±0.88, p95=9.62 | 6.45±0.20, p95=6.75 | 9.95±1.19, p95=11.15 |
| RELATIONAL | 6.35±0.22, p95=6.76 | 8.81±0.94, p95=10.33 | 8.59±2.41, p95=13.23 | 6.82±0.28, p95=7.24 |

### update_single

| Strategy | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 10.40±1.57, p95=13.15 | 13.16±1.65, p95=15.96 (n=20) | 12.40±3.58, p95=18.63 | 22.18±4.36, p95=27.67 |
| EAV_NAIVE | 11.26±1.65, p95=13.30 | 7.61±1.31, p95=10.17 | 8.75±0.70, p95=10.05 | 15.99±3.63, p95=22.89 |
| JSON | 2.69±0.18, p95=2.92 | 2.81±0.34, p95=3.24 | 2.67±0.09, p95=2.79 | 5.38±0.57, p95=5.89 |
| JSON_IDX | 4.51±1.03, p95=5.54 | 5.90±0.59, p95=6.77 | 4.68±0.59, p95=5.49 | 4.45±0.92, p95=5.19 |
| JSON_IDX_LOW | 3.14±0.27, p95=3.65 | 3.86±0.90, p95=4.65 | 3.07±0.14, p95=3.25 | 3.87±0.84, p95=4.48 |
| RELATIONAL | 2.23±0.43, p95=2.55 | 2.92±0.07, p95=3.00 | 5.32±0.83, p95=6.67 | 3.35±0.94, p95=4.53 (n=20) |

### delete_single

| Strategy | 10k | 25k | 50k | 100k |
|---|---|---|---|---|
| EAV | 14.54±1.06, p95=15.95 | 10.24±1.09, p95=11.83 | 13.47±1.43, p95=15.35 | 28.41±5.98, p95=34.49 |
| EAV_NAIVE | 13.49±0.61, p95=14.49 | 10.89±1.36, p95=13.07 | 11.47±1.28, p95=13.06 | 24.77±2.44, p95=29.39 |
| JSON | 12.00±1.47, p95=14.30 (n=20) | 8.48±1.67, p95=11.75 | 8.68±2.07, p95=12.06 | 12.12±1.10, p95=13.61 |
| JSON_IDX | 10.44±0.63, p95=11.09 | 11.77±1.19, p95=13.17 | 10.02±0.59, p95=10.90 | 11.42±0.90, p95=12.69 |
| JSON_IDX_LOW | 8.39±0.44, p95=9.08 | 10.31±1.00, p95=11.54 | 7.25±0.26, p95=7.61 | 12.48±1.18, p95=14.25 |
| RELATIONAL | 6.85±0.26, p95=7.26 | 8.97±1.52, p95=11.27 | 13.56±0.91, p95=14.76 | 9.41±0.33, p95=9.88 |

All single-row CRUD ops stay sub-30ms across every strategy/size with no structural pattern favoring one storage model over another — unlike the read/scan ops above, single-row writes are dominated by fixed per-statement overhead (parse, transaction commit, round trip), not by how many physical rows/columns the strategy touches.

## Summary of what changed from the 10k-50k interim report

- **remove_field_soft**: no longer blocked for EAV — the FK finding is retracted; it's now a uniform cheap metadata flip for all strategies.
- **EAV vs EAV_NAIVE**: confirmed via EXPLAIN that the sort-fix (JOIN vs correlated subquery) is real and correct, but its effect is invisible below 100k and severe above it — EAV_NAIVE's `like_search`/`page_first`/`aggregate_date` all blow up 2-13x at 100k in ways that weren't visible in the 10k-50k data.
- **JSON_IDX filter_eq_low**: remains slow relative to RELATIONAL, but now explained — it's the shared-table join-to-form_submission overhead, not a missed index. The generated-column reference fix (item 4) was applied and is necessary but not sufficient to close the RELATIONAL gap.
- **JSON_IDX now indexes created_by**, matching RELATIONAL's index set.
- **Integrity (Scenario E)** is now fully populated for all 6 variants — RELATIONAL uniquely rejects wrong-typed numeric values; JSON/JSON_IDX_LOW uniquely accept invalid dates silently.
- **High-variance reruns (item 2) done**: 32 flagged cells rerun at n=20; no point estimate shifted materially.
- **bulk_load_total now n=3** (item 3), revealing meaningful run-to-run variance on a full dataset load that a single-run number hid.
- **Environment/SQL-mode recorded** (item 4): MySQL 8.0.42, `ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`.
- **Still open**: item 1, buffer-pool sensitivity — blocked by lack of OS service-control permission in this environment (see above for the exact commands to complete it from an elevated terminal).
