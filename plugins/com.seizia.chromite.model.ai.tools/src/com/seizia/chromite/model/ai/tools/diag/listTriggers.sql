-- listTriggers: triggers of a table or a schema
-- @family: sqlserver
SELECT
    SCHEMA_NAME(o.schema_id) AS schema_name,
    o.name AS table_name,
    t.name AS trigger_name,
    t.is_disabled,
    t.is_instead_of_trigger,
    (SELECT STRING_AGG(te.type_desc, ',') FROM sys.trigger_events te WHERE te.object_id = t.object_id) AS events,
    t.create_date,
    t.modify_date
FROM sys.triggers t
JOIN sys.objects o ON o.object_id = t.parent_id
WHERE t.parent_class = 1
  AND (${table} IS NULL OR t.parent_id = OBJECT_ID(${table}))
  AND (${schema} IS NULL OR SCHEMA_NAME(o.schema_id) = ${schema})
ORDER BY schema_name, table_name, trigger_name

-- @family: postgresql
SELECT
    n.nspname AS schema_name,
    c.relname AS table_name,
    t.tgname AS trigger_name,
    t.tgenabled = 'D' AS is_disabled,
    pg_get_triggerdef(t.oid, true) AS definition,
    fn.nspname || '.' || p.proname AS trigger_function
FROM pg_trigger t
JOIN pg_class c ON c.oid = t.tgrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_proc p ON p.oid = t.tgfoid
JOIN pg_namespace fn ON fn.oid = p.pronamespace
WHERE NOT t.tgisinternal
  AND (${table}::text IS NULL OR c.relname = ${table} OR n.nspname || '.' || c.relname = ${table})
  AND (${schema}::text IS NULL OR n.nspname = ${schema})
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
ORDER BY 1, 2, 3

-- @family: mysql
SELECT TRIGGER_SCHEMA AS schema_name, EVENT_OBJECT_TABLE AS table_name, TRIGGER_NAME AS trigger_name,
       ACTION_TIMING, EVENT_MANIPULATION, ACTION_ORIENTATION, CREATED, DEFINER
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = COALESCE(${schema}, IF(LOCATE('.', COALESCE(${table}, '')) > 0, SUBSTRING_INDEX(${table}, '.', 1), DATABASE()))
  AND (${table} IS NULL OR EVENT_OBJECT_TABLE = SUBSTRING_INDEX(${table}, '.', -1))
ORDER BY 1, 2, 3

-- @family: oracle
SELECT owner AS schema_name, table_name, trigger_name, trigger_type, triggering_event, status, base_object_type
FROM all_triggers
WHERE owner = COALESCE(UPPER(${schema}),
                       CASE WHEN INSTR(${table}, '.') > 0 THEN UPPER(SUBSTR(${table}, 1, INSTR(${table}, '.') - 1)) END,
                       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
  AND (${table} IS NULL OR table_name = UPPER(SUBSTR(${table}, INSTR(${table}, '.') + 1)))
ORDER BY 1, 2, 3
