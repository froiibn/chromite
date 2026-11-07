-- getQueryStoreTopQueries: top queries of the Query Store of the current database (DiagnosticPostProcessor reports an OFF Query Store)
-- @family: sqlserver
-- @requires: VIEW DATABASE STATE (VIEW DATABASE PERFORMANCE STATE on SQL Server 2022)
-- @note: Runtime statistics are kept per interval (interval_length_minutes); the window covers every interval that ended in the last hours. Durations and CPU are in milliseconds.
-- @statement: queryStore
SELECT DB_NAME() AS database_name, actual_state_desc, desired_state_desc, readonly_reason, query_capture_mode_desc,
       current_storage_size_mb, max_storage_size_mb, interval_length_minutes, stale_query_threshold_days
FROM sys.database_query_store_options
-- @statement: queries
SELECT TOP (${top})
    q.query_id,
    OBJECT_SCHEMA_NAME(q.object_id) + '.' + OBJECT_NAME(q.object_id) AS sql_object,
    COUNT(DISTINCT p.plan_id) AS plan_count,
    SUM(rs.count_executions) AS execution_count,
    CAST(SUM(rs.avg_cpu_time * rs.count_executions) / 1000.0 AS decimal(18, 1)) AS total_cpu_ms,
    CAST(SUM(rs.avg_cpu_time * rs.count_executions) / NULLIF(SUM(rs.count_executions), 0) / 1000.0 AS decimal(18, 2)) AS avg_cpu_ms,
    CAST(SUM(rs.avg_duration * rs.count_executions) / 1000.0 AS decimal(18, 1)) AS total_duration_ms,
    CAST(SUM(rs.avg_duration * rs.count_executions) / NULLIF(SUM(rs.count_executions), 0) / 1000.0 AS decimal(18, 2)) AS avg_duration_ms,
    CAST(SUM(rs.avg_logical_io_reads * rs.count_executions) AS bigint) AS total_logical_reads,
    CAST(SUM(rs.avg_logical_io_reads * rs.count_executions) / NULLIF(SUM(rs.count_executions), 0) AS decimal(18, 1)) AS avg_logical_reads,
    MAX(rs.last_execution_time) AS last_execution_time,
    LEFT(qt.query_sql_text, 4000) AS sql_text
FROM sys.query_store_query q
JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
JOIN sys.query_store_plan p ON p.query_id = q.query_id
JOIN sys.query_store_runtime_stats rs ON rs.plan_id = p.plan_id
JOIN sys.query_store_runtime_stats_interval i ON i.runtime_stats_interval_id = rs.runtime_stats_interval_id
WHERE i.end_time >= DATEADD(HOUR, 0 - CAST(${hours} AS int), SYSUTCDATETIME())
GROUP BY q.query_id, q.object_id, qt.query_sql_text
ORDER BY CASE ${orderBy}
    WHEN 'duration' THEN SUM(rs.avg_duration * rs.count_executions)
    WHEN 'io' THEN SUM(rs.avg_logical_io_reads * rs.count_executions)
    WHEN 'executions' THEN SUM(rs.count_executions)
    ELSE SUM(rs.avg_cpu_time * rs.count_executions) END DESC
