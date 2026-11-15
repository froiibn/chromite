-- getJobHistory: job runs (failures by default) of the last days. The chat offers it for PostgreSQL, MySQL and Oracle only
-- (DiagnosticSqlTool.supports); the SQL Server block serves direct calls (MCP, saved conversations) until 26.2.0, then goes:
-- getAgentJobHistory reads SQL Server Agent jobs
-- @family: sqlserver
-- @requires: SELECT on msdb job tables (SQLAgentReaderRole)
-- @statement: runs
SELECT TOP (${limit}) j.name AS job_name, j.enabled,
       msdb.dbo.agent_datetime(h.run_date, h.run_time) AS run_time,
       CASE h.run_status WHEN 0 THEN 'Failed' WHEN 1 THEN 'Succeeded' WHEN 2 THEN 'Retry' WHEN 3 THEN 'Canceled' WHEN 4 THEN 'In progress' END AS outcome,
       h.step_id, h.step_name,
       (h.run_duration / 10000) * 3600 + (h.run_duration / 100 % 100) * 60 + h.run_duration % 100 AS duration_sec,
       LEFT(h.message, 2000) AS message
FROM msdb.dbo.sysjobhistory h
JOIN msdb.dbo.sysjobs j ON j.job_id = h.job_id
WHERE msdb.dbo.agent_datetime(h.run_date, h.run_time) >= DATEADD(DAY, 0 - CAST(${days} AS int), GETDATE())
  AND (${failedOnly} = 0 OR h.run_status IN (0, 3))
ORDER BY h.run_date DESC, h.run_time DESC
-- @statement: jobs
SELECT j.name AS job_name, j.enabled, js.last_run_outcome,
       CASE WHEN js.last_run_date > 0 THEN msdb.dbo.agent_datetime(js.last_run_date, js.last_run_time) END AS last_run_time,
       LEFT(js.last_outcome_message, 1000) AS last_message
FROM msdb.dbo.sysjobs j
LEFT JOIN msdb.dbo.sysjobservers js ON js.job_id = j.job_id
ORDER BY j.name

-- @family: postgresql
-- @requires: pg_cron extension (cron.job_run_details)
SELECT jobid, runid, database, username, status AS outcome, start_time AS run_time, end_time,
       (extract(epoch FROM end_time - start_time))::bigint AS duration_sec, left(command, 1000) AS command, left(return_message, 2000) AS message
FROM cron.job_run_details
WHERE start_time >= now() - make_interval(days => ${days}::int)
  AND (${failedOnly} = 0 OR status = 'failed')
ORDER BY start_time DESC
LIMIT ${limit}

-- @family: mysql
-- @note: MySQL/MariaDB events keep no run history; this lists the events and their last execution.
SELECT EVENT_SCHEMA, EVENT_NAME AS job_name, STATUS AS enabled, EVENT_TYPE, INTERVAL_VALUE, INTERVAL_FIELD, LAST_EXECUTED AS last_run_time,
       ON_COMPLETION, DEFINER
FROM information_schema.EVENTS
ORDER BY LAST_EXECUTED DESC

-- @family: oracle
SELECT owner, job_name, status AS outcome, actual_start_date AS run_time, run_duration, error#, SUBSTR(additional_info, 1, 2000) AS message
FROM all_scheduler_job_run_details
WHERE actual_start_date >= SYSTIMESTAMP - NUMTODSINTERVAL(${days}, 'DAY')
  AND (${failedOnly} = 0 OR status <> 'SUCCEEDED')
ORDER BY actual_start_date DESC
FETCH FIRST ${limit} ROWS ONLY
