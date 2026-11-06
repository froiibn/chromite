-- getMissingIndexes: optimizer suggestions (SQL Server) or scan-heavy tables (PostgreSQL)
-- @family: sqlserver
-- @requires: VIEW SERVER STATE
-- @note: Suggestions ignore existing similar indexes and column order subtleties; check them against getIndexUsage before creating anything.
SELECT TOP (${limit})
    d.statement AS table_name,
    d.equality_columns,
    d.inequality_columns,
    d.included_columns,
    gs.user_seeks,
    gs.user_scans,
    CAST(gs.avg_total_user_cost AS decimal(18, 2)) AS avg_query_cost,
    gs.avg_user_impact AS avg_impact_pct,
    CAST(gs.avg_total_user_cost * gs.avg_user_impact * (gs.user_seeks + gs.user_scans) AS decimal(18, 0)) AS improvement_measure,
    gs.last_user_seek,
    'CREATE INDEX [IX_' + OBJECT_NAME(d.object_id, d.database_id) + '_' +
        REPLACE(REPLACE(REPLACE(ISNULL(d.equality_columns, '') + ISNULL('_' + d.inequality_columns, ''), '[', ''), ']', ''), ', ', '_') +
        '] ON ' + d.statement + ' (' + ISNULL(d.equality_columns, '') +
        CASE WHEN d.equality_columns IS NOT NULL AND d.inequality_columns IS NOT NULL THEN ', ' ELSE '' END +
        ISNULL(d.inequality_columns, '') + ')' + ISNULL(' INCLUDE (' + d.included_columns + ')', '') AS suggested_ddl
FROM sys.dm_db_missing_index_details d
JOIN sys.dm_db_missing_index_groups g ON g.index_handle = d.index_handle
JOIN sys.dm_db_missing_index_group_stats gs ON gs.group_handle = g.index_group_handle
WHERE d.database_id = DB_ID()
ORDER BY improvement_measure DESC

-- @family: postgresql
-- @note: PostgreSQL has no missing-index view; these are large tables read mostly by sequential scans. Confirm with explainQuery on the top queries.
SELECT schemaname || '.' || relname AS table_name, seq_scan, seq_tup_read, idx_scan, n_live_tup AS row_estimate,
       seq_tup_read / nullif(seq_scan, 0) AS avg_rows_per_seq_scan, pg_relation_size(relid) / 1048576 AS table_mb
FROM pg_stat_user_tables
WHERE seq_scan > 0 AND n_live_tup > 10000
ORDER BY seq_tup_read DESC
LIMIT ${limit}
