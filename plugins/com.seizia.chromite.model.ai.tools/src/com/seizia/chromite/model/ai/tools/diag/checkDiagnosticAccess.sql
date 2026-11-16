-- checkDiagnosticAccess (probePermissions before 26.1.0): what this login may read (PermissionProbe maps the facts to tools)
-- @family: sqlserver
SELECT
    IS_SRVROLEMEMBER('sysadmin') AS is_sysadmin,
    IS_SRVROLEMEMBER('securityadmin') AS is_securityadmin,
    IS_SRVROLEMEMBER('processadmin') AS is_processadmin,
    HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW SERVER STATE') AS view_server_state,
    HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW ANY DEFINITION') AS view_any_definition,
    HAS_PERMS_BY_NAME(NULL, NULL, 'ALTER ANY CONNECTION') AS alter_any_connection,
    HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DATABASE STATE') AS view_database_state,
    CASE WHEN HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DEFINITION') = 1
           OR HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW ANY DEFINITION') = 1 THEN 1 ELSE 0 END AS view_definition,
    HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'SHOWPLAN') AS showplan,
    HAS_DBACCESS('msdb') AS msdb_access,
    ISNULL(HAS_PERMS_BY_NAME('msdb.dbo.backupset', 'OBJECT', 'SELECT'), 0) AS msdb_backupset,
    ISNULL(HAS_PERMS_BY_NAME('msdb.dbo.sysjobhistory', 'OBJECT', 'SELECT'), 0) AS msdb_jobs,
    ISNULL(HAS_PERMS_BY_NAME('msdb.dbo.sp_help_job', 'OBJECT', 'EXECUTE'), 0) AS msdb_agent,
    CAST(SERVERPROPERTY('EngineEdition') AS int) AS engine_edition,
    (SELECT CAST(actual_state_desc AS nvarchar(60)) FROM sys.database_query_store_options) AS query_store_state

-- @family: postgresql
SELECT
    current_user AS login_name,
    (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS is_superuser,
    pg_has_role(current_user, 'pg_monitor', 'MEMBER') AS pg_monitor,
    pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER') AS pg_read_all_stats,
    pg_has_role(current_user, 'pg_read_all_settings', 'MEMBER') AS pg_read_all_settings,
    pg_has_role(current_user, 'pg_signal_backend', 'MEMBER') AS pg_signal_backend,
    pg_has_role(current_user, 'pg_read_server_files', 'MEMBER') AS pg_read_server_files,
    EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements') AS ext_pg_stat_statements,
    EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgstattuple') AS ext_pgstattuple,
    EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'hypopg') AS ext_hypopg,
    EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') AS ext_pg_cron,
    current_setting('track_functions') AS track_functions,
    current_setting('track_io_timing') AS track_io_timing,
    current_setting('logging_collector') AS logging_collector

-- @family: mysql
-- @statement: grants
SHOW GRANTS
-- @statement: facts
SELECT CURRENT_USER() AS login_name, @@performance_schema AS performance_schema, @@read_only AS server_read_only

-- @family: oracle
-- @statement: privileges
SELECT privilege FROM session_privs ORDER BY privilege
-- @statement: roles
SELECT role FROM session_roles ORDER BY role
-- @statement: vdollar
SELECT COUNT(*) AS v_session_rows FROM v$session WHERE ROWNUM = 1
-- @statement: alert_log
SELECT (SELECT 1 FROM v$diag_alert_ext WHERE ROWNUM = 1) AS alert_log_first_row, 1 AS alert_log FROM dual
