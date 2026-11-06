-- listOpenTransactions: long-open and idle-in-transaction sessions
-- @family: sqlserver
-- @requires: VIEW SERVER STATE
SELECT
    s.session_id,
    s.login_name,
    s.host_name,
    s.program_name,
    s.status,
    at.name AS transaction_name,
    at.transaction_begin_time AS tx_begin_time,
    DATEDIFF(SECOND, at.transaction_begin_time, SYSDATETIME()) AS tx_age_sec,
    CASE at.transaction_state WHEN 0 THEN 'not initialized' WHEN 1 THEN 'initialized, not started' WHEN 2 THEN 'active'
        WHEN 3 THEN 'ended (read-only)' WHEN 4 THEN 'commit initiated' WHEN 5 THEN 'prepared' WHEN 6 THEN 'committed'
        WHEN 7 THEN 'rolling back' WHEN 8 THEN 'rolled back' END AS transaction_state,
    s.open_transaction_count,
    (SELECT SUM(dt.database_transaction_log_bytes_used) FROM sys.dm_tran_database_transactions dt
       WHERE dt.transaction_id = at.transaction_id) AS log_bytes_used,
    (SELECT COUNT(*) FROM sys.dm_tran_locks l WHERE l.request_session_id = s.session_id) AS lock_count,
    LEFT(st.text, 4000) AS last_sql_text
FROM sys.dm_tran_session_transactions tst
JOIN sys.dm_tran_active_transactions at ON at.transaction_id = tst.transaction_id
JOIN sys.dm_exec_sessions s ON s.session_id = tst.session_id
LEFT JOIN sys.dm_exec_connections c ON c.session_id = s.session_id
OUTER APPLY sys.dm_exec_sql_text(c.most_recent_sql_handle) st
WHERE s.is_user_process = 1 AND s.session_id <> @@SPID
  AND DATEDIFF(SECOND, at.transaction_begin_time, SYSDATETIME()) >= ${minSeconds}
ORDER BY at.transaction_begin_time

-- @family: postgresql
SELECT a.pid AS session_id, a.usename AS login_name, a.client_addr::text AS host_name, a.application_name AS program_name,
       a.state AS status, a.xact_start AS tx_begin_time, (extract(epoch FROM now() - a.xact_start))::bigint AS tx_age_sec,
       (extract(epoch FROM now() - a.state_change))::bigint AS state_age_sec,
       a.backend_xid::text AS transaction_id, a.backend_xmin::text AS xmin,
       (SELECT count(*) FROM pg_locks l WHERE l.pid = a.pid) AS lock_count,
       left(a.query, 4000) AS last_sql_text
FROM pg_stat_activity a
WHERE a.xact_start IS NOT NULL AND a.pid <> pg_backend_pid() AND a.backend_type = 'client backend'
  AND extract(epoch FROM now() - a.xact_start) >= ${minSeconds}
ORDER BY a.xact_start

-- @family: mysql
-- @requires: PROCESS
SELECT t.trx_mysql_thread_id AS session_id, p.USER AS login_name, p.HOST AS host_name, p.DB AS database_name,
       p.COMMAND AS command, t.trx_state AS status, t.trx_started AS tx_begin_time,
       TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS tx_age_sec, t.trx_isolation_level AS isolation,
       t.trx_rows_locked, t.trx_rows_modified, t.trx_tables_locked, LEFT(COALESCE(t.trx_query, p.INFO), 4000) AS last_sql_text
FROM information_schema.INNODB_TRX t
LEFT JOIN information_schema.PROCESSLIST p ON p.ID = t.trx_mysql_thread_id
WHERE t.trx_mysql_thread_id <> CONNECTION_ID()
  AND TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) >= ${minSeconds}
ORDER BY t.trx_started

-- @family: oracle
-- @requires: SELECT on V$TRANSACTION, V$SESSION
SELECT s.sid AS session_id, s.serial# AS serial, s.username AS login_name, s.machine AS host_name, s.program AS program_name,
       s.status, t.start_date AS tx_begin_time, ROUND((SYSDATE - t.start_date) * 86400) AS tx_age_sec,
       t.used_ublk AS undo_blocks, t.used_urec AS undo_records,
       (SELECT SUBSTR(q.sql_text, 1, 1000) FROM v$sql q WHERE q.sql_id = s.prev_sql_id AND ROWNUM = 1) AS last_sql_text
FROM v$transaction t
JOIN v$session s ON s.taddr = t.addr
WHERE (SYSDATE - t.start_date) * 86400 >= ${minSeconds}
ORDER BY t.start_date
