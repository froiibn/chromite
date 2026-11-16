-- getLongRunningQueries: requests running longer than minSeconds
-- @family: sqlserver
-- @requires: VIEW SERVER STATE
SELECT
    r.session_id,
    s.login_name,
    s.host_name,
    s.program_name,
    DB_NAME(r.database_id) AS database_name,
    r.status,
    r.command,
    r.total_elapsed_time / 1000 AS elapsed_sec,
    r.cpu_time AS cpu_ms,
    r.logical_reads,
    r.reads,
    r.writes,
    r.wait_type,
    r.wait_time AS wait_time_ms,
    r.blocking_session_id AS blocked_by,
    r.percent_complete,
    r.granted_query_memory * 8 AS granted_memory_kb,
    r.dop,
    OBJECT_SCHEMA_NAME(st.objectid, st.dbid) + '.' + OBJECT_NAME(st.objectid, st.dbid) AS sql_object,
    SUBSTRING(st.text, r.statement_start_offset / 2 + 1,
        (CASE WHEN r.statement_end_offset = -1 THEN DATALENGTH(st.text) ELSE r.statement_end_offset END - r.statement_start_offset) / 2 + 1) AS sql_text
FROM sys.dm_exec_requests r
JOIN sys.dm_exec_sessions s ON s.session_id = r.session_id
OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) st
WHERE s.is_user_process = 1 AND r.session_id <> @@SPID
  AND r.total_elapsed_time >= ${minSeconds} * 1000
ORDER BY r.total_elapsed_time DESC

-- @family: postgresql
SELECT a.pid AS session_id, a.usename AS login_name, a.client_addr::text AS host_name, a.application_name AS program_name,
       a.datname AS database_name, a.state AS status, (extract(epoch FROM now() - a.query_start))::bigint AS elapsed_sec,
       a.wait_event_type, a.wait_event, pg_blocking_pids(a.pid)::text AS blocked_by, left(a.query, 4000) AS sql_text
FROM pg_stat_activity a
WHERE a.state = 'active' AND a.pid <> pg_backend_pid() AND a.backend_type = 'client backend'
  AND extract(epoch FROM now() - a.query_start) >= ${minSeconds}
ORDER BY a.query_start

-- @family: mysql
-- @requires: PROCESS
SELECT ID AS session_id, USER AS login_name, HOST AS host_name, DB AS database_name, COMMAND AS command, TIME AS elapsed_sec,
       STATE AS state, LEFT(INFO, 4000) AS sql_text
FROM information_schema.PROCESSLIST
WHERE COMMAND NOT IN ('Sleep', 'Daemon', 'Binlog Dump') AND ID <> CONNECTION_ID() AND TIME >= ${minSeconds}
ORDER BY TIME DESC

-- @family: oracle
-- @requires: SELECT on V$SESSION
SELECT s.sid AS session_id, s.serial# AS serial, s.username AS login_name, s.machine AS host_name, s.program AS program_name,
       s.last_call_et AS elapsed_sec, s.event AS wait_event, s.blocking_session AS blocked_by, s.sql_id,
       (SELECT SUBSTR(q.sql_text, 1, 1000) FROM v$sql q WHERE q.sql_id = s.sql_id AND ROWNUM = 1) AS sql_text
FROM v$session s
WHERE s.status = 'ACTIVE' AND s.type = 'USER' AND s.sid <> SYS_CONTEXT('USERENV', 'SID') AND s.last_call_et >= ${minSeconds}
ORDER BY s.last_call_et DESC
