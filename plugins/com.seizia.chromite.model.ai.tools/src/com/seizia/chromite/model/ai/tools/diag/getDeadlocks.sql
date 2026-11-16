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

-- @family: oracle
-- @requires: SELECT on V$DIAG_ALERT_EXT, which SELECT_CATALOG_ROLE does not include: as SYS, GRANT SELECT ON SYS.V_$DIAG_ALERT_EXT TO <user>
-- @requires: SELECT_CATALOG_ROLE (or SELECT ANY DICTIONARY) for the deadlock graph in the trace file (V$DIAG_TRACE_FILE_CONTENTS)
-- @note: Oracle writes each deadlock (ORA-00060) to the alert log and the deadlock graph (resources, sessions, their SQL) to the trace file the entry names; the graph is read from V$DIAG_TRACE_FILE_CONTENTS. Last 30 days.
SELECT originating_timestamp AS event_time, SUBSTR(message_text, 1, 2000) AS message,
       REGEXP_SUBSTR(message_text, '[^/\\ ]+\.trc') AS trace_file
FROM v$diag_alert_ext
WHERE message_text LIKE '%ORA-00060%'
  AND originating_timestamp > SYSTIMESTAMP - INTERVAL '30' DAY
ORDER BY originating_timestamp DESC
FETCH FIRST ${limit} ROWS ONLY
