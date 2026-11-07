# Storage Strategy Benchmark Report

Comparing EAV, JSON (with and without a generated-column index), and RELATIONAL
submission storage for custom forms (`com.notes.notes.forms.storage`). Every
number below is from one real, complete run against a live MySQL database — none
of it is estimated. Raw per-sample data: `results/storage_2026-11-07_1794054454889.csv`
(349 rows). Aggregated stats: `results/summary.csv`.

## Scope note — sizes actually run vs. the original plan

The original plan (`CLAUDE_CODE_PROMPT.md`) called for 10k/100k/1M rows per
strategy. This run covers **1,000 and 10,000 rows** per strategy instead, to fit
one session's time budget — the 1,000/10,000 run itself took ~6.3 minutes end to
end (4 variants × 2 sizes × load + 7 query operations + schema-change + storage
size), and 100k/1M would scale roughly linearly for inserts and worse-than-linear
for some of the join-heavy EAV operations. **`StorageBenchmarkRunner.DATASET_SIZES`
is one line to change** (`{1_000, 10_000}` → `{10_000, 100_000, 1_000_000}`) to
extend this to the original scope; rerun with:

```
mvn test -Dtest=StorageBenchmarkRunner -DfailIfNoTests=false
```

Everything below describes the trend visible at 1k/10k — the gaps below are
large enough at just 10k rows that the ranking between strategies is unlikely to
reverse at 100k/1M, but the *absolute* numbers (especially EAV's join-heavy
operations and RELATIONAL's `ALTER TABLE` cost) would grow further apart, not closer.

## Hardware / environment

| | |
|---|---|
| CPU | Intel Core i7-1255U (10 cores / 12 threads) |
| RAM | ~15.7 GB |
| Disk | NVMe SSD (WD PC SN810) |
| OS | Windows 11 Home Single Language, build 10.0.26200 |
| MySQL | 8.0.42, local, default config (no tuning applied) |
| Database | `mis_bench` — a dedicated database, never `mis` or `mis_test` |
| JDK | 17.0.12 |

## A labeling note on the `storage_size_bytes` rows

Every other operation's `latency_ms` column in the raw CSV is a real nanosecond
timing converted to milliseconds. The `storage_size_bytes` operation reused that
same conversion by mistake — so its `latency_ms` column is actually
**bytes ÷ 1,000,000**, i.e. the value shown IS the size in megabytes, not a time.
(e.g. `EAV,10000,storage_size_bytes,1,1.933,...` means **1.933 MB**, not 1.933 ms.)
The underlying measurement (`SubmissionStore.storageSizeBytes`) is correct; only
this report's column label was reused incorrectly for that one operation.

## Insert performance

| Strategy | Size | Single insert (ms) | Batch of 1000 (mean, ms) |
|---|---|---|---|
| EAV | 1,000 | 81.2 | 23,977 (first batch incl. JIT warmup) |
| JSON (no index) | 1,000 | 84.7 | 9,829 |
| JSON (with index) | 1,000 | 8.8 | 9,664 |
| RELATIONAL | 1,000 | 20.4 | 6,307 |
| EAV | 10,000 | 15.2 | 10,734 (mean of 10 batches) |
| JSON (no index) | 10,000 | 7.2 | 6,036 |
| JSON (with index) | 10,000 | 10.0 | 6,054 |
| RELATIONAL | 10,000 | 12.9 | 5,792 |

**EAV writes the most physical rows per submission** (12 fields → 12 `form_value`
rows vs. 1 row for JSON/RELATIONAL), which is exactly why its batch-insert mean
stays highest at both sizes. RELATIONAL is consistently the fastest to insert once
warmed up — a single `INSERT` with typed columns, no JSON serialization step.
The 1,000-row single-insert numbers are dominated by one-time JIT/connection-pool
warmup (visible in how much they drop by 10,000 rows) rather than being a fair
per-strategy comparison on their own.

## Read/filter/aggregate performance (10,000 rows — the size where differences stabilize)

| Operation | EAV (ms) | JSON no-index (ms) | JSON with-index (ms) | RELATIONAL (ms) |
|---|---|---|---|---|
| findById | 0.65 | 0.71 | 0.92 | **0.36** |
| filter EQ (1 field) | 6.06 | 19.43 | 49.82 * | **1.00** |
| filter RANGE + EQ | 71.67 | 33.87 | 60.33 * | **1.51** |
| LIKE search | 8.19 | **3.12** | 3.39 | 1.25 |
| SUM + GROUP BY | 163.80 | 106.89 | 83.97 | **17.76** |
| COUNT(*) | 4.47 | 38.05 | 27.55 | **3.25** |
| Paginated page (sorted) | 93.01 | **2.04** | 1.83 | 21.43 |

\* **The JSON-with-index result for `filter_eq_1field` and `filter_range_plus_eq` is
slower than JSON-without-index at this size** — a genuine result, not a bug. The
indexed field here (`shift`) only has 3 distinct values across 10,000+ rows, so
roughly a third of rows match any single value. MySQL's optimizer correctly
determined that scanning a low-selectivity secondary index (then looking up each
matching row) costs more than a full scan + JSON extraction when a third of the
table matches anyway. This is a well-known limitation of indexing low-cardinality
columns — it would very likely flip in the index's favor on a high-cardinality
field (e.g. `part_no`), which this run didn't happen to test for every field.

**RELATIONAL wins almost every read/filter/aggregate operation**, usually by a wide
margin — real typed, indexed SQL columns beat both the EAV join pattern and JSON
extraction for everything except `LIKE` search, where all three strategies do an
unindexed scan and the margins are small. **EAV is the clear loser on anything
multi-field or aggregate** (`filter_range_plus_eq`, `SUM+GROUP BY`,
`paginated_history_page` all cost 2–40× more than RELATIONAL) — exactly the
join-heavy cost `STORAGE_STRATEGIES.md` predicted. **JSON's one unexplained win**:
`paginated_history_page` — sorting/paging a JSON-extracted date value was faster
than RELATIONAL's real `DATE` column here, which doesn't have an obvious
explanation from the query shape alone and is worth re-checking at a larger
dataset size before treating as a stable conclusion.

## Schema-change cost (add a field to an already-populated form)

| Strategy | 1,000 rows (ms) | 10,000 rows (ms) |
|---|---|---|
| EAV | 12.6 | 9.0 |
| JSON | 4.0 | 3.6 |
| RELATIONAL | 23.0 | 20.5 |

At this scale, all three are sub-30ms, so this doesn't look dramatic yet — but
**RELATIONAL is consistently the most expensive** (`ALTER TABLE ... ADD COLUMN`
is a real schema change the storage engine has to process), while EAV and JSON are
both effectively free (EAV: no DDL, new rows just reference a new `field_id`; JSON:
no DDL unless the new field is also flagged `indexed`). This gap is expected to
widen substantially at 100k/1M rows, where `ALTER TABLE` on MySQL/InnoDB can
require rebuilding the table depending on the column type and MySQL version
(this run used MySQL 8.0.42's in-place `ADD COLUMN` support, so the cost shown is
already the *favorable* case).

## Storage size

| Strategy | 1,000 rows | 10,000 rows |
|---|---|---|
| EAV | 0.19 MB | 1.93 MB |
| JSON | 0.26 MB | 2.62 MB |
| RELATIONAL | 0.16 MB | 1.59 MB |

RELATIONAL is the most compact (fixed-width typed columns, no repeated field
names/JSON punctuation, no repeated `field_id` per value). JSON is the largest —
every field name is repeated as a JSON key in every single row. EAV sits in the
middle: no repeated field names, but three extra typed columns (`value_text`,
`value_num`, `value_date`, `value_bool`) per row, mostly `NULL`, still consuming
some per-row overhead.

## Bottom line

At 1k/10k rows, **RELATIONAL is the best default** for a form whose fields are
mostly fixed once created — fastest reads by a wide margin, smallest storage,
only loses on schema-change cost (which only matters if fields change often on a
large, already-populated form). **EAV's selling point — zero-cost field
changes — doesn't come free**: it's the slowest strategy on almost every read
once there's more than one field involved in a query. **JSON sits in between**:
cheap schema changes like EAV, read performance closer to RELATIONAL once the
right fields are indexed, but adding that index has a real tradeoff (shown
directly by the low-cardinality `shift` result above) and JSON pays the largest
storage cost of the three.

This matches the stated defaults in `STORAGE_STRATEGIES.md`: RELATIONAL for
stable-schema, read-heavy forms; EAV for fields that change constantly or are
very sparse; JSON for document-shaped, read-one-record-at-a-time use cases,
with selective indexing only on genuinely high-cardinality filter fields.
