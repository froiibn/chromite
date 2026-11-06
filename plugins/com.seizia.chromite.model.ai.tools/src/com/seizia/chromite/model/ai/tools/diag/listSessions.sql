-- listSessions: user sessions with their current request
-- @family: sqlserver
-- @requires: VIEW SERVER STATE
SELECT
    s.session_id,
    s.login_name,
    s.host_name,
    s.program_name,
    DB_NAME(COALESCE(r.database_id, s.database_id)) AS database_name,
    s.status,
    r.status AS request_status,
    r.command,
    r.blocking_session_id AS blocked_by,
    r.wait_type,
    r.wait_time AS wait_time_ms,
    r.total_elapsed_time AS elapsed_ms,
    s.open_transaction_count,
    s.cpu_time AS cpu_ms,
    s.logical_reads,
    s.last_request_start_time,
    s.last_request_end_time,
    SUBSTRING(st.text, COALESCE(r.statement_start_offset, 0) / 2 + 1, 4000) AS sql_text
FROM sys.dm_exec_sessions s
LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id
LEFT JOIN sys.dm_exec_connections c ON c.session_id = s.session_id
OUTER APPLY sys.dm_exec_sql_text(COALESCE(r.sql_handle, c.most_recent_sql_handle)) st
WHERE s.is_user_process = 1
  AND (${activeOnly} = 0 OR r.session_id IS NOT NULL OR s.open_transaction_count > 0)
ORDER BY CASE WHEN r.session_id IS NULL THEN 1 ELSE 0 END, s.session_id
OFFSET 0 ROWS FETCH NEXT ${limit} ROWS ONLY

-- @family: postgresql
-- @requires: pg_monitor or pg_read_all_stats (to see other users' query text)
SELECT
    a.pid AS session_id,
    a.usename AS login_name,
    a.client_addr::text AS host_name,
    a.application_name AS program_name,
    a.datname AS database_name,
    a.state AS status,
    pg_blocking_pids(a.pid)::text AS blocked_by,
    a.wait_event_type,
    a.wait_event,
    a.xact_start,
    (extract(epoch FROM now() - a.xact_start))::bigint AS tx_age_sec,
    (extract(epoch FROM now() - a.query_start))::bigint AS query_age_sec,
    a.backend_start,
    left(a.query, 4000) AS sql_text
FROM pg_stat_activity a
WHERE a.backend_type = 'client backend' AND a.pid <> pg_backend_pid()
  AND (${activeOnly} = 0 OR a.state <> 'idle')
ORDER BY a.state = 'idle', a.pid
LIMIT ${limit}

-- @family: mysql
-- @requires: PROCESS (to see other users' sessions)
SELECT ID AS session_id, USER AS login_name, HOST AS host_name, DB AS database_name, COMMAND AS command,
       TIME AS time_sec, STATE AS state, LEFT(INFO, 4000) AS sql_text
FROM information_schema.PROCESSLIST
WHERE ID <> CONNECTION_ID()
  AND (${activeOnly} = 0 OR COMMAND <> 'Sleep')
ORDER BY COMMAND = 'Sleep', ID
LIMIT ${limit}

-- @family: oracle
-- @requires: SELECT on V$SESSION
SELECT s.sid || ',' || s.serial# AS session_id, s.username AS login_name, s.machine AS host_name, s.program AS program_name,
       s.status, s.blocking_session AS blocked_by, s.event AS wait_event, s.wait_class, s.seconds_in_wait,
       s.last_call_et AS seconds_since_last_call, s.sql_id, s.logon_time,
       (SELECT SUBSTR(q.sql_text, 1, 1000) FROM v$sql q WHERE q.sql_id = s.sql_id AND ROWNUM = 1) AS sql_text
FROM v$session s
WHERE s.type = 'USER' AND s.sid <> SYS_CONTEXT('USERENV', 'SID')
  AND (${activeOnly} = 0 OR s.status = 'ACTIVE')
ORDER BY s.status, s.sid
FETCH FIRST ${limit} ROWS ONLY
