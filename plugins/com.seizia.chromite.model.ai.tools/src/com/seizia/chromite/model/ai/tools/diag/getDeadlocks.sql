-- getDeadlocks: recent deadlocks (SQL Server XML graphs parsed by DiagnosticPostProcessor)
-- @family: sqlserver
-- @requires: VIEW SERVER STATE (VIEW SERVER PERFORMANCE STATE on SQL Server 2022)
-- @maxCellChars: 500000
SELECT TOP (${limit}) x.event_time, x.event_xml
FROM (
    SELECT CAST(f.event_data AS xml).value('(event/@timestamp)[1]', 'datetime2') AS event_time,
           CAST(f.event_data AS nvarchar(max)) AS event_xml
    FROM sys.fn_xe_file_target_read_file('system_health*.xel', NULL, NULL, NULL) f
    WHERE f.object_name = 'xml_deadlock_report'
) x
ORDER BY x.event_time DESC

-- @family: mysql
-- @requires: PROCESS
-- @maxCellChars: 1000000
SHOW ENGINE INNODB STATUS

-- @family: postgresql
-- @note: PostgreSQL keeps only deadlock counters; the details (both statements) are written to the server log. Use getErrorLog with contains="deadlock".
SELECT datname AS database_name, deadlocks, conflicts, stats_reset
FROM pg_stat_database
WHERE datname IS NOT NULL
ORDER BY deadlocks DESC
