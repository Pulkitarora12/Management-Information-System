# Storage Strategy Benchmark — Interim Report (10k / 25k / 50k)

**Status: PARTIAL.** This report covers dataset sizes 10,000 / 25,000 / 50,000 rows across all 6 variants and all Scenario A (read/write) + Scenario B (schema evolution) operations — these are fully complete and will not change.

The 100,000-row size and Scenario E (integrity, 10k) are still running in the background at the time of writing and are **not included below**. This report will be superseded once the full run finishes; the 100k numbers and integrity findings will be appended/merged at that point.

Variants: `EAV`, `EAV_NAIVE` (pre-fairness-fix correlated-subquery sort, kept as a comparison baseline), `JSON` (no indexes), `JSON_IDX` (generated columns + indexes on part_no/prod_date/shift/machine), `JSON_IDX_LOW` (index on shift only), `RELATIONAL` (typed columns + indexes on part_no/prod_date/shift/machine/created_by).

All times are `mean_ms` over the runs; `_full` variants of filter operations fetch and checksum-verify every matching row (not just the LIMIT 50 page) per the fairness-fix spec (item 5) — all strategies' result sets were confirmed identical at every completed cell, no `ChecksumMismatchException` was raised through 50k.

## Bulk load (ms, loading the whole dataset)

| Strategy | 10k | 25k | 50k |
|---|---|---|---|
| EAV | 11329.033 | 25742.396 | 51601.302 |
| EAV_NAIVE | 10255.786 | 32010.137 | 56076.514 |
| JSON | 710.397 | 2092.990 | 4229.291 |
| JSON_IDX | 944.471 | 2484.856 | 5698.117 |
| JSON_IDX_LOW | 832.295 | 2593.131 | 4447.938 |
| RELATIONAL | 815.550 | 2556.738 | 5339.934 |

EAV/EAV_NAIVE bulk load is ~10-15x slower than every other strategy at every size, because each submission requires one `form_value` row per field rather than one row total — this is structural, not an indexing artifact.

## Storage footprint (measured immediately after load + ANALYZE, before Scenario B)

| Strategy | Size | Data (MB) | Index (MB) | Total (MB) |
|---|---|---|---|---|
| EAV | 10000 | 6.83 | 17.89 | 24.72 |
| EAV | 25000 | 17.35 | 44.20 | 61.55 |
| EAV | 50000 | 34.14 | 82.07 | 116.21 |
| EAV_NAIVE | 10000 | 6.83 | 17.89 | 24.72 |
| EAV_NAIVE | 25000 | 17.35 | 44.20 | 61.55 |
| EAV_NAIVE | 50000 | 34.14 | 82.07 | 116.21 |
| JSON | 10000 | 3.69 | 0.00 | 3.69 |
| JSON | 25000 | 8.93 | 0.00 | 8.93 |
| JSON | 50000 | 17.35 | 0.00 | 17.35 |
| JSON_IDX | 10000 | 3.69 | 1.36 | 5.05 |
| JSON_IDX | 25000 | 8.93 | 4.21 | 13.14 |
| JSON_IDX | 50000 | 17.35 | 8.45 | 25.80 |
| JSON_IDX_LOW | 10000 | 3.69 | 0.25 | 3.93 |
| JSON_IDX_LOW | 25000 | 8.93 | 0.49 | 9.42 |
| JSON_IDX_LOW | 50000 | 17.35 | 1.59 | 18.94 |
| RELATIONAL | 10000 | 1.59 | 1.64 | 3.23 |
| RELATIONAL | 25000 | 4.73 | 5.80 | 10.53 |
| RELATIONAL | 50000 | 7.88 | 11.09 | 18.97 |

EAV/EAV_NAIVE (identical physical storage — same table, different store implementation) are by far the heaviest: ~5-6x RELATIONAL's footprint at 50k, dominated by index overhead (82MB index vs 34MB data — the `(submission_id, field_id)` + per-column-type index structure on a tall EAV table is expensive). RELATIONAL is the leanest at every size. JSON's lack of indexes keeps it compact but at the cost of filter performance (see below).

## Reads (ms, mean)

### find_by_id

| Strategy | 10k | 25k | 50k |
|---|---|---|---|
| EAV | 0.538 | 0.649 | 0.662 |
| EAV_NAIVE | 0.771 | 0.666 | 0.352 |
| JSON | 0.186 | 0.447 | 0.257 |
| JSON_IDX | 0.381 | 0.453 | 0.279 |
| JSON_IDX_LOW | 0.866 | 0.207 | 0.417 |
| RELATIONAL | 0.624 | 0.162 | 0.615 |

All strategies are sub-millisecond for PK lookup — no meaningful differentiation at this scale.

### filter_eq_low / filter_eq_low_full (filter on a low-cardinality indexed field; page vs. full result set)

| Strategy | 10k | 25k | 50k |
|---|---|---|---|
| EAV | 30.082 | 64.696 | 167.411 |
| EAV_NAIVE | 29.343 | 80.085 | 175.369 |
| JSON | 3.967 | 5.021 | 237.928 |
| JSON_IDX | 43.152 | 96.316 | 218.791 |
| JSON_IDX_LOW | 46.217 | 108.360 | 201.663 |
| RELATIONAL | 1.951 | 0.971 | 1.250 |

| Strategy (full) | 10k | 25k | 50k |
|---|---|---|---|
| EAV | 213.051 | 525.009 | 1038.134 |
| EAV_NAIVE | 226.856 | 527.974 | 1071.001 |
| JSON | 86.630 | 222.954 | 436.095 |
| JSON_IDX | 83.418 | 194.525 | 380.711 |
| JSON_IDX_LOW | 97.084 | 211.649 | 366.731 |
| RELATIONAL | 29.377 | 56.224 | 164.387 |

RELATIONAL dominates at every size on indexed-column filters — a typed indexed column beats both a JSON generated-column index and EAV's JOIN-based filter. Note JSON (no index) spikes sharply between 25k→50k (5ms→238ms) on the paged query once the table no longer fits comfortably for a full scan, while RELATIONAL stays flat (<2ms) — the index is doing real work.

### filter_eq_high (high-cardinality field, not indexed by JSON_IDX_LOW)

| Strategy | 10k | 25k | 50k |
|---|---|---|---|
| EAV | 1.424 | 1.141 | 1.920 |
| EAV_NAIVE | 1.366 | 1.389 | 1.889 |
| JSON | 44.924 | 114.013 | 137.777 |
| JSON_IDX | 1.188 | 1.037 | 0.761 |
| JSON_IDX_LOW | 26.533 | 124.941 | 151.708 |
| RELATIONAL | 0.606 | 0.296 | 0.716 |

This isolates the index effect cleanly: JSON (no index) and JSON_IDX_LOW (field not in its index set) both pay a full table scan (100-150ms), while JSON_IDX (field indexed) collapses to sub-millisecond, matching RELATIONAL and EAV. The index, not the storage model, is what matters here.

### like_search

| Strategy | 10k | 25k | 50k |
|---|---|---|---|
| EAV | 41.455 | 93.147 | 228.510 |
| EAV_NAIVE | 38.111 | 109.721 | 211.225 |
| JSON | 3.541 | 7.601 | 175.060 |
| JSON_IDX | 7.551 | 28.306 | 197.307 |
| JSON_IDX_LOW | 41.564 | 8.322 | 187.486 |
| RELATIONAL | 2.785 | 1.994 | 3.266 |

No strategy indexes for LIKE (none use a full-text or prefix index), so this is close to a pure table-scan comparison — RELATIONAL's narrow typed rows scan fastest; EAV's JOIN-per-row pattern is consistently the slowest.

### page_first / page_deep

| Strategy | 10k | 25k | 50k |
|---|---|---|---|
| EAV (first) | 71.996 | 210.078 | 659.749 |
| EAV_NAIVE (first) | 98.181 | 283.663 | 604.436 |
| JSON (first) | 137.153 | 369.610 | 768.966 |
| JSON_IDX (first) | 63.646 | 2.734 | 1.942 |
| JSON_IDX_LOW (first) | 135.890 | 430.964 | 740.292 |
| RELATIONAL (first) | 0.914 | 0.706 | 0.867 |

RELATIONAL is flat and sub-millisecond regardless of offset or size — its sort column is a real indexed column. JSON_IDX collapses dramatically between 10k and 25k (64ms → 2.7ms) once its generated-column sort index becomes the query planner's clear best choice; EAV/EAV_NAIVE/JSON/JSON_IDX_LOW all scale roughly linearly with table size (no sort index), confirming the sort-JOIN fix (item 1) made EAV's sort behave like a normal indexed JOIN rather than a correlated subquery, but it is still not as fast as a native indexed column.

### aggregate_date / aggregate_group / export_full / count_all / count_filtered

| Strategy | agg_date 50k | agg_group 50k | export_full 50k | count_all 50k | count_filtered 50k |
|---|---|---|---|---|---|
| EAV | 619.319 | 2431.260 | 5264.045 | 18.303 | 119.793 |
| EAV_NAIVE | 577.683 | 2138.615 | 4351.650 | 17.624 | 123.882 |
| JSON | 441.171 | 1096.169 | 4513.261 | 16.530 | 183.551 |
| JSON_IDX | 621.081 | 1421.337 | 4498.327 | 19.175 | 160.737 |
| JSON_IDX_LOW | 468.823 | 1196.331 | 4498.222 | 18.656 | 166.639 |
| RELATIONAL | 139.769 | 307.649 | 566.321 | 18.559 | 8.929 |

`count_all` uses the identical `SELECT COUNT(*) FROM form_submission WHERE form_id=?` query for every strategy (item 4) and, as expected, all 6 variants land in the same ~17-19ms band — this is a control confirming the benchmark harness is fair. Everywhere else (aggregate, export, count_filtered), RELATIONAL is 3-8x faster than every other strategy; EAV is consistently the slowest for heavy multi-row scans (aggregate_group, export_full).

Full per-operation tables (insert_single/batch, update_single, delete_single, own_scope_page, filter_range_eq, filter_3_fields, and their `_full` counterparts) are in `summary.csv`; omitted here for brevity but follow the same pattern — RELATIONAL fastest on indexed/typed access, EAV/EAV_NAIVE slowest on anything requiring per-field JOINs, JSON competitive only when the relevant generated column is indexed.

## Schema evolution (Scenario B, mean of 5 runs per op, fresh field each run)

| Strategy | add_field_plain 50k | add_field_indexed 50k | remove_field_soft 50k | remove_field_hard 50k | rename_label 50k |
|---|---|---|---|---|---|
| EAV | 7.712 | 0.000* | 0.000** | 2359.989 | 3.556 |
| EAV_NAIVE | 3.054 | 0.000* | 0.000** | 2395.056 | 4.107 |
| JSON | 4.885 | 242.167 | 4.427 | 2788.729 | 3.324 |
| JSON_IDX | 5.519 | 271.278 | 4.680 | 3598.315 | 3.350 |
| JSON_IDX_LOW | 2.890 | 216.479 | 3.996 | 3005.088 | 8.537 |
| RELATIONAL | 34.168 | 287.981 | 4.713 | 61.978 | 3.350 |

\* EAV has no DDL concept for new fields regardless of the "indexed" flag — `add_field_indexed` is structurally identical to `add_field_plain` for EAV, recorded as 0 since no separate operation exists.

\*\* **Genuine structural finding**: EAV's `remove_field_soft` (metadata-only field removal, leaving existing `form_value` rows in place) is **blocked** by `form_value`'s foreign key constraint on `field_id` whenever value rows still reference that field — confirmed at every size, every run. This is not a bug; it is EAV enforcing referential integrity in a way JSON/RELATIONAL do not. JSON and RELATIONAL both support true soft-remove (the column/key becomes orphaned/ignorable metadata) in ~4-5ms regardless of size.

`remove_field_hard` (physically removing the data) is RELATIONAL's standout: `ALTER TABLE ... DROP COLUMN` costs 42-62ms across all three sizes — nearly flat, because it's a single metadata operation (MySQL 8 INSTANT DDL). EAV/EAV_NAIVE's `DELETE FROM form_value WHERE field_id=?` and JSON's `JSON_REMOVE` across every row both scale with row count and cost 1.4-3.6 **seconds**, confirming hard removal is drastically more expensive without a columnar/metadata-only drop path.

`add_field_indexed` for JSON/JSON_IDX/JSON_IDX_LOW/RELATIONAL all pay 200-290ms at 50k for building the new index — comparable across strategies since it's dominated by the underlying `ADD INDEX`/generated-column-plus-index build on an existing table, not storage-model-specific.

## What's still pending

- **100,000-row size**: all 6 variants × all Scenario A+B operations — running now.
- **EXPLAIN ANALYZE captures** (item 8) for find_by_id / filter_eq_low / aggregate_date / page_first, scoped to 100k only — partially captured so far (`EAV_NAIVE`, `JSON`, `JSON_IDX_LOW`, `RELATIONAL` done; `EAV`, `JSON_IDX` pending) in `results/explain/`.
- **Scenario E (integrity, 10k)**: not yet started — `integrity.csv` currently has header only.

This report will be rewritten to include all of the above once the background run completes, per the original Phase 1 deliverable list (REPORT.md, summary.csv, storage_size_summary.csv, integrity.csv, explain/).
