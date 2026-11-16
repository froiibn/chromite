-- getServerInfo: product, version, edition, uptime (docs/chromite/ai-agent.md)
-- @family: sqlserver
-- @requires: VIEW SERVER STATE (host statement only)
-- @statement: server
SELECT
    CAST(SERVERPROPERTY('ProductVersion') AS nvarchar(128)) AS product_version,
    CAST(SERVERPROPERTY('ProductLevel') AS nvarchar(128)) AS product_level,
    CAST(SERVERPROPERTY('ProductUpdateLevel') AS nvarchar(128)) AS update_level,
    CAST(SERVERPROPERTY('Edition') AS nvarchar(128)) AS edition,
    CAST(SERVERPROPERTY('EngineEdition') AS int) AS engine_edition,
    CAST(SERVERPROPERTY('ServerName') AS nvarchar(256)) AS server_name,
    CAST(SERVERPROPERTY('Collation') AS nvarchar(128)) AS server_collation,
    CAST(SERVERPROPERTY('IsHadrEnabled') AS int) AS is_hadr_enabled,
    CAST(SERVERPROPERTY('IsClustered') AS int) AS is_clustered,
    @@VERSION AS version_string,
    DB_NAME() AS current_database,
    SUSER_SNAME() AS login_name,
    (SELECT compatibility_level FROM sys.databases WHERE database_id = DB_ID()) AS db_compatibility_level
-- @statement: host
SELECT
    sqlserver_start_time AS start_time,
    DATEDIFF(MINUTE, sqlserver_start_time, SYSDATETIME()) AS uptime_minutes,
    cpu_count,
    physical_memory_kb / 1024 AS physical_memory_mb,
    committed_kb / 1024 AS committed_memory_mb,
    committed_target_kb / 1024 AS target_memory_mb,
    virtual_machine_type_desc
FROM sys.dm_os_sys_info

-- @family: postgresql
-- @statement: server
SELECT
    version() AS version_string,
    current_setting('server_version') AS server_version,
    current_setting('server_version_num')::int AS version_num,
    current_database() AS current_database,
    current_user AS login_name,
    pg_postmaster_start_time() AS start_time,
    (extract(epoch FROM now() - pg_postmaster_start_time()) / 60)::bigint AS uptime_minutes,
    pg_is_in_recovery() AS is_replica,
    current_setting('max_connections')::int AS max_connections,
    current_setting('shared_buffers') AS shared_buffers,
    (SELECT count(*) FROM pg_database WHERE NOT datistemplate) AS database_count,
    (SELECT string_agg(extname || ' ' || extversion, ', ' ORDER BY extname) FROM pg_extension) AS extensions

-- @family: mysql
-- @statement: server
SELECT
    VERSION() AS version_string,
    @@version_comment AS edition,
    @@hostname AS host_name,
    @@port AS port,
    DATABASE() AS current_database,
    CURRENT_USER() AS login_name,
    @@read_only AS read_only,
    @@max_connections AS max_connections,
    @@innodb_buffer_pool_size / 1048576 AS innodb_buffer_pool_mb,
    @@performance_schema AS performance_schema
-- @statement: uptime
SHOW GLOBAL STATUS WHERE Variable_name IN ('Uptime', 'Threads_connected', 'Threads_running', 'Questions')

-- @family: oracle
-- @requires: SELECT on V$INSTANCE (instance statement only)
-- @statement: product
SELECT product, version, status FROM product_component_version
-- @statement: session
SELECT
    SYS_CONTEXT('USERENV', 'DB_NAME') AS database_name,
    SYS_CONTEXT('USERENV', 'CON_NAME') AS container_name,
    SYS_CONTEXT('USERENV', 'SESSION_USER') AS login_name,
    SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS current_schema,
    SYS_CONTEXT('USERENV', 'SERVER_HOST') AS server_host
FROM dual
-- @statement: instance
SELECT instance_name, host_name, version_full, startup_time, ROUND((SYSDATE - startup_time) * 1440) AS uptime_minutes,
       status, database_status, edition
FROM v$instance
