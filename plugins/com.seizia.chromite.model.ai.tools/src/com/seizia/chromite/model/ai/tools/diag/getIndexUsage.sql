-- getIndexUsage: how indexes are used since the server started
-- @family: sqlserver
-- @requires: VIEW DATABASE STATE (VIEW SERVER STATE for usage stats)
SELECT OBJECT_SCHEMA_NAME(i.object_id) + '.' + OBJECT_NAME(i.object_id) AS table_name, i.name AS index_name, i.type_desc,
       i.is_unique, i.is_primary_key, i.is_disabled, i.fill_factor, i.has_filter,
       STUFF((SELECT ',' + c.name FROM sys.index_columns ic JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
              WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 0 ORDER BY ic.key_ordinal
              FOR XML PATH('')), 1, 1, '') AS key_columns,
       STUFF((SELECT ',' + c.name FROM sys.index_columns ic JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
              WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 1
              FOR XML PATH('')), 1, 1, '') AS included_columns,
       us.user_seeks, us.user_scans, us.user_lookups, us.user_updates, us.last_user_seek, us.last_user_scan,
       os.index_lock_promotion_count AS lock_escalations, os.row_lock_wait_count, os.page_latch_wait_count
FROM sys.indexes i
LEFT JOIN sys.dm_db_index_usage_stats us ON us.database_id = DB_ID() AND us.object_id = i.object_id AND us.index_id = i.index_id
LEFT JOIN sys.dm_db_index_operational_stats(DB_ID(), OBJECT_ID(${table}), NULL, NULL) os ON os.object_id = i.object_id AND os.index_id = i.index_id
WHERE OBJECTPROPERTY(i.object_id, 'IsUserTable') = 1 AND i.index_id > 0
  AND (${table} IS NULL OR i.object_id = OBJECT_ID(${table}))
  AND (${schema} IS NULL OR OBJECT_SCHEMA_NAME(i.object_id) = ${schema})
ORDER BY table_name, i.index_id

-- @family: postgresql
SELECT s.schemaname || '.' || s.relname AS table_name, s.indexrelname AS index_name, s.idx_scan, s.idx_tup_read, s.idx_tup_fetch,
       pg_relation_size(s.indexrelid) / 1048576 AS index_mb, x.indisunique AS is_unique, x.indisprimary AS is_primary_key,
       x.indisvalid AS is_valid, pg_get_indexdef(s.indexrelid) AS definition
FROM pg_stat_user_indexes s JOIN pg_index x ON x.indexrelid = s.indexrelid
WHERE (${table}::text IS NULL OR s.relname = ${table} OR s.schemaname || '.' || s.relname = ${table})
  AND (${schema}::text IS NULL OR s.schemaname = ${schema})
ORDER BY 1, 2
LIMIT ${limit}

-- @family: mysql
-- @requires: performance_schema enabled
SELECT CONCAT(OBJECT_SCHEMA, '.', OBJECT_NAME) AS table_name, INDEX_NAME AS index_name, COUNT_STAR AS accesses, COUNT_READ AS reads,
       COUNT_WRITE AS writes, COUNT_FETCH, COUNT_INSERT, COUNT_UPDATE, COUNT_DELETE
FROM performance_schema.table_io_waits_summary_by_index_usage
WHERE OBJECT_SCHEMA = COALESCE(${schema}, IF(LOCATE('.', COALESCE(${table}, '')) > 0, SUBSTRING_INDEX(${table}, '.', 1), DATABASE()))
  AND (${table} IS NULL OR OBJECT_NAME = SUBSTRING_INDEX(${table}, '.', -1))
ORDER BY 1, 2

-- @family: oracle
-- @note: Oracle records index usage only with index monitoring or in DBA_INDEX_USAGE (12.2+, needs privileges); this lists index statistics.
SELECT owner || '.' || table_name AS table_name, index_name, index_type, uniqueness, status, num_rows, distinct_keys, clustering_factor,
       leaf_blocks, last_analyzed
FROM all_indexes
WHERE table_owner = COALESCE(UPPER(${schema}), CASE WHEN INSTR(${table}, '.') > 0 THEN UPPER(SUBSTR(${table}, 1, INSTR(${table}, '.') - 1)) END,
                             SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
  AND (${table} IS NULL OR table_name = UPPER(SUBSTR(${table}, INSTR(${table}, '.') + 1)))
ORDER BY 1, 2
