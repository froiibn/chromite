-- getTableStatistics: row counts, sizes and statistics freshness of a table or a schema
-- @family: sqlserver
-- @requires: VIEW DATABASE STATE
-- @statement: tables
SELECT OBJECT_SCHEMA_NAME(ps.object_id) + '.' + OBJECT_NAME(ps.object_id) AS table_name,
       SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.row_count ELSE 0 END) AS row_count,
       CAST(SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.reserved_page_count ELSE 0 END) * 8.0 / 1024 AS decimal(18, 1)) AS table_mb,
       CAST(SUM(CASE WHEN ps.index_id > 1 THEN ps.reserved_page_count ELSE 0 END) * 8.0 / 1024 AS decimal(18, 1)) AS indexes_mb,
       MAX(CASE WHEN ps.index_id = 0 THEN 1 ELSE 0 END) AS is_heap,
       COUNT(DISTINCT ps.partition_number) AS partitions
FROM sys.dm_db_partition_stats ps
WHERE OBJECTPROPERTY(ps.object_id, 'IsUserTable') = 1
  AND (${table} IS NULL OR ps.object_id = OBJECT_ID(${table}))
  AND (${schema} IS NULL OR OBJECT_SCHEMA_NAME(ps.object_id) = ${schema})
GROUP BY ps.object_id
ORDER BY row_count DESC
-- @statement: statistics
SELECT OBJECT_SCHEMA_NAME(s.object_id) + '.' + OBJECT_NAME(s.object_id) AS table_name, s.name AS stats_name,
       STUFF((SELECT ',' + c.name FROM sys.stats_columns sc JOIN sys.columns c ON c.object_id = sc.object_id AND c.column_id = sc.column_id
              WHERE sc.object_id = s.object_id AND sc.stats_id = s.stats_id ORDER BY sc.stats_column_id FOR XML PATH('')), 1, 1, '') AS columns,
       s.auto_created, s.user_created, s.no_recompute, sp.last_updated, sp.rows, sp.rows_sampled,
       CAST(100.0 * sp.rows_sampled / NULLIF(sp.rows, 0) AS decimal(5, 1)) AS sample_pct, sp.modification_counter,
       CAST(100.0 * sp.modification_counter / NULLIF(sp.rows, 0) AS decimal(9, 1)) AS modified_pct,
       DATEDIFF(DAY, sp.last_updated, GETDATE()) AS age_days
FROM sys.stats s
CROSS APPLY sys.dm_db_stats_properties(s.object_id, s.stats_id) sp
WHERE OBJECTPROPERTY(s.object_id, 'IsUserTable') = 1
  AND (${table} IS NULL OR s.object_id = OBJECT_ID(${table}))
  AND (${schema} IS NULL OR OBJECT_SCHEMA_NAME(s.object_id) = ${schema})
ORDER BY sp.modification_counter DESC

-- @family: postgresql
-- @statement: tables
SELECT t.schemaname || '.' || t.relname AS table_name, t.n_live_tup AS row_estimate, t.n_dead_tup AS dead_rows,
       round((100.0 * t.n_dead_tup / nullif(t.n_live_tup + t.n_dead_tup, 0))::numeric, 1) AS dead_pct,
       t.n_mod_since_analyze AS modified_since_analyze, t.last_analyze, t.last_autoanalyze, t.last_vacuum, t.last_autovacuum,
       t.seq_scan, t.seq_tup_read, t.idx_scan, t.n_tup_ins, t.n_tup_upd, t.n_tup_hot_upd, t.n_tup_del,
       pg_relation_size(t.relid) / 1048576 AS table_mb, pg_indexes_size(t.relid) / 1048576 AS indexes_mb,
       c.reltuples::bigint AS planner_rows, c.relpages AS planner_pages
FROM pg_stat_user_tables t JOIN pg_class c ON c.oid = t.relid
WHERE (${table}::text IS NULL OR t.relname = ${table} OR t.schemaname || '.' || t.relname = ${table})
  AND (${schema}::text IS NULL OR t.schemaname = ${schema})
ORDER BY t.n_live_tup DESC
LIMIT ${limit}
-- @statement: columns
SELECT s.schemaname || '.' || s.tablename AS table_name, s.attname AS column_name, s.null_frac, s.n_distinct, s.correlation,
       s.avg_width, left(s.most_common_vals::text, 300) AS most_common_vals, left(s.most_common_freqs::text, 300) AS most_common_freqs
FROM pg_stats s
WHERE ${table}::text IS NOT NULL
  AND (s.tablename = ${table} OR s.schemaname || '.' || s.tablename = ${table})

-- @family: mysql
-- @statement: tables
SELECT CONCAT(TABLE_SCHEMA, '.', TABLE_NAME) AS table_name, ENGINE, TABLE_ROWS AS row_estimate,
       ROUND(DATA_LENGTH / 1048576, 1) AS table_mb, ROUND(INDEX_LENGTH / 1048576, 1) AS indexes_mb,
       ROUND(DATA_FREE / 1048576, 1) AS free_mb, AUTO_INCREMENT, CREATE_TIME, UPDATE_TIME
FROM information_schema.TABLES
WHERE TABLE_TYPE = 'BASE TABLE'
  AND TABLE_SCHEMA = COALESCE(${schema}, IF(LOCATE('.', COALESCE(${table}, '')) > 0, SUBSTRING_INDEX(${table}, '.', 1), DATABASE()))
  AND (${table} IS NULL OR TABLE_NAME = SUBSTRING_INDEX(${table}, '.', -1))
ORDER BY TABLE_ROWS DESC
-- @statement: statistics
SELECT CONCAT(database_name, '.', table_name) AS table_name, last_update, n_rows, clustered_index_size, sum_of_other_index_sizes
FROM mysql.innodb_table_stats
WHERE database_name = COALESCE(${schema}, IF(LOCATE('.', COALESCE(${table}, '')) > 0, SUBSTRING_INDEX(${table}, '.', 1), DATABASE()))
  AND (${table} IS NULL OR table_name = SUBSTRING_INDEX(${table}, '.', -1))
-- @statement: indexes
SELECT CONCAT(TABLE_SCHEMA, '.', TABLE_NAME) AS table_name, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, CARDINALITY, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = COALESCE(${schema}, IF(LOCATE('.', COALESCE(${table}, '')) > 0, SUBSTRING_INDEX(${table}, '.', 1), DATABASE()))
  AND (${table} IS NULL OR TABLE_NAME = SUBSTRING_INDEX(${table}, '.', -1))
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX

-- @family: oracle
-- @statement: tables
SELECT owner || '.' || table_name AS table_name, num_rows, blocks, avg_row_len, sample_size, last_analyzed, stale_stats,
       ROUND(SYSDATE - last_analyzed) AS age_days
FROM all_tab_statistics
WHERE object_type = 'TABLE'
  AND owner = COALESCE(UPPER(${schema}), CASE WHEN INSTR(${table}, '.') > 0 THEN UPPER(SUBSTR(${table}, 1, INSTR(${table}, '.') - 1)) END,
                       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
  AND (${table} IS NULL OR table_name = UPPER(SUBSTR(${table}, INSTR(${table}, '.') + 1)))
ORDER BY num_rows DESC NULLS LAST
-- @statement: indexes
SELECT owner || '.' || table_name AS table_name, index_name, num_rows, distinct_keys, clustering_factor, leaf_blocks, blevel,
       last_analyzed, stale_stats
FROM all_ind_statistics
WHERE owner = COALESCE(UPPER(${schema}), CASE WHEN INSTR(${table}, '.') > 0 THEN UPPER(SUBSTR(${table}, 1, INSTR(${table}, '.') - 1)) END,
                       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
  AND (${table} IS NULL OR table_name = UPPER(SUBSTR(${table}, INSTR(${table}, '.') + 1)))
