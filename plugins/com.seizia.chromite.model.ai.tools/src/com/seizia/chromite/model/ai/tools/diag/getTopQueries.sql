-- getTopQueries (source=cache): top statements by cpu | io | duration | executions
-- @family: sqlserver
-- @limit: 20
-- @requires: VIEW SERVER STATE
-- @note: From the plan cache (sys.dm_exec_query_stats): statements whose plans were evicted or recompiled are missing.
SELECT TOP (${limit})
    qs.execution_count,
    qs.total_worker_time / 1000 AS total_cpu_ms,
    qs.total_worker_time / 1000 / qs.execution_count AS avg_cpu_ms,
    qs.total_elapsed_time / 1000 AS total_duration_ms,
    qs.total_elapsed_time / 1000 / qs.execution_count AS avg_duration_ms,
    qs.total_logical_reads,
    qs.total_logical_reads / qs.execution_count AS avg_logical_reads,
    qs.total_physical_reads,
    qs.total_rows,
    qs.creation_time,
    qs.last_execution_time,
    DB_NAME(st.dbid) AS database_name,
    OBJECT_SCHEMA_NAME(st.objectid, st.dbid) + '.' + OBJECT_NAME(st.objectid, st.dbid) AS sql_object,
    CONVERT(varchar(20), qs.query_hash, 1) AS query_hash,
    SUBSTRING(st.text, qs.statement_start_offset / 2 + 1,
        (CASE WHEN qs.statement_end_offset = -1 THEN DATALENGTH(st.text) ELSE qs.statement_end_offset END - qs.statement_start_offset) / 2 + 1) AS sql_text
FROM sys.dm_exec_query_stats qs
CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
ORDER BY CASE ${sortBy}
    WHEN 'io' THEN qs.total_logical_reads
    WHEN 'duration' THEN qs.total_elapsed_time
    WHEN 'executions' THEN qs.execution_count
    ELSE qs.total_worker_time END DESC

-- @family: postgresql
-- @limit: 20
-- @requires: pg_stat_statements extension in this database (CREATE EXTENSION pg_stat_statements; shared_preload_libraries)
SELECT queryid::text AS query_hash, calls AS execution_count,
       round(total_exec_time::numeric, 1) AS total_duration_ms, round(mean_exec_time::numeric, 2) AS avg_duration_ms,
       rows AS total_rows, shared_blks_hit, shared_blks_read, temp_blks_written,
       round((100.0 * shared_blks_hit / nullif(shared_blks_hit + shared_blks_read, 0))::numeric, 1) AS cache_hit_percent,
       left(query, 4000) AS sql_text
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY CASE ${sortBy}::text
    WHEN 'io' THEN (shared_blks_read + shared_blks_hit)::double precision
    WHEN 'executions' THEN calls::double precision
    WHEN 'duration' THEN total_exec_time
    ELSE total_exec_time END DESC
LIMIT ${limit}

-- @family: mysql
-- @limit: 20
-- @requires: performance_schema enabled
SELECT DIGEST AS query_hash, SCHEMA_NAME AS database_name, COUNT_STAR AS execution_count,
       ROUND(SUM_TIMER_WAIT / 1000000000, 1) AS total_duration_ms, ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS avg_duration_ms,
       SUM_ROWS_EXAMINED AS rows_examined, SUM_ROWS_SENT AS rows_sent, SUM_NO_INDEX_USED AS no_index_used,
       SUM_CREATED_TMP_DISK_TABLES AS tmp_disk_tables, LAST_SEEN, LEFT(DIGEST_TEXT, 4000) AS sql_text
FROM performance_schema.events_statements_summary_by_digest
ORDER BY CASE ${sortBy}
    WHEN 'io' THEN SUM_ROWS_EXAMINED
    WHEN 'executions' THEN COUNT_STAR
    ELSE SUM_TIMER_WAIT END DESC
LIMIT ${limit}

-- @family: oracle
-- @limit: 20
-- @requires: SELECT on V$SQL
SELECT sql_id AS query_hash, executions AS execution_count, ROUND(cpu_time / 1000) AS total_cpu_ms,
       ROUND(elapsed_time / 1000) AS total_duration_ms, buffer_gets AS total_logical_reads, disk_reads AS total_physical_reads,
       rows_processed AS total_rows, parsing_schema_name AS database_name, last_active_time,
       SUBSTR(sql_text, 1, 1000) AS sql_text
FROM v$sql
WHERE parsing_schema_name NOT IN ('SYS', 'SYSTEM')
ORDER BY CASE ${sortBy} WHEN 'io' THEN buffer_gets WHEN 'duration' THEN elapsed_time WHEN 'executions' THEN executions ELSE cpu_time END DESC
FETCH FIRST ${limit} ROWS ONLY
