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

-- @family: oracle
-- @requires: SELECT on DBA_INDEX_USAGE, DBA_SEGMENTS and V$SQL_PLAN (SELECT_CATALOG_ROLE or SELECT ANY DICTIONARY)
-- @note: Index usage tracking (DBA_INDEX_USAGE, Oracle 12.2+, no pack needed) samples executions and writes them about every 15 minutes, so a recently used index can still show here; in_cached_plan = 1 means a plan in the shared pool uses it. Unique and primary-key indexes are excluded. Schema default: the current schema.
-- @statement: unused
SELECT i.table_owner || '.' || i.table_name AS table_name, i.owner || '.' || i.index_name AS index_name, i.index_type,
       i.num_rows, ROUND(sg.bytes / 1048576, 1) AS index_mb, u.total_access_count, u.last_used,
       CASE WHEN EXISTS (SELECT 1 FROM v$sql_plan p WHERE p.object_owner = i.owner AND p.object_name = i.index_name) THEN 1 ELSE 0 END AS in_cached_plan
FROM all_indexes i
LEFT JOIN dba_index_usage u ON u.owner = i.owner AND u.name = i.index_name
LEFT JOIN (SELECT owner, segment_name, SUM(bytes) AS bytes FROM dba_segments GROUP BY owner, segment_name) sg
       ON sg.owner = i.owner AND sg.segment_name = i.index_name
WHERE i.table_owner = NVL(UPPER(${schema}), SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
  AND i.uniqueness = 'NONUNIQUE' AND i.index_type NOT IN ('LOB')
  AND NVL(u.total_access_count, 0) = 0
ORDER BY sg.bytes DESC NULLS LAST
-- @statement: duplicates
WITH k AS (
    SELECT index_owner, index_name, table_owner, table_name,
           LISTAGG(column_name, ',') WITHIN GROUP (ORDER BY column_position) AS key_list
    FROM all_ind_columns
    WHERE table_owner = NVL(UPPER(${schema}), SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
    GROUP BY index_owner, index_name, table_owner, table_name
)
SELECT a.table_owner || '.' || a.table_name AS table_name, a.index_name, b.index_name AS duplicate_of, a.key_list AS key_columns,
       CASE WHEN a.key_list = b.key_list THEN 'same keys' ELSE 'left prefix of the other' END AS relation
FROM k a
JOIN k b ON b.table_owner = a.table_owner AND b.table_name = a.table_name AND b.index_name <> a.index_name
WHERE (a.key_list = b.key_list AND a.index_name > b.index_name) OR b.key_list LIKE a.key_list || ',%'
