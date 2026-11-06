-- getRoutineSource: routine/trigger/view source (numbered by DiagnosticPostProcessor) and parameters
-- @family: sqlserver
-- @requires: VIEW DEFINITION on the object
-- @statement: routine
-- @maxCellChars: 1000000
SELECT
    SCHEMA_NAME(o.schema_id) AS schema_name,
    o.name,
    o.type_desc,
    o.create_date,
    o.modify_date,
    OBJECT_SCHEMA_NAME(t.parent_id) + '.' + OBJECT_NAME(t.parent_id) AS trigger_table,
    t.is_disabled AS trigger_disabled,
    m.uses_ansi_nulls,
    m.uses_quoted_identifier,
    m.is_schema_bound,
    m.definition
FROM sys.objects o
JOIN sys.sql_modules m ON m.object_id = o.object_id
LEFT JOIN sys.triggers t ON t.object_id = o.object_id
WHERE o.object_id = OBJECT_ID(${name})
-- @statement: parameters
SELECT p.parameter_id, p.name, TYPE_NAME(p.user_type_id) AS type_name, p.max_length, p.precision, p.scale,
       p.is_output, p.has_default_value, p.is_readonly
FROM sys.parameters p
WHERE p.object_id = OBJECT_ID(${name})
ORDER BY p.parameter_id

-- @family: postgresql
-- @statement: routine
-- @maxCellChars: 1000000
SELECT
    n.nspname AS schema_name,
    p.proname AS name,
    CASE p.prokind WHEN 'f' THEN 'FUNCTION' WHEN 'p' THEN 'PROCEDURE' WHEN 'a' THEN 'AGGREGATE' WHEN 'w' THEN 'WINDOW' END AS type_desc,
    l.lanname AS language,
    pg_get_function_identity_arguments(p.oid) AS arguments,
    pg_get_function_result(p.oid) AS result_type,
    CASE p.provolatile WHEN 'i' THEN 'IMMUTABLE' WHEN 's' THEN 'STABLE' ELSE 'VOLATILE' END AS volatility,
    p.prosecdef AS security_definer,
    CASE WHEN p.prokind IN ('f', 'p', 'w') THEN pg_get_functiondef(p.oid) END AS definition
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
JOIN pg_language l ON l.oid = p.prolang
WHERE n.nspname || '.' || p.proname = ${name}
   OR (p.proname = ${name} AND n.nspname = ANY (current_schemas(false)))
-- @statement: trigger
-- @maxCellChars: 1000000
SELECT
    n.nspname AS schema_name,
    t.tgname AS name,
    'TRIGGER' AS type_desc,
    c.relname AS trigger_table,
    t.tgenabled = 'D' AS trigger_disabled,
    pg_get_triggerdef(t.oid, true) AS trigger_definition,
    fn.nspname || '.' || p.proname AS trigger_function,
    pg_get_functiondef(p.oid) AS definition
FROM pg_trigger t
JOIN pg_class c ON c.oid = t.tgrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_proc p ON p.oid = t.tgfoid
JOIN pg_namespace fn ON fn.oid = p.pronamespace
WHERE NOT t.tgisinternal
  AND (t.tgname = ${name} OR n.nspname || '.' || t.tgname = ${name})
-- @statement: view
-- @maxCellChars: 1000000
SELECT
    n.nspname AS schema_name,
    c.relname AS name,
    CASE c.relkind WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'VIEW' END AS type_desc,
    pg_get_viewdef(c.oid, true) AS definition
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('v', 'm')
  AND (n.nspname || '.' || c.relname = ${name} OR (c.relname = ${name} AND n.nspname = ANY (current_schemas(false))))

-- @family: mysql
-- @statement: routine
-- @maxCellChars: 1000000
SELECT ROUTINE_SCHEMA AS schema_name, ROUTINE_NAME AS name, ROUTINE_TYPE AS type_desc, DTD_IDENTIFIER AS result_type,
       SQL_DATA_ACCESS, SECURITY_TYPE, IS_DETERMINISTIC, CREATED, LAST_ALTERED, DEFINER, ROUTINE_DEFINITION AS definition
FROM information_schema.ROUTINES
WHERE ROUTINE_NAME = SUBSTRING_INDEX(${name}, '.', -1)
  AND ROUTINE_SCHEMA = IF(LOCATE('.', ${name}) > 0, SUBSTRING_INDEX(${name}, '.', 1), DATABASE())
-- @statement: parameters
SELECT SPECIFIC_NAME AS routine, ORDINAL_POSITION, PARAMETER_MODE, PARAMETER_NAME, DTD_IDENTIFIER AS type_name
FROM information_schema.PARAMETERS
WHERE SPECIFIC_NAME = SUBSTRING_INDEX(${name}, '.', -1)
  AND SPECIFIC_SCHEMA = IF(LOCATE('.', ${name}) > 0, SUBSTRING_INDEX(${name}, '.', 1), DATABASE())
ORDER BY ORDINAL_POSITION
-- @statement: trigger
-- @maxCellChars: 1000000
SELECT TRIGGER_SCHEMA AS schema_name, TRIGGER_NAME AS name, 'TRIGGER' AS type_desc,
       CONCAT(EVENT_OBJECT_SCHEMA, '.', EVENT_OBJECT_TABLE) AS trigger_table, ACTION_TIMING, EVENT_MANIPULATION,
       ACTION_ORIENTATION, DEFINER, ACTION_STATEMENT AS definition
FROM information_schema.TRIGGERS
WHERE TRIGGER_NAME = SUBSTRING_INDEX(${name}, '.', -1)
  AND TRIGGER_SCHEMA = IF(LOCATE('.', ${name}) > 0, SUBSTRING_INDEX(${name}, '.', 1), DATABASE())
-- @statement: view
-- @maxCellChars: 1000000
SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME AS name, 'VIEW' AS type_desc, VIEW_DEFINITION AS definition
FROM information_schema.VIEWS
WHERE TABLE_NAME = SUBSTRING_INDEX(${name}, '.', -1)
  AND TABLE_SCHEMA = IF(LOCATE('.', ${name}) > 0, SUBSTRING_INDEX(${name}, '.', 1), DATABASE())

-- @family: oracle
-- @statement: routine
-- @limit: 20000
SELECT owner AS schema_name, name, type AS type_desc, line, text
FROM all_source
WHERE name = UPPER(SUBSTR(${name}, INSTR(${name}, '.') + 1))
  AND owner = CASE WHEN INSTR(${name}, '.') > 0 THEN UPPER(SUBSTR(${name}, 1, INSTR(${name}, '.') - 1))
                   ELSE SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') END
ORDER BY type, line
-- @statement: parameters
SELECT object_name AS routine, package_name, position, argument_name, in_out, data_type
FROM all_arguments
WHERE object_name = UPPER(SUBSTR(${name}, INSTR(${name}, '.') + 1))
  AND owner = CASE WHEN INSTR(${name}, '.') > 0 THEN UPPER(SUBSTR(${name}, 1, INSTR(${name}, '.') - 1))
                   ELSE SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') END
ORDER BY object_name, overload, position
