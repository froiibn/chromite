-- getSpaceUsage: files, log and free space (current database; pass database to choose)
-- @family: sqlserver
-- @requires: VIEW SERVER STATE (volumes, log usage)
-- @statement: files
SELECT name AS file_name, type_desc, physical_name, state_desc,
       CAST(size * 8.0 / 1024 AS decimal(18, 1)) AS size_mb,
       CAST(FILEPROPERTY(name, 'SpaceUsed') * 8.0 / 1024 AS decimal(18, 1)) AS used_mb,
       CAST((size - FILEPROPERTY(name, 'SpaceUsed')) * 8.0 / 1024 AS decimal(18, 1)) AS free_mb,
       CASE max_size WHEN -1 THEN 'unlimited' WHEN 0 THEN 'no growth' ELSE CAST(CAST(max_size * 8.0 / 1024 AS decimal(18, 1)) AS varchar(30)) END AS max_size_mb,
       CASE is_percent_growth WHEN 1 THEN CAST(growth AS varchar(10)) + '%' ELSE CAST(growth * 8 / 1024 AS varchar(20)) + ' MB' END AS growth
FROM sys.database_files
-- @statement: log
SELECT DB_NAME(database_id) AS database_name, CAST(total_log_size_in_bytes / 1048576.0 AS decimal(18, 1)) AS log_size_mb,
       CAST(used_log_space_in_bytes / 1048576.0 AS decimal(18, 1)) AS log_used_mb, CAST(used_log_space_in_percent AS decimal(5, 1)) AS log_used_pct,
       (SELECT log_reuse_wait_desc FROM sys.databases WHERE database_id = DB_ID()) AS log_reuse_wait
FROM sys.dm_db_log_space_usage
-- @statement: volumes
SELECT DISTINCT vs.volume_mount_point, vs.logical_volume_name, vs.file_system_type,
       CAST(vs.total_bytes / 1048576.0 AS decimal(18, 1)) AS total_mb, CAST(vs.available_bytes / 1048576.0 AS decimal(18, 1)) AS free_mb,
       CAST(100.0 * vs.available_bytes / NULLIF(vs.total_bytes, 0) AS decimal(5, 1)) AS free_pct
FROM sys.master_files mf
CROSS APPLY sys.dm_os_volume_stats(mf.database_id, mf.file_id) vs

-- @family: postgresql
-- @requires: pg_monitor (WAL directory)
-- @statement: tablespaces
SELECT spcname AS tablespace_name, pg_tablespace_location(oid) AS location, pg_tablespace_size(oid) / 1048576 AS size_mb
FROM pg_tablespace
-- @statement: largestTables
SELECT n.nspname || '.' || c.relname AS table_name, pg_total_relation_size(c.oid) / 1048576 AS total_mb,
       pg_relation_size(c.oid) / 1048576 AS table_mb, pg_indexes_size(c.oid) / 1048576 AS indexes_mb,
       COALESCE(pg_total_relation_size(c.reltoastrelid), 0) / 1048576 AS toast_mb
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p', 'm') AND n.nspname NOT IN ('pg_catalog', 'information_schema')
ORDER BY pg_total_relation_size(c.oid) DESC
LIMIT 20
-- @statement: wal
SELECT count(*) AS wal_files, sum(size) / 1048576 AS wal_mb FROM pg_ls_waldir()

-- @family: mysql
-- @statement: schemas
SELECT TABLE_SCHEMA AS database_name, ROUND(SUM(DATA_LENGTH) / 1048576, 1) AS data_mb, ROUND(SUM(INDEX_LENGTH) / 1048576, 1) AS index_mb,
       ROUND(SUM(DATA_FREE) / 1048576, 1) AS reclaimable_mb
FROM information_schema.TABLES
GROUP BY TABLE_SCHEMA
ORDER BY data_mb DESC
-- @statement: largestTables
SELECT CONCAT(TABLE_SCHEMA, '.', TABLE_NAME) AS table_name, ENGINE, TABLE_ROWS, ROUND(DATA_LENGTH / 1048576, 1) AS data_mb,
       ROUND(INDEX_LENGTH / 1048576, 1) AS index_mb, ROUND(DATA_FREE / 1048576, 1) AS reclaimable_mb
FROM information_schema.TABLES
WHERE TABLE_TYPE = 'BASE TABLE'
ORDER BY DATA_LENGTH + INDEX_LENGTH DESC
LIMIT 20

-- @family: oracle
-- @requires: SELECT on DBA_TABLESPACE_USAGE_METRICS
-- @statement: tablespaces
SELECT m.tablespace_name, ROUND(m.used_space * t.block_size / 1048576, 1) AS used_mb,
       ROUND(m.tablespace_size * t.block_size / 1048576, 1) AS max_mb, ROUND(m.used_percent, 1) AS used_pct
FROM dba_tablespace_usage_metrics m JOIN dba_tablespaces t ON t.tablespace_name = m.tablespace_name
ORDER BY m.used_percent DESC
-- @statement: quotas
SELECT tablespace_name, ROUND(bytes / 1048576, 1) AS used_mb, DECODE(max_bytes, -1, NULL, ROUND(max_bytes / 1048576, 1)) AS quota_mb
FROM user_ts_quotas
