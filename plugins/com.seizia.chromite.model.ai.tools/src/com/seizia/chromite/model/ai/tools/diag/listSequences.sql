-- listSequences
-- @family: sqlserver
SELECT SCHEMA_NAME(s.schema_id) AS schema_name, s.name, TYPE_NAME(s.user_type_id) AS type_name,
       s.current_value, s.start_value, s.increment, s.minimum_value, s.maximum_value, s.is_cycling, s.cache_size
FROM sys.sequences s
WHERE ${schema} IS NULL OR SCHEMA_NAME(s.schema_id) = ${schema}
ORDER BY 1, 2

-- @family: postgresql
SELECT schemaname AS schema_name, sequencename AS name, data_type::text AS type_name, last_value, start_value,
       increment_by, min_value, max_value, cycle, cache_size
FROM pg_sequences
WHERE (${schema}::text IS NULL OR schemaname = ${schema})
  AND schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY 1, 2

-- @family: mariadb
-- @note: MariaDB sequences are tables of type SEQUENCE; read the current value with SELECT NEXTVAL is a write, so only metadata is listed.
SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME AS name, CREATE_TIME
FROM information_schema.TABLES
WHERE TABLE_TYPE = 'SEQUENCE'
  AND TABLE_SCHEMA = COALESCE(${schema}, DATABASE())
ORDER BY 1, 2

-- @family: oracle
SELECT sequence_owner AS schema_name, sequence_name AS name, last_number, min_value, max_value, increment_by,
       cycle_flag, cache_size
FROM all_sequences
WHERE sequence_owner = COALESCE(UPPER(${schema}), SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
ORDER BY 1, 2
