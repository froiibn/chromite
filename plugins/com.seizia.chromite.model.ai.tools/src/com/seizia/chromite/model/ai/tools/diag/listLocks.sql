-- listLocks: held and waited locks mapped to objects (current database), grouped; DiagnosticPostProcessor adds a summary
-- @family: sqlserver
-- @requires: VIEW SERVER STATE
-- @statement: locks
-- @limit: 500
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
    COUNT(*) AS lock_count
FROM sys.dm_tran_locks l
WHERE l.resource_type <> 'DATABASE'
  AND l.request_session_id <> @@SPID
  AND (${session} IS NULL OR l.request_session_id = ${session})
GROUP BY l.request_session_id, l.resource_type, l.resource_database_id, l.request_mode, l.request_status, l.resource_associated_entity_id
ORDER BY l.request_status DESC, lock_count DESC
-- @statement: escalation
SELECT TOP (20) OBJECT_SCHEMA_NAME(s.object_id) + '.' + OBJECT_NAME(s.object_id) AS object_name, i.name AS index_name,
       s.index_lock_promotion_attempt_count AS escalation_attempts, s.index_lock_promotion_count AS escalations,
       s.row_lock_wait_count, s.row_lock_wait_in_ms, s.page_lock_wait_count, s.page_lock_wait_in_ms
FROM sys.dm_db_index_operational_stats(DB_ID(), NULL, NULL, NULL) s
JOIN sys.indexes i ON i.object_id = s.object_id AND i.index_id = s.index_id
WHERE OBJECTPROPERTY(s.object_id, 'IsUserTable') = 1
  AND (s.index_lock_promotion_attempt_count > 0 OR s.row_lock_wait_count > 0 OR s.page_lock_wait_count > 0)
ORDER BY s.row_lock_wait_in_ms + s.page_lock_wait_in_ms DESC

-- @family: postgresql
-- @statement: locks
-- @limit: 500
SELECT l.pid AS session_id, l.locktype AS resource_type, l.mode AS request_mode,
       CASE WHEN l.granted THEN 'GRANT' ELSE 'WAIT' END AS request_status,
       CASE WHEN l.relation IS NOT NULL THEN l.relation::regclass::text END AS object_name,
       c.relkind::text AS object_kind, a.application_name, a.state, count(*) AS lock_count
FROM pg_locks l
LEFT JOIN pg_class c ON c.oid = l.relation
LEFT JOIN pg_stat_activity a ON a.pid = l.pid
WHERE (l.database IS NULL OR l.database = (SELECT oid FROM pg_database WHERE datname = current_database()))
  AND l.pid <> pg_backend_pid()
  AND (${session}::bigint IS NULL OR l.pid = ${session})
  AND NOT (l.locktype = 'relation' AND c.relnamespace = 'pg_catalog'::regnamespace)
GROUP BY 1, 2, 3, 4, 5, 6, 7, 8
ORDER BY 4 DESC, 9 DESC

-- @family: mariadb
-- @requires: PROCESS
-- @statement: locks
SELECT t.trx_mysql_thread_id AS session_id, lk.lock_type AS resource_type, lk.lock_mode AS request_mode,
       CASE WHEN w.requested_lock_id IS NULL THEN 'GRANT' ELSE 'WAIT' END AS request_status,
       lk.lock_table AS object_name, lk.lock_index AS index_name, lk.lock_data AS sample_resource
FROM information_schema.INNODB_LOCKS lk
JOIN information_schema.INNODB_TRX t ON t.trx_id = lk.lock_trx_id
LEFT JOIN information_schema.INNODB_LOCK_WAITS w ON w.requested_lock_id = lk.lock_id
WHERE ${session} IS NULL OR t.trx_mysql_thread_id = ${session}
-- @statement: transactions
SELECT trx_mysql_thread_id AS session_id, trx_state, trx_started, trx_rows_locked, trx_tables_locked, trx_rows_modified,
       trx_isolation_level
FROM information_schema.INNODB_TRX
WHERE ${session} IS NULL OR trx_mysql_thread_id = ${session}

-- @family: mysql
-- @requires: SELECT on performance_schema
-- @statement: locks
-- @limit: 500
SELECT th.PROCESSLIST_ID AS session_id, l.LOCK_TYPE AS resource_type, l.LOCK_MODE AS request_mode, l.LOCK_STATUS AS request_status,
       CONCAT(l.OBJECT_SCHEMA, '.', l.OBJECT_NAME) AS object_name, l.INDEX_NAME AS index_name, COUNT(*) AS lock_count
FROM performance_schema.data_locks l
JOIN performance_schema.threads th ON th.THREAD_ID = l.THREAD_ID
WHERE ${session} IS NULL OR th.PROCESSLIST_ID = ${session}
GROUP BY th.PROCESSLIST_ID, l.LOCK_TYPE, l.LOCK_MODE, l.LOCK_STATUS, l.OBJECT_SCHEMA, l.OBJECT_NAME, l.INDEX_NAME
-- @statement: metadataLocks
SELECT th.PROCESSLIST_ID AS session_id, m.OBJECT_TYPE, m.OBJECT_SCHEMA, m.OBJECT_NAME, m.LOCK_TYPE, m.LOCK_DURATION, m.LOCK_STATUS
FROM performance_schema.metadata_locks m
JOIN performance_schema.threads th ON th.THREAD_ID = m.OWNER_THREAD_ID
WHERE m.OBJECT_SCHEMA NOT IN ('performance_schema', 'mysql', 'sys')
  AND (${session} IS NULL OR th.PROCESSLIST_ID = ${session})

-- @family: oracle
-- @requires: SELECT on V$LOCK, V$LOCKED_OBJECT
-- @statement: locks
SELECT lo.session_id, o.owner || '.' || o.object_name AS object_name, o.object_type,
       DECODE(lo.locked_mode, 1, 'NULL', 2, 'ROW SHARE', 3, 'ROW EXCLUSIVE', 4, 'SHARE', 5, 'SHARE ROW EXCLUSIVE', 6, 'EXCLUSIVE') AS request_mode,
       lo.oracle_username AS login_name, lo.os_user_name
FROM v$locked_object lo
JOIN all_objects o ON o.object_id = lo.object_id
WHERE ${session} IS NULL OR lo.session_id = ${session}
