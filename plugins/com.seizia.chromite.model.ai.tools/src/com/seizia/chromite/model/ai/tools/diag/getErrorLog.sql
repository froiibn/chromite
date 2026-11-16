-- getErrorLog: recent server log entries (newest first where the server allows)
-- @family: sqlserver
-- @requires: securityadmin or sysadmin
EXEC master.dbo.xp_readerrorlog 0, 1, ${contains}, NULL, NULL, NULL, N'desc'

-- @family: postgresql
-- @requires: pg_read_server_files or superuser; logging_collector = on (Docker images log to stderr by default)
-- @maxCellChars: 300000
SELECT pg_current_logfile() AS log_file,
       pg_read_file(pg_current_logfile(), greatest(0, (pg_stat_file(pg_current_logfile())).size - 262144), 262144) AS tail

-- @family: mysql
-- @requires: SELECT on performance_schema.error_log (MySQL 8.0.22+)
SELECT LOGGED, PRIO, ERROR_CODE, SUBSYSTEM, LEFT(DATA, 2000) AS message
FROM performance_schema.error_log
WHERE ${contains} IS NULL OR DATA LIKE CONCAT('%', ${contains}, '%')
ORDER BY LOGGED DESC
LIMIT ${limit}

-- @family: mariadb
-- @note: MariaDB has no SQL view of the error log; this is the file to read on the server host.
SELECT @@log_error AS log_error_file

-- @family: oracle
-- @requires: SELECT on V$DIAG_ALERT_EXT, which SELECT_CATALOG_ROLE does not include: as SYS, GRANT SELECT ON SYS.V_$DIAG_ALERT_EXT TO <user>
SELECT originating_timestamp, message_level, SUBSTR(message_text, 1, 2000) AS message
FROM v$diag_alert_ext
WHERE originating_timestamp > SYSTIMESTAMP - INTERVAL '7' DAY
  AND (${contains} IS NULL OR message_text LIKE '%' || ${contains} || '%')
ORDER BY originating_timestamp DESC
FETCH FIRST ${limit} ROWS ONLY
