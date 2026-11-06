-- getUnusedIndexes: indexes with writes but no reads since start, and duplicate key lists
-- @family: sqlserver
-- @requires: VIEW SERVER STATE
-- @note: Usage counters reset when the server restarts; check the server uptime (getServerInfo) before dropping anything.
-- @statement: unused
SELECT OBJECT_SCHEMA_NAME(i.object_id) + '.' + OBJECT_NAME(i.object_id) AS table_name, i.name AS index_name, i.type_desc,
       ISNULL(us.user_updates, 0) AS user_updates,
       CAST((SELECT SUM(ps.used_page_count) FROM sys.dm_db_partition_stats ps WHERE ps.object_id = i.object_id AND ps.index_id = i.index_id) * 8.0 / 1024 AS decimal(18, 1)) AS index_mb
FROM sys.indexes i
LEFT JOIN sys.dm_db_index_usage_stats us ON us.database_id = DB_ID() AND us.object_id = i.object_id AND us.index_id = i.index_id
WHERE OBJECTPROPERTY(i.object_id, 'IsUserTable') = 1 AND i.index_id > 1 AND i.is_primary_key = 0 AND i.is_unique_constraint = 0
  AND ISNULL(us.user_seeks, 0) + ISNULL(us.user_scans, 0) + ISNULL(us.user_lookups, 0) = 0
  AND (${schema} IS NULL OR OBJECT_SCHEMA_NAME(i.object_id) = ${schema})
ORDER BY user_updates DESC
-- @statement: duplicates
WITH keys AS (
    SELECT i.object_id, i.index_id, i.name,
           STUFF((SELECT ',' + CAST(ic.column_id AS varchar(10)) FROM sys.index_columns ic
                  WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 0
                  ORDER BY ic.key_ordinal FOR XML PATH('')), 1, 1, '') AS key_list
    FROM sys.indexes i
    WHERE OBJECTPROPERTY(i.object_id, 'IsUserTable') = 1 AND i.index_id > 0
)
SELECT OBJECT_SCHEMA_NAME(a.object_id) + '.' + OBJECT_NAME(a.object_id) AS table_name, a.name AS index_name, b.name AS duplicate_of,
       CASE WHEN a.key_list = b.key_list THEN 'same keys' ELSE 'left prefix of the other' END AS relation
FROM keys a JOIN keys b ON b.object_id = a.object_id AND b.index_id <> a.index_id
WHERE (a.key_list = b.key_list AND a.index_id > b.index_id) OR (b.key_list LIKE a.key_list + ',%')

-- @family: postgresql
-- @note: Counters are since the last statistics reset (pg_stat_reset); unique and primary-key indexes are excluded.
-- @statement: unused
SELECT s.schemaname || '.' || s.relname AS table_name, s.indexrelname AS index_name, s.idx_scan,
       pg_relation_size(s.indexrelid) / 1048576 AS index_mb, pg_get_indexdef(s.indexrelid) AS definition
FROM pg_stat_user_indexes s JOIN pg_index x ON x.indexrelid = s.indexrelid
WHERE s.idx_scan = 0 AND NOT x.indisunique AND NOT x.indisprimary
  AND (${schema}::text IS NULL OR s.schemaname = ${schema})
ORDER BY pg_relation_size(s.indexrelid) DESC
-- @statement: duplicates
SELECT x.indrelid::regclass::text AS table_name, string_agg(x.indexrelid::regclass::text, ', ') AS indexes, x.indkey::text AS key_columns
FROM pg_index x JOIN pg_class c ON c.oid = x.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
GROUP BY x.indrelid, x.indkey::text, x.indclass::text, coalesce(x.indexprs::text, ''), coalesce(x.indpred::text, '')
HAVING count(*) > 1

-- @family: mysql
-- @requires: performance_schema enabled (unused statement)
-- @statement: unused
SELECT CONCAT(OBJECT_SCHEMA, '.', OBJECT_NAME) AS table_name, INDEX_NAME AS index_name, COUNT_WRITE AS writes
FROM performance_schema.table_io_waits_summary_by_index_usage
WHERE INDEX_NAME IS NOT NULL AND INDEX_NAME <> 'PRIMARY' AND COUNT_READ = 0
  AND OBJECT_SCHEMA = COALESCE(${schema}, DATABASE())
ORDER BY COUNT_WRITE DESC
-- @statement: duplicates
SELECT CONCAT(a.TABLE_SCHEMA, '.', a.TABLE_NAME) AS table_name, a.INDEX_NAME AS index_name, b.INDEX_NAME AS duplicate_of, a.cols AS key_columns
FROM (SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
      FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = COALESCE(${schema}, DATABASE()) GROUP BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME) a
JOIN (SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
      FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = COALESCE(${schema}, DATABASE()) GROUP BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME) b
  ON b.TABLE_SCHEMA = a.TABLE_SCHEMA AND b.TABLE_NAME = a.TABLE_NAME AND b.INDEX_NAME <> a.INDEX_NAME
WHERE (a.cols = b.cols AND a.INDEX_NAME > b.INDEX_NAME) OR b.cols LIKE CONCAT(a.cols, ',%')
