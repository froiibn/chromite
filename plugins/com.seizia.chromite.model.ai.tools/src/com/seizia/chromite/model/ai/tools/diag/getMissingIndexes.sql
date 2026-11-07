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

-- @family: oracle
-- @requires: SELECT on V$SQL and V$SQL_PLAN (SELECT_CATALOG_ROLE or SELECT ANY DICTIONARY)
-- @note: Oracle's own index advice (SQL Access Advisor, SQL Tuning Advisor) needs the Tuning Pack and is not used. These are full scans of tables over 10,000 rows in cached plans, with the filter predicates an index could serve; check them with explainQuery and the column statistics before creating anything.
SELECT p.object_owner || '.' || p.object_name AS table_name, t.num_rows AS row_estimate, p.operation || ' ' || p.options AS operation,
       p.sql_id, s.executions AS execution_count, s.buffer_gets AS total_logical_reads,
       ROUND(s.buffer_gets / NULLIF(s.executions, 0)) AS avg_logical_reads, ROUND(s.elapsed_time / 1000) AS total_duration_ms,
       SUBSTR(p.filter_predicates, 1, 1000) AS filter_predicates, s.parsing_schema_name, SUBSTR(s.sql_text, 1, 1000) AS sql_text
FROM v$sql_plan p
JOIN v$sql s ON s.sql_id = p.sql_id AND s.child_number = p.child_number
JOIN all_tables t ON t.owner = p.object_owner AND t.table_name = p.object_name
WHERE p.operation = 'TABLE ACCESS' AND p.options LIKE '%FULL'
  AND p.filter_predicates IS NOT NULL
  AND t.num_rows > 10000
  AND p.object_owner NOT IN ('SYS', 'SYSTEM')
ORDER BY s.buffer_gets DESC
FETCH FIRST ${limit} ROWS ONLY
