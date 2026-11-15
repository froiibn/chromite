-- getQueryStoreRegressions: queries slower in the recent window than in the earlier one (Query Store of the current database)
-- @family: sqlserver
-- @requires: VIEW DATABASE STATE (VIEW DATABASE PERFORMANCE STATE on SQL Server 2022)
-- @note: An interval belongs to the recent window when it starts inside it. plan_changed = 1 when the recent window ran a plan the earlier one did not: compare the plans (getCachedPlans, explainQuery) before forcing one. Durations are in milliseconds.
-- @statement: queryStore
SELECT DB_NAME() AS database_name, actual_state_desc, desired_state_desc, readonly_reason, query_capture_mode_desc,
       current_storage_size_mb, max_storage_size_mb, interval_length_minutes, stale_query_threshold_days
FROM sys.database_query_store_options
-- @statement: regressions
-- @limit: 20
WITH bounds AS (
    SELECT DATEADD(MINUTE, 0 - CAST(${recentMinutes} AS int), SYSUTCDATETIME()) AS recent_start,
           DATEADD(MINUTE, 0 - CAST(${recentMinutes} AS int) - CAST(${historyMinutes} AS int), SYSUTCDATETIME()) AS history_start
), stats AS (
    SELECT p.query_id, rs.plan_id,
           CASE WHEN i.start_time >= b.recent_start THEN 'recent' ELSE 'history' END AS period,
           rs.count_executions,
           rs.avg_duration * rs.count_executions AS duration_us,
           rs.avg_cpu_time * rs.count_executions AS cpu_us,
           rs.avg_logical_io_reads * rs.count_executions AS reads
    FROM sys.query_store_runtime_stats rs
    JOIN sys.query_store_plan p ON p.plan_id = rs.plan_id
    JOIN sys.query_store_runtime_stats_interval i ON i.runtime_stats_interval_id = rs.runtime_stats_interval_id
    CROSS JOIN bounds b
    WHERE i.end_time > b.history_start
), totals AS (
    SELECT query_id, period, SUM(count_executions) AS executions,
           SUM(duration_us) / NULLIF(SUM(count_executions), 0) AS avg_duration_us,
           SUM(cpu_us) / NULLIF(SUM(count_executions), 0) AS avg_cpu_us,
           SUM(reads) / NULLIF(SUM(count_executions), 0) AS avg_reads
    FROM stats
    GROUP BY query_id, period
), plans AS (
    SELECT query_id, period, STRING_AGG(CAST(plan_id AS varchar(20)), ',') WITHIN GROUP (ORDER BY plan_id) AS plan_ids
    FROM (SELECT DISTINCT query_id, period, plan_id FROM stats) d
    GROUP BY query_id, period
)
SELECT TOP (${limit})
    r.query_id,
    OBJECT_SCHEMA_NAME(q.object_id) + '.' + OBJECT_NAME(q.object_id) AS sql_object,
    h.executions AS history_executions,
    r.executions AS recent_executions,
    CAST(h.avg_duration_us / 1000.0 AS decimal(18, 2)) AS history_avg_duration_ms,
    CAST(r.avg_duration_us / 1000.0 AS decimal(18, 2)) AS recent_avg_duration_ms,
    CAST(r.avg_duration_us / NULLIF(h.avg_duration_us, 0) AS decimal(18, 2)) AS duration_ratio,
    CAST(h.avg_cpu_us / 1000.0 AS decimal(18, 2)) AS history_avg_cpu_ms,
    CAST(r.avg_cpu_us / 1000.0 AS decimal(18, 2)) AS recent_avg_cpu_ms,
    CAST(h.avg_reads AS decimal(18, 1)) AS history_avg_logical_reads,
    CAST(r.avg_reads AS decimal(18, 1)) AS recent_avg_logical_reads,
    ph.plan_ids AS history_plan_ids,
    pr.plan_ids AS recent_plan_ids,
    CASE WHEN EXISTS (SELECT 1 FROM stats s WHERE s.query_id = r.query_id AND s.period = 'recent'
                      AND NOT EXISTS (SELECT 1 FROM stats o WHERE o.query_id = s.query_id AND o.period = 'history' AND o.plan_id = s.plan_id))
         THEN 1 ELSE 0 END AS plan_changed,
    CAST((r.avg_duration_us - h.avg_duration_us) * r.executions / 1000.0 AS decimal(18, 1)) AS added_duration_ms,
    LEFT(qt.query_sql_text, 4000) AS sql_text
FROM totals r
JOIN totals h ON h.query_id = r.query_id AND h.period = 'history'
JOIN plans pr ON pr.query_id = r.query_id AND pr.period = 'recent'
JOIN plans ph ON ph.query_id = r.query_id AND ph.period = 'history'
JOIN sys.query_store_query q ON q.query_id = r.query_id
JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
WHERE r.period = 'recent'
  AND r.executions >= ${minExecutions} AND h.executions >= ${minExecutions}
  AND r.avg_duration_us > h.avg_duration_us
ORDER BY (r.avg_duration_us - h.avg_duration_us) * r.executions DESC
