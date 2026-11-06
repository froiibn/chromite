-- getIndexFragmentation: fragmentation / bloat of indexes and tables
-- @family: sqlserver
-- @requires: VIEW DATABASE STATE
-- @note: LIMITED scan mode; indexes under 1000 pages are skipped because their fragmentation does not matter.
SELECT OBJECT_SCHEMA_NAME(ps.object_id) + '.' + OBJECT_NAME(ps.object_id) AS table_name, i.name AS index_name, ps.index_type_desc,
       ps.partition_number, ps.page_count, CAST(ps.avg_fragmentation_in_percent AS decimal(5, 1)) AS fragmentation_pct,
       ps.fragment_count,
       CASE WHEN ps.avg_fragmentation_in_percent >= 30 THEN 'REBUILD' WHEN ps.avg_fragmentation_in_percent >= 5 THEN 'REORGANIZE' ELSE 'OK' END AS suggestion
FROM sys.dm_db_index_physical_stats(DB_ID(), OBJECT_ID(${table}), NULL, NULL, 'LIMITED') ps
JOIN sys.indexes i ON i.object_id = ps.object_id AND i.index_id = ps.index_id
WHERE ps.page_count >= 1000 AND ps.index_id > 0
  AND (${schema} IS NULL OR OBJECT_SCHEMA_NAME(ps.object_id) = ${schema})
ORDER BY ps.avg_fragmentation_in_percent DESC

-- @family: postgresql
-- @requires: pgstattuple extension (pgstatindex statement only)
-- @statement: tableBloat
SELECT schemaname || '.' || relname AS table_name, n_live_tup, n_dead_tup,
       round((100.0 * n_dead_tup / nullif(n_live_tup + n_dead_tup, 0))::numeric, 1) AS dead_pct,
       pg_relation_size(relid) / 1048576 AS table_mb, last_autovacuum, last_vacuum
FROM pg_stat_user_tables
WHERE (${table}::text IS NULL OR relname = ${table} OR schemaname || '.' || relname = ${table})
  AND (${schema}::text IS NULL OR schemaname = ${schema})
ORDER BY n_dead_tup DESC
LIMIT ${limit}
-- @statement: pgstatindex
SELECT s.schemaname || '.' || s.indexrelname AS index_name, (pgstatindex(s.indexrelid)).avg_leaf_density,
       (pgstatindex(s.indexrelid)).leaf_fragmentation, pg_relation_size(s.indexrelid) / 1048576 AS index_mb
FROM pg_stat_user_indexes s JOIN pg_class c ON c.oid = s.indexrelid JOIN pg_am am ON am.oid = c.relam
WHERE am.amname = 'btree' AND pg_relation_size(s.indexrelid) > 8 * 1024 * 1024
  AND (${table}::text IS NULL OR s.relname = ${table} OR s.schemaname || '.' || s.relname = ${table})
  AND (${schema}::text IS NULL OR s.schemaname = ${schema})
LIMIT 20

-- @family: mysql
-- @note: InnoDB reports reclaimable space (DATA_FREE) per tablespace, not per index; OPTIMIZE TABLE rebuilds a table.
SELECT CONCAT(TABLE_SCHEMA, '.', TABLE_NAME) AS table_name, ENGINE, ROUND(DATA_LENGTH / 1048576, 1) AS data_mb,
       ROUND(INDEX_LENGTH / 1048576, 1) AS index_mb, ROUND(DATA_FREE / 1048576, 1) AS free_mb,
       ROUND(100 * DATA_FREE / NULLIF(DATA_LENGTH + INDEX_LENGTH + DATA_FREE, 0), 1) AS free_pct
FROM information_schema.TABLES
WHERE TABLE_TYPE = 'BASE TABLE'
  AND TABLE_SCHEMA = COALESCE(${schema}, IF(LOCATE('.', COALESCE(${table}, '')) > 0, SUBSTRING_INDEX(${table}, '.', 1), DATABASE()))
  AND (${table} IS NULL OR TABLE_NAME = SUBSTRING_INDEX(${table}, '.', -1))
ORDER BY DATA_FREE DESC
