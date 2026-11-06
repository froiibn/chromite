-- getBlockingChain: sessions that block or wait (DiagnosticPostProcessor builds the tree and the findings) and their locks.
-- Session columns used by the post-processor: session_id, blocked_by, status/state, tx_age_sec, sql_text, program_name,
-- host_name, login_name, isolation, wait_type/wait_event, wait_time_ms, wait_resource, lock_count.
-- @family: sqlserver
-- @requires: VIEW SERVER STATE
-- @statement: sessions
WITH involved AS (
    SELECT session_id FROM sys.dm_exec_requests WHERE blocking_session_id <> 0
    UNION
    SELECT blocking_session_id FROM sys.dm_exec_requests WHERE blocking_session_id <> 0
)
SELECT
    s.session_id,
    r.blocking_session_id AS blocked_by,
    s.login_name,
    s.host_name,
    s.program_name,
    s.status,
    r.command,
    r.wait_type,
    r.wait_time AS wait_time_ms,
    r.wait_resource,
    DB_NAME(COALESCE(r.database_id, s.database_id)) AS database_name,
    s.open_transaction_count,
    CASE s.transaction_isolation_level WHEN 0 THEN 'Unspecified' WHEN 1 THEN 'ReadUncommitted' WHEN 2 THEN 'ReadCommitted'
        WHEN 3 THEN 'RepeatableRead' WHEN 4 THEN 'Serializable' WHEN 5 THEN 'Snapshot' END AS isolation,
    (SELECT MIN(at.transaction_begin_time) FROM sys.dm_tran_session_transactions tst
        JOIN sys.dm_tran_active_transactions at ON at.transaction_id = tst.transaction_id
        WHERE tst.session_id = s.session_id) AS tx_begin_time,
    DATEDIFF(SECOND, (SELECT MIN(at.transaction_begin_time) FROM sys.dm_tran_session_transactions tst
        JOIN sys.dm_tran_active_transactions at ON at.transaction_id = tst.transaction_id
        WHERE tst.session_id = s.session_id), SYSDATETIME()) AS tx_age_sec,
    (SELECT COUNT(*) FROM sys.dm_tran_locks l WHERE l.request_session_id = s.session_id AND l.request_status = 'GRANT') AS lock_count,
    s.last_request_start_time,
    s.last_request_end_time,
    COALESCE(SUBSTRING(st.text, r.statement_start_offset / 2 + 1,
        (CASE WHEN r.statement_end_offset = -1 THEN DATALENGTH(st.text) ELSE r.statement_end_offset END - r.statement_start_offset) / 2 + 1),
        st.text) AS sql_text,
    OBJECT_SCHEMA_NAME(st.objectid, st.dbid) + '.' + OBJECT_NAME(st.objectid, st.dbid) AS sql_object
FROM sys.dm_exec_sessions s
JOIN involved i ON i.session_id = s.session_id
LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id
LEFT JOIN sys.dm_exec_connections c ON c.session_id = s.session_id
OUTER APPLY sys.dm_exec_sql_text(COALESCE(r.sql_handle, c.most_recent_sql_handle)) st
-- @statement: locks
SELECT
    l.request_session_id AS session_id,
    l.resource_type,
    DB_NAME(l.resource_database_id) AS database_name,
    l.request_mode,
    l.request_status,
    CASE WHEN l.resource_type = 'OBJECT'
            THEN OBJECT_SCHEMA_NAME(l.resource_associated_entity_id, l.resource_database_id) + '.' + OBJECT_NAME(l.resource_associated_entity_id, l.resource_database_id)
         WHEN l.resource_database_id = DB_ID() AND l.resource_type IN ('KEY', 'PAGE', 'RID', 'HOBT')
            THEN (SELECT OBJECT_SCHEMA_NAME(p.object_id) + '.' + OBJECT_NAME(p.object_id) FROM sys.partitions p WHERE p.hobt_id = l.resource_associated_entity_id)
    END AS object_name,
    CASE WHEN l.resource_database_id = DB_ID() AND l.resource_type IN ('KEY', 'PAGE', 'RID', 'HOBT')
            THEN (SELECT i.name FROM sys.partitions p JOIN sys.indexes i ON i.object_id = p.object_id AND i.index_id = p.index_id
                  WHERE p.hobt_id = l.resource_associated_entity_id)
    END AS index_name,
    RTRIM(MIN(l.resource_description)) AS sample_resource,
    COUNT(*) AS lock_count
FROM sys.dm_tran_locks l
WHERE l.resource_type <> 'DATABASE'
  AND l.request_session_id IN (SELECT session_id FROM sys.dm_exec_requests WHERE blocking_session_id <> 0
                               UNION SELECT blocking_session_id FROM sys.dm_exec_requests WHERE blocking_session_id <> 0)
GROUP BY l.request_session_id, l.resource_type, l.resource_database_id, l.request_mode, l.request_status, l.resource_associated_entity_id
ORDER BY l.request_status, lock_count DESC

-- @family: postgresql
-- @requires: pg_monitor or pg_read_all_stats (query text of other users)
-- @note: PostgreSQL does not expose the isolation level of other sessions.
-- @statement: sessions
WITH waiting AS (SELECT pid, pg_blocking_pids(pid) AS blockers FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0),
     involved AS (SELECT pid FROM waiting UNION SELECT unnest(blockers) FROM waiting)
SELECT
    a.pid AS session_id,
    w.blockers::text AS blocked_by,
    a.usename AS login_name,
    a.client_addr::text AS host_name,
    a.application_name AS program_name,
    a.state,
    a.wait_event_type,
    a.wait_event,
    (extract(epoch FROM now() - a.state_change) * 1000)::bigint AS wait_time_ms,
    a.datname AS database_name,
    a.xact_start AS tx_begin_time,
    (extract(epoch FROM now() - a.xact_start))::bigint AS tx_age_sec,
    (extract(epoch FROM now() - a.query_start))::bigint AS query_age_sec,
    (SELECT count(*) FROM pg_locks l WHERE l.pid = a.pid AND l.granted) AS lock_count,
    (SELECT string_agg(DISTINCT l.locktype || ' ' || l.mode || COALESCE(' on ' || l.relation::regclass::text, ''), '; ')
       FROM pg_locks l WHERE l.pid = a.pid AND NOT l.granted) AS wait_resource,
    left(a.query, 4000) AS sql_text
FROM pg_stat_activity a
JOIN involved i ON i.pid = a.pid
LEFT JOIN waiting w ON w.pid = a.pid
-- @statement: locks
WITH waiting AS (SELECT pid, pg_blocking_pids(pid) AS blockers FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0),
     involved AS (SELECT pid FROM waiting UNION SELECT unnest(blockers) FROM waiting)
SELECT l.pid AS session_id, l.locktype AS resource_type, l.mode AS request_mode,
       CASE WHEN l.granted THEN 'GRANT' ELSE 'WAIT' END AS request_status,
       CASE WHEN l.relation IS NOT NULL THEN l.relation::regclass::text END AS object_name,
       c.relkind::text AS object_kind, l.page, l.tuple, l.transactionid::text AS transaction_id, count(*) AS lock_count
FROM pg_locks l
JOIN involved i ON i.pid = l.pid
LEFT JOIN pg_class c ON c.oid = l.relation
WHERE l.database IS NULL OR l.database = (SELECT oid FROM pg_database WHERE datname = current_database())
GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9
ORDER BY 4 DESC, 1

-- @family: mariadb
-- @requires: PROCESS
-- @statement: sessions
SELECT t.trx_mysql_thread_id AS session_id, b.trx_mysql_thread_id AS blocked_by, p.USER AS login_name, p.HOST AS host_name,
       p.DB AS database_name, p.COMMAND AS command, p.STATE AS state, t.trx_state AS status, t.trx_started AS tx_begin_time,
       TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS tx_age_sec, t.trx_isolation_level AS isolation,
       t.trx_rows_locked AS lock_count, t.trx_tables_locked, t.trx_rows_modified,
       TIMESTAMPDIFF(SECOND, t.trx_wait_started, NOW()) * 1000 AS wait_time_ms,
       CONCAT(lk.lock_mode, ' ', lk.lock_type, ' on ', lk.lock_table, COALESCE(CONCAT(' index ', lk.lock_index), '')) AS wait_resource,
       LEFT(COALESCE(t.trx_query, p.INFO), 4000) AS sql_text
FROM information_schema.INNODB_TRX t
LEFT JOIN information_schema.INNODB_LOCK_WAITS w ON w.requesting_trx_id = t.trx_id
LEFT JOIN information_schema.INNODB_TRX b ON b.trx_id = w.blocking_trx_id
LEFT JOIN information_schema.INNODB_LOCKS lk ON lk.lock_id = w.requested_lock_id
LEFT JOIN information_schema.PROCESSLIST p ON p.ID = t.trx_mysql_thread_id
WHERE w.requesting_trx_id IS NOT NULL
   OR t.trx_id IN (SELECT blocking_trx_id FROM information_schema.INNODB_LOCK_WAITS)
-- @statement: locks
SELECT t.trx_mysql_thread_id AS session_id, lk.lock_type AS resource_type, lk.lock_mode AS request_mode,
       CASE WHEN w.requested_lock_id IS NULL THEN 'GRANT' ELSE 'WAIT' END AS request_status,
       lk.lock_table AS object_name, lk.lock_index AS index_name, lk.lock_data AS sample_resource
FROM information_schema.INNODB_LOCKS lk
JOIN information_schema.INNODB_TRX t ON t.trx_id = lk.lock_trx_id
LEFT JOIN information_schema.INNODB_LOCK_WAITS w ON w.requested_lock_id = lk.lock_id

-- @family: mysql
-- @requires: PROCESS and SELECT on performance_schema
-- @statement: sessions
SELECT t.trx_mysql_thread_id AS session_id, bt.PROCESSLIST_ID AS blocked_by, p.USER AS login_name, p.HOST AS host_name,
       p.DB AS database_name, p.COMMAND AS command, p.STATE AS state, t.trx_state AS status, t.trx_started AS tx_begin_time,
       TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS tx_age_sec, t.trx_isolation_level AS isolation,
       t.trx_rows_locked AS lock_count, t.trx_tables_locked, t.trx_rows_modified,
       TIMESTAMPDIFF(SECOND, t.trx_wait_started, NOW()) * 1000 AS wait_time_ms,
       CONCAT(rl.LOCK_MODE, ' ', rl.LOCK_TYPE, ' on ', rl.OBJECT_SCHEMA, '.', rl.OBJECT_NAME, COALESCE(CONCAT(' index ', rl.INDEX_NAME), '')) AS wait_resource,
       LEFT(COALESCE(t.trx_query, p.INFO), 4000) AS sql_text
FROM information_schema.INNODB_TRX t
LEFT JOIN performance_schema.data_lock_waits w ON w.REQUESTING_ENGINE_TRANSACTION_ID = t.trx_id
LEFT JOIN performance_schema.threads bt ON bt.THREAD_ID = w.BLOCKING_THREAD_ID
LEFT JOIN performance_schema.data_locks rl ON rl.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
LEFT JOIN information_schema.PROCESSLIST p ON p.ID = t.trx_mysql_thread_id
WHERE w.REQUESTING_ENGINE_TRANSACTION_ID IS NOT NULL
   OR t.trx_id IN (SELECT BLOCKING_ENGINE_TRANSACTION_ID FROM performance_schema.data_lock_waits)
-- @statement: locks
SELECT th.PROCESSLIST_ID AS session_id, l.LOCK_TYPE AS resource_type, l.LOCK_MODE AS request_mode, l.LOCK_STATUS AS request_status,
       CONCAT(l.OBJECT_SCHEMA, '.', l.OBJECT_NAME) AS object_name, l.INDEX_NAME AS index_name, COUNT(*) AS lock_count
FROM performance_schema.data_locks l
JOIN performance_schema.threads th ON th.THREAD_ID = l.THREAD_ID
GROUP BY th.PROCESSLIST_ID, l.LOCK_TYPE, l.LOCK_MODE, l.LOCK_STATUS, l.OBJECT_SCHEMA, l.OBJECT_NAME, l.INDEX_NAME

-- @family: oracle
-- @requires: SELECT on V$SESSION, V$TRANSACTION, V$LOCKED_OBJECT
-- @statement: sessions
SELECT s.sid AS session_id, s.serial# AS serial, s.blocking_session AS blocked_by, s.username AS login_name, s.machine AS host_name,
       s.program AS program_name, s.status, s.event AS wait_event, s.seconds_in_wait * 1000 AS wait_time_ms,
       t.start_date AS tx_begin_time, ROUND((SYSDATE - t.start_date) * 86400) AS tx_age_sec,
       o.owner || '.' || o.object_name AS wait_resource,
       (SELECT SUBSTR(q.sql_text, 1, 1000) FROM v$sql q WHERE q.sql_id = NVL(s.sql_id, s.prev_sql_id) AND ROWNUM = 1) AS sql_text
FROM v$session s
LEFT JOIN v$transaction t ON t.addr = s.taddr
LEFT JOIN all_objects o ON o.object_id = s.row_wait_obj#
WHERE s.blocking_session IS NOT NULL
   OR s.sid IN (SELECT blocking_session FROM v$session WHERE blocking_session IS NOT NULL)
-- @statement: locks
SELECT lo.session_id, o.owner || '.' || o.object_name AS object_name, o.object_type,
       DECODE(lo.locked_mode, 1, 'NULL', 2, 'ROW SHARE', 3, 'ROW EXCLUSIVE', 4, 'SHARE', 5, 'SHARE ROW EXCLUSIVE', 6, 'EXCLUSIVE') AS request_mode
FROM v$locked_object lo
JOIN all_objects o ON o.object_id = lo.object_id
