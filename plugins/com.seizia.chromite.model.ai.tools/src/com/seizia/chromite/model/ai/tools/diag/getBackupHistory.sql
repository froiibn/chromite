-- getBackupHistory: last backup per database and recent backups
-- @family: sqlserver
-- @requires: SELECT on msdb.dbo.backupset
-- @statement: databases
SELECT d.name AS database_name, d.recovery_model_desc,
       MAX(CASE WHEN b.type = 'D' THEN b.backup_finish_date END) AS last_full,
       MAX(CASE WHEN b.type = 'I' THEN b.backup_finish_date END) AS last_differential,
       MAX(CASE WHEN b.type = 'L' THEN b.backup_finish_date END) AS last_log,
       DATEDIFF(HOUR, MAX(CASE WHEN b.type = 'D' THEN b.backup_finish_date END), GETDATE()) AS full_age_hours,
       DATEDIFF(MINUTE, MAX(CASE WHEN b.type = 'L' THEN b.backup_finish_date END), GETDATE()) AS log_age_minutes,
       CASE WHEN MAX(CASE WHEN b.type = 'D' THEN b.backup_finish_date END) IS NULL THEN 'NEVER BACKED UP'
            WHEN d.recovery_model_desc <> 'SIMPLE' AND MAX(CASE WHEN b.type = 'L' THEN b.backup_finish_date END) IS NULL
                 THEN 'FULL/BULK_LOGGED recovery without log backups: the log grows' END AS warning
FROM sys.databases d
LEFT JOIN msdb.dbo.backupset b ON b.database_name = d.name
WHERE d.name <> 'tempdb'
GROUP BY d.name, d.recovery_model_desc
ORDER BY d.name
-- @statement: recent
SELECT TOP (${limit}) b.database_name, b.type, b.backup_start_date, b.backup_finish_date,
       DATEDIFF(SECOND, b.backup_start_date, b.backup_finish_date) AS duration_sec,
       CAST(b.backup_size / 1048576.0 AS decimal(18, 1)) AS size_mb,
       CAST(b.compressed_backup_size / 1048576.0 AS decimal(18, 1)) AS compressed_mb,
       b.is_copy_only, b.has_backup_checksums, mf.physical_device_name
FROM msdb.dbo.backupset b
LEFT JOIN msdb.dbo.backupmediafamily mf ON mf.media_set_id = b.media_set_id
ORDER BY b.backup_finish_date DESC

-- @family: postgresql
-- @note: PostgreSQL records no base backups in the catalog; only WAL archiving is visible. Check the backup tool (pg_basebackup, pgBackRest, Barman) for backup history.
SELECT archived_count, last_archived_wal, last_archived_time, failed_count, last_failed_wal, last_failed_time, stats_reset,
       current_setting('archive_mode') AS archive_mode
FROM pg_stat_archiver

-- @family: oracle
-- @requires: SELECT on V$RMAN_BACKUP_JOB_DETAILS
SELECT session_key, input_type, status, start_time, end_time, ROUND(elapsed_seconds) AS duration_sec,
       ROUND(output_bytes / 1048576, 1) AS size_mb
FROM v$rman_backup_job_details
ORDER BY start_time DESC
FETCH FIRST ${limit} ROWS ONLY
