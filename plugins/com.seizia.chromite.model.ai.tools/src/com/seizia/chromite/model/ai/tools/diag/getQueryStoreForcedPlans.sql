-- getQueryStoreForcedPlans: plans forced through the Query Store of the current database (read only)
-- @family: sqlserver
-- @requires: VIEW DATABASE STATE (VIEW DATABASE PERFORMANCE STATE on SQL Server 2022)
-- @note: plan_forcing_type_desc AUTO means automatic tuning (FORCE_LAST_GOOD_PLAN) forced the plan. A failed force (force_failure_count > 0) leaves the optimizer free to choose another plan.
-- @statement: queryStore
SELECT DB_NAME() AS database_name, actual_state_desc, desired_state_desc, readonly_reason, query_capture_mode_desc,
       current_storage_size_mb, max_storage_size_mb, interval_length_minutes, stale_query_threshold_days
FROM sys.database_query_store_options
-- @statement: forcedPlans
SELECT p.query_id, p.plan_id, p.plan_forcing_type_desc, p.force_failure_count, p.last_force_failure_reason_desc,
       p.count_compiles, p.last_compile_start_time, p.last_execution_time,
       (SELECT COUNT(*) FROM sys.query_store_plan o WHERE o.query_id = p.query_id) AS query_plan_count,
       OBJECT_SCHEMA_NAME(q.object_id) + '.' + OBJECT_NAME(q.object_id) AS sql_object,
       LEFT(qt.query_sql_text, 4000) AS sql_text
FROM sys.query_store_plan p
JOIN sys.query_store_query q ON q.query_id = p.query_id
JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id
WHERE p.is_forced_plan = 1
ORDER BY p.query_id, p.plan_id
