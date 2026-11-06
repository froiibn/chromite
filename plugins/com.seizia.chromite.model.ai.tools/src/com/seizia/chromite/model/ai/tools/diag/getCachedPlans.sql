-- getCachedPlans: cached plans per statement of a routine or of statements containing a text (parsed by PlanParser)
-- @family: sqlserver
-- @requires: VIEW SERVER STATE
-- @maxCellChars: 2000000
SELECT TOP (${top})
    OBJECT_SCHEMA_NAME(st.objectid, st.dbid) + '.' + OBJECT_NAME(st.objectid, st.dbid) AS sql_object,
    (DATALENGTH(LEFT(st.text, qs.statement_start_offset / 2)) - DATALENGTH(REPLACE(LEFT(st.text, qs.statement_start_offset / 2), NCHAR(10), N''))) / 2 + 1 AS start_line,
    qs.execution_count,
    qs.total_worker_time / 1000 AS total_cpu_ms,
    qs.total_logical_reads,
    qs.plan_generation_num,
    qs.creation_time,
    qs.last_execution_time,
    LEFT(SUBSTRING(st.text, qs.statement_start_offset / 2 + 1,
        (CASE WHEN qs.statement_end_offset = -1 THEN DATALENGTH(st.text) ELSE qs.statement_end_offset END - qs.statement_start_offset) / 2 + 1), 4000) AS sql_text,
    tqp.query_plan
FROM sys.dm_exec_query_stats qs
CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st
OUTER APPLY sys.dm_exec_text_query_plan(qs.plan_handle, qs.statement_start_offset, qs.statement_end_offset) tqp
WHERE (${name} IS NULL OR (st.objectid = OBJECT_ID(${name}) AND st.dbid = DB_ID()))
  AND (${contains} IS NULL OR st.text LIKE '%' + ${contains} + '%')
  AND st.text NOT LIKE '%dm_exec_text_query_plan%'
ORDER BY CASE WHEN ${name} IS NULL THEN 0 ELSE qs.statement_start_offset END, qs.total_worker_time DESC
