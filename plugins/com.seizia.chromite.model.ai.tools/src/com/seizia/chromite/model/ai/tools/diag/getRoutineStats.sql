-- getRoutineStats: runtime statistics of a routine and of each statement inside it (with line numbers)
-- @family: sqlserver
-- @requires: VIEW SERVER STATE
-- @statement: routine
SELECT 'PROCEDURE' AS kind, ps.execution_count, ps.total_worker_time / 1000 AS total_cpu_ms, ps.total_elapsed_time / 1000 AS total_duration_ms,
       ps.total_elapsed_time / 1000 / NULLIF(ps.execution_count, 0) AS avg_duration_ms, ps.total_logical_reads, ps.total_logical_writes,
       ps.cached_time, ps.last_execution_time
FROM sys.dm_exec_procedure_stats ps
WHERE ps.object_id = OBJECT_ID(${object}) AND ps.database_id = DB_ID()
UNION ALL
SELECT 'TRIGGER', ts.execution_count, ts.total_worker_time / 1000, ts.total_elapsed_time / 1000,
       ts.total_elapsed_time / 1000 / NULLIF(ts.execution_count, 0), ts.total_logical_reads, ts.total_logical_writes, ts.cached_time, ts.last_execution_time
FROM sys.dm_exec_trigger_stats ts
WHERE ts.object_id = OBJECT_ID(${object}) AND ts.database_id = DB_ID()
UNION ALL
SELECT 'FUNCTION', fs.execution_count, fs.total_worker_time / 1000, fs.total_elapsed_time / 1000,
       fs.total_elapsed_time / 1000 / NULLIF(fs.execution_count, 0), fs.total_logical_reads, fs.total_logical_writes, fs.cached_time, fs.last_execution_time
FROM sys.dm_exec_function_stats fs
WHERE fs.object_id = OBJECT_ID(${object}) AND fs.database_id = DB_ID()
-- @statement: statements
SELECT
    (DATALENGTH(LEFT(st.text, qs.statement_start_offset / 2)) - DATALENGTH(REPLACE(LEFT(st.text, qs.statement_start_offset / 2), NCHAR(10), N''))) / 2 + 1 AS start_line,
    qs.execution_count,
    qs.total_worker_time / 1000 AS total_cpu_ms,
    qs.total_worker_time / 1000 / NULLIF(qs.execution_count, 0) AS avg_cpu_ms,
    qs.total_elapsed_time / 1000 AS total_duration_ms,
    qs.total_logical_reads,
    qs.total_logical_reads / NULLIF(qs.execution_count, 0) AS avg_logical_reads,
    qs.total_rows,
    qs.total_rows / NULLIF(qs.execution_count, 0) AS avg_rows,
    qs.min_rows,
    qs.max_rows,
    qs.plan_generation_num,
    qs.last_execution_time,
    SUBSTRING(st.text, qs.statement_start_offset / 2 + 1,
        (CASE WHEN qs.statement_end_offset = -1 THEN DATALENGTH(st.text) ELSE qs.statement_end_offset END - qs.statement_start_offset) / 2 + 1) AS sql_text
FROM sys.dm_exec_query_stats qs
CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
WHERE st.objectid = OBJECT_ID(${object}) AND st.dbid = DB_ID()
ORDER BY qs.statement_start_offset

-- @family: postgresql
-- @requires: track_functions = pl or all (routine); pg_stat_statements with pg_stat_statements.track = all (statements)
-- @note: pg_stat_statements does not link nested statements to their function; the statements are the top non-top-level ones of this database. Match them against the source (getRoutineSource).
-- @statement: routine
SELECT schemaname || '.' || funcname AS routine, calls AS execution_count, round(total_time::numeric, 1) AS total_duration_ms,
       round(self_time::numeric, 1) AS self_duration_ms, round((total_time / nullif(calls, 0))::numeric, 2) AS avg_duration_ms
FROM pg_stat_user_functions
WHERE funcname = ${object} OR schemaname || '.' || funcname = ${object}
-- @statement: statements
SELECT calls AS execution_count, round(total_exec_time::numeric, 1) AS total_duration_ms, round(mean_exec_time::numeric, 2) AS avg_duration_ms,
       rows AS total_rows, shared_blks_hit, shared_blks_read, left(query, 2000) AS sql_text
FROM pg_stat_statements
WHERE NOT toplevel AND dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY total_exec_time DESC
LIMIT ${limit}

-- @family: mysql
-- @requires: performance_schema enabled
-- @statement: routine
SELECT CONCAT(OBJECT_SCHEMA, '.', OBJECT_NAME) AS routine, OBJECT_TYPE AS kind, COUNT_STAR AS execution_count,
       ROUND(SUM_TIMER_WAIT / 1000000000, 1) AS total_duration_ms, ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS avg_duration_ms,
       COUNT_STATEMENTS AS statements_executed, ROUND(SUM_STATEMENTS_WAIT / 1000000000, 1) AS statements_duration_ms,
       SUM_ROWS_EXAMINED AS rows_examined, SUM_NO_INDEX_USED AS no_index_used
FROM performance_schema.events_statements_summary_by_program
WHERE OBJECT_NAME = SUBSTRING_INDEX(${object}, '.', -1)
  AND OBJECT_SCHEMA = IF(LOCATE('.', ${object}) > 0, SUBSTRING_INDEX(${object}, '.', 1), DATABASE())

-- @family: oracle
-- @requires: SELECT on V$SQL (SELECT_CATALOG_ROLE or SELECT ANY DICTIONARY)
-- @note: V$SQL keeps the statements a PL/SQL unit ran (PROGRAM_ID, PROGRAM_LINE#) while their cursors stay in the shared pool. Oracle keeps no per-call totals of the unit itself outside the Diagnostics Pack, so the routine row sums its statements. A package is named by its package name.
-- @statement: routine
SELECT o.owner || '.' || o.object_name AS routine, o.object_type AS kind, COUNT(s.sql_id) AS statements,
       SUM(s.executions) AS statement_executions, ROUND(SUM(s.elapsed_time) / 1000, 1) AS total_duration_ms,
       ROUND(SUM(s.cpu_time) / 1000, 1) AS total_cpu_ms, SUM(s.buffer_gets) AS total_logical_reads, SUM(s.disk_reads) AS total_physical_reads,
       MAX(s.last_active_time) AS last_execution_time
FROM all_objects o
LEFT JOIN v$sql s ON s.program_id = o.object_id
WHERE o.object_type IN ('PROCEDURE', 'FUNCTION', 'PACKAGE BODY', 'TRIGGER', 'TYPE BODY')
  AND ((o.owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND o.object_name = UPPER(${object}))
       OR o.owner || '.' || o.object_name = UPPER(${object}))
GROUP BY o.owner, o.object_name, o.object_type
-- @statement: statements
SELECT s.program_line# AS start_line, s.sql_id, s.child_number, s.executions AS execution_count,
       ROUND(s.cpu_time / 1000, 1) AS total_cpu_ms, ROUND(s.cpu_time / 1000 / NULLIF(s.executions, 0), 3) AS avg_cpu_ms,
       ROUND(s.elapsed_time / 1000, 1) AS total_duration_ms, s.buffer_gets AS total_logical_reads,
       ROUND(s.buffer_gets / NULLIF(s.executions, 0), 1) AS avg_logical_reads, s.rows_processed AS total_rows,
       s.disk_reads AS total_physical_reads, s.plan_hash_value, s.last_active_time AS last_execution_time,
       SUBSTR(s.sql_text, 1, 2000) AS sql_text
FROM v$sql s
JOIN all_objects o ON o.object_id = s.program_id
WHERE o.object_type IN ('PROCEDURE', 'FUNCTION', 'PACKAGE BODY', 'TRIGGER', 'TYPE BODY')
  AND ((o.owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND o.object_name = UPPER(${object}))
       OR o.owner || '.' || o.object_name = UPPER(${object}))
ORDER BY s.program_line#, s.child_number
FETCH FIRST ${limit} ROWS ONLY
