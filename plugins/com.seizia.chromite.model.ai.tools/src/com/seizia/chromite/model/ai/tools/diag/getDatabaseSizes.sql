-- getDatabaseSizes: size of every database (data and log)
-- @family: sqlserver
-- @requires: VIEW ANY DEFINITION (sys.master_files of all databases)
SELECT d.name AS database_name, d.state_desc, d.recovery_model_desc,
       CAST(SUM(CASE WHEN mf.type = 0 THEN mf.size END) * 8.0 / 1024 AS decimal(18, 1)) AS data_mb,
       CAST(SUM(CASE WHEN mf.type = 1 THEN mf.size END) * 8.0 / 1024 AS decimal(18, 1)) AS log_mb,
       CAST(SUM(mf.size) * 8.0 / 1024 AS decimal(18, 1)) AS total_mb
FROM sys.databases d
LEFT JOIN sys.master_files mf ON mf.database_id = d.database_id
GROUP BY d.name, d.state_desc, d.recovery_model_desc
ORDER BY total_mb DESC

-- @family: postgresql
SELECT datname AS database_name, pg_database_size(datname) / 1048576 AS total_mb, datallowconn, datconnlimit,
       pg_encoding_to_char(encoding) AS encoding, datcollate AS collation
FROM pg_database
WHERE NOT datistemplate AND has_database_privilege(datname, 'CONNECT')
ORDER BY 2 DESC

-- @family: mysql
SELECT TABLE_SCHEMA AS database_name, ROUND(SUM(DATA_LENGTH) / 1048576, 1) AS data_mb, ROUND(SUM(INDEX_LENGTH) / 1048576, 1) AS index_mb,
       ROUND(SUM(DATA_FREE) / 1048576, 1) AS free_mb, ROUND(SUM(DATA_LENGTH + INDEX_LENGTH) / 1048576, 1) AS total_mb, COUNT(*) AS tables
FROM information_schema.TABLES
GROUP BY TABLE_SCHEMA
ORDER BY total_mb DESC

-- @family: oracle
-- @requires: SELECT on DBA_SEGMENTS (all schemas); otherwise only the current schema
-- @statement: schemas
SELECT owner AS database_name, ROUND(SUM(bytes) / 1048576, 1) AS total_mb, COUNT(*) AS segments
FROM dba_segments
GROUP BY owner
ORDER BY total_mb DESC
-- @statement: currentSchema
SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS database_name, ROUND(SUM(bytes) / 1048576, 1) AS total_mb, COUNT(*) AS segments
FROM user_segments
