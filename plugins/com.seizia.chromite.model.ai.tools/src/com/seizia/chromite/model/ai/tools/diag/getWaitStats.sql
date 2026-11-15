-- getWaitStats: cumulative waits since start (benign waits excluded); PostgreSQL has only the current snapshot
-- @family: sqlserver
-- @limit: 20
-- @requires: VIEW SERVER STATE
SELECT TOP (${limit})
    wait_type,
    waiting_tasks_count,
    wait_time_ms,
    signal_wait_time_ms,
    wait_time_ms - signal_wait_time_ms AS resource_wait_ms,
    CAST(100.0 * wait_time_ms / NULLIF(SUM(wait_time_ms) OVER (), 0) AS decimal(5, 2)) AS percent_of_waits,
    CAST(wait_time_ms * 1.0 / NULLIF(waiting_tasks_count, 0) AS decimal(18, 2)) AS avg_wait_ms
FROM sys.dm_os_wait_stats
WHERE waiting_tasks_count > 0
  AND wait_type NOT IN (
    'BROKER_EVENTHANDLER', 'BROKER_RECEIVE_WAITFOR', 'BROKER_TASK_STOP', 'BROKER_TO_FLUSH', 'BROKER_TRANSMITTER',
    'CHECKPOINT_QUEUE', 'CHKPT', 'CLR_AUTO_EVENT', 'CLR_MANUAL_EVENT', 'CLR_SEMAPHORE', 'DBMIRROR_DBM_EVENT',
    'DBMIRROR_EVENTS_QUEUE', 'DBMIRROR_WORKER_QUEUE', 'DBMIRRORING_CMD', 'DIRTY_PAGE_POLL', 'DISPATCHER_QUEUE_SEMAPHORE',
    'EXECSYNC', 'FSAGENT', 'FT_IFTS_SCHEDULER_IDLE_WAIT', 'FT_IFTSHC_MUTEX', 'HADR_CLUSAPI_CALL',
    'HADR_FILESTREAM_IOMGR_IOCOMPLETION', 'HADR_LOGCAPTURE_WAIT', 'HADR_NOTIFICATION_DEQUEUE', 'HADR_TIMER_TASK',
    'HADR_WORK_QUEUE', 'KSOURCE_WAKEUP', 'LAZYWRITER_SLEEP', 'LOGMGR_QUEUE', 'MEMORY_ALLOCATION_EXT', 'ONDEMAND_TASK_QUEUE',
    'PARALLEL_REDO_DRAIN_WORKER', 'PARALLEL_REDO_LOG_CACHE', 'PARALLEL_REDO_TRAN_LIST', 'PARALLEL_REDO_WORKER_SYNC',
    'PARALLEL_REDO_WORKER_WAIT_WORK', 'PREEMPTIVE_XE_GETTARGETSTATE', 'PWAIT_ALL_COMPONENTS_INITIALIZED',
    'PWAIT_DIRECTLOGCONSUMER_GETNEXT', 'PWAIT_EXTENSIBILITY_CLEANUP_TASK', 'QDS_PERSIST_TASK_MAIN_LOOP_SLEEP', 'QDS_ASYNC_QUEUE',
    'QDS_CLEANUP_STALE_QUERIES_TASK_MAIN_LOOP_SLEEP', 'QDS_SHUTDOWN_QUEUE', 'REDO_THREAD_PENDING_WORK', 'REQUEST_FOR_DEADLOCK_SEARCH',
    'RESOURCE_QUEUE', 'SERVER_IDLE_CHECK', 'SLEEP_BPOOL_FLUSH', 'SLEEP_DBSTARTUP', 'SLEEP_DCOMSTARTUP', 'SLEEP_MASTERDBREADY',
    'SLEEP_MASTERMDREADY', 'SLEEP_MASTERUPGRADED', 'SLEEP_MSDBSTARTUP', 'SLEEP_SYSTEMTASK', 'SLEEP_TASK', 'SLEEP_TEMPDBSTARTUP',
    'SNI_HTTP_ACCEPT', 'SOS_WORK_DISPATCHER', 'SP_SERVER_DIAGNOSTICS_SLEEP', 'SQLTRACE_BUFFER_FLUSH',
    'SQLTRACE_INCREMENTAL_FLUSH_SLEEP', 'SQLTRACE_WAIT_ENTRIES', 'VDI_CLIENT_OTHER', 'WAIT_FOR_RESULTS', 'WAITFOR',
    'WAITFOR_TASKSHUTDOWN', 'WAIT_XTP_RECOVERY', 'WAIT_XTP_HOST_WAIT', 'WAIT_XTP_OFFLINE_CKPT_NEW_LOG', 'WAIT_XTP_CKPT_CLOSE',
    'XE_DISPATCHER_JOIN', 'XE_DISPATCHER_WAIT', 'XE_TIMER_EVENT', 'XE_LIVE_TARGET_TVF')
  AND wait_type NOT LIKE 'PREEMPTIVE_OS_%'
ORDER BY wait_time_ms DESC

-- @family: postgresql
-- @limit: 20
-- @note: PostgreSQL keeps no cumulative wait statistics; this is what client sessions wait on right now. Call again to sample.
SELECT wait_event_type, wait_event, state, count(*) AS sessions
FROM pg_stat_activity
WHERE backend_type = 'client backend' AND pid <> pg_backend_pid() AND wait_event IS NOT NULL
GROUP BY 1, 2, 3
ORDER BY sessions DESC
LIMIT ${limit}

-- @family: mysql
-- @limit: 20
-- @requires: performance_schema enabled
SELECT EVENT_NAME AS wait_type, COUNT_STAR AS waiting_tasks_count, ROUND(SUM_TIMER_WAIT / 1000000000, 1) AS wait_time_ms,
       ROUND(AVG_TIMER_WAIT / 1000000000, 3) AS avg_wait_ms
FROM performance_schema.events_waits_summary_global_by_event_name
WHERE COUNT_STAR > 0 AND EVENT_NAME <> 'idle'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT ${limit}

-- @family: oracle
-- @limit: 20
-- @requires: SELECT on V$SYSTEM_EVENT
SELECT event AS wait_type, wait_class, total_waits AS waiting_tasks_count, ROUND(time_waited_micro / 1000) AS wait_time_ms,
       ROUND(average_wait * 10, 2) AS avg_wait_ms
FROM v$system_event
WHERE wait_class <> 'Idle'
ORDER BY time_waited_micro DESC
FETCH FIRST ${limit} ROWS ONLY
