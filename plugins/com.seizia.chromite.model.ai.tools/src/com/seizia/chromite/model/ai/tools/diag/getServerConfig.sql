-- getServerConfig: key settings with sanity findings
-- @family: sqlserver
-- @statement: settings
SELECT name, CAST(value AS bigint) AS configured, CAST(value_in_use AS bigint) AS in_use, is_dynamic, is_advanced,
       CASE
           WHEN name = 'max degree of parallelism' AND CAST(value_in_use AS int) = 0
               THEN 'MAXDOP 0 lets one query use every core; usually set to the cores of one NUMA node (max 8)'
           WHEN name = 'cost threshold for parallelism' AND CAST(value_in_use AS int) = 5
               THEN 'Default 5 is low for modern hardware; 30-50 is a common start'
           WHEN name = 'max server memory (MB)' AND CAST(value_in_use AS bigint) = 2147483647
               THEN 'Unlimited: leave memory for the OS and other processes'
           WHEN name = 'optimize for ad hoc workloads' AND CAST(value_in_use AS int) = 0
               THEN 'Off: single-use ad hoc plans fill the plan cache'
           WHEN name = 'xp_cmdshell' AND CAST(value_in_use AS int) = 1 THEN 'xp_cmdshell is enabled (security risk)'
           WHEN name = 'backup checksum default' AND CAST(value_in_use AS int) = 0 THEN 'Backups are written without checksums'
           WHEN CAST(value AS bigint) <> CAST(value_in_use AS bigint) THEN 'Configured value is not active yet (RECONFIGURE or restart needed)'
       END AS finding
FROM sys.configurations
WHERE name IN ('max degree of parallelism', 'cost threshold for parallelism', 'max server memory (MB)', 'min server memory (MB)',
               'optimize for ad hoc workloads', 'backup compression default', 'backup checksum default', 'remote admin connections',
               'xp_cmdshell', 'clr enabled', 'fill factor (%)', 'max worker threads', 'blocked process threshold (s)')
   OR CAST(value AS bigint) <> CAST(value_in_use AS bigint)
ORDER BY name
-- @statement: databases
SELECT name AS database_name, compatibility_level, recovery_model_desc, page_verify_option_desc, is_auto_create_stats_on,
       is_auto_update_stats_on, is_auto_update_stats_async_on, is_auto_close_on, is_auto_shrink_on, is_read_committed_snapshot_on,
       snapshot_isolation_state_desc, is_query_store_on, log_reuse_wait_desc,
       CASE WHEN is_auto_shrink_on = 1 THEN 'AUTO_SHRINK on: causes fragmentation and CPU load'
            WHEN is_auto_close_on = 1 THEN 'AUTO_CLOSE on: slow first connections, plan cache flushes'
            WHEN page_verify_option_desc <> 'CHECKSUM' THEN 'PAGE_VERIFY is not CHECKSUM'
            WHEN is_auto_update_stats_on = 0 THEN 'AUTO_UPDATE_STATISTICS off: stale statistics' END AS finding
FROM sys.databases
WHERE database_id > 4 OR name = DB_NAME()
ORDER BY name

-- @family: postgresql
SELECT name, setting, unit, source, boot_val AS default_value, pending_restart, short_desc,
       -- Nested CASE: the numeric casts run only for the setting they belong to
       CASE name
           WHEN 'shared_buffers' THEN CASE WHEN setting::bigint * 8 <= 131072 THEN 'Default 128MB; commonly 25% of RAM' END
           WHEN 'work_mem' THEN CASE WHEN setting::bigint <= 4096 THEN 'Default 4MB: sorts and hashes spill to disk early (watch per-connection use)' END
           WHEN 'random_page_cost' THEN CASE WHEN setting::numeric >= 4 THEN 'Default 4 assumes spinning disks; 1.1-2 for SSD' END
           WHEN 'autovacuum' THEN CASE WHEN setting = 'off' THEN 'Autovacuum is off: bloat and transaction wraparound risk' END
           WHEN 'track_io_timing' THEN CASE WHEN setting = 'off' THEN 'Off: plans and pg_stat_statements show no I/O timing' END
           WHEN 'log_lock_waits' THEN CASE WHEN setting = 'off' THEN 'Off: lock waits longer than deadlock_timeout are not logged' END
           WHEN 'idle_in_transaction_session_timeout' THEN CASE WHEN setting = '0' THEN 'No timeout: idle-in-transaction sessions hold locks forever' END
       END AS finding
FROM pg_settings
WHERE name IN ('shared_buffers', 'work_mem', 'maintenance_work_mem', 'effective_cache_size', 'random_page_cost', 'max_connections',
               'autovacuum', 'track_io_timing', 'track_functions', 'log_lock_waits', 'log_min_duration_statement',
               'idle_in_transaction_session_timeout', 'statement_timeout', 'lock_timeout', 'deadlock_timeout', 'max_wal_size',
               'checkpoint_timeout', 'default_statistics_target', 'jit', 'shared_preload_libraries', 'wal_level', 'synchronous_commit')
   OR pending_restart
ORDER BY name

-- @family: mysql
SHOW GLOBAL VARIABLES WHERE Variable_name IN ('innodb_buffer_pool_size', 'innodb_log_file_size', 'innodb_redo_log_capacity',
    'innodb_flush_log_at_trx_commit', 'sync_binlog', 'max_connections', 'tmp_table_size', 'max_heap_table_size', 'long_query_time',
    'slow_query_log', 'innodb_lock_wait_timeout', 'transaction_isolation', 'tx_isolation', 'innodb_deadlock_detect',
    'innodb_print_all_deadlocks', 'performance_schema', 'table_open_cache', 'thread_cache_size', 'sql_mode', 'innodb_file_per_table')

-- @family: oracle
-- @requires: SELECT on V$PARAMETER
SELECT name, value, isdefault, ismodified, description
FROM v$parameter
WHERE isdefault = 'FALSE' OR name IN ('sga_target', 'pga_aggregate_target', 'memory_target', 'optimizer_mode', 'cursor_sharing',
                                     'open_cursors', 'processes', 'sessions', 'undo_retention')
ORDER BY name
