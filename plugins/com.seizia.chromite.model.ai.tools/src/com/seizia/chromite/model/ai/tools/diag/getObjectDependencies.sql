-- getObjectDependencies: direct edges of one object; DiagnosticPostProcessor follows "uses" edges up to depth 3
-- Columns: direction, from_object, from_type, to_object, to_type, kind
-- @family: sqlserver
-- @requires: VIEW DEFINITION
-- @statement: edges
SELECT 'uses' AS direction,
       OBJECT_SCHEMA_NAME(d.referencing_id) + '.' + OBJECT_NAME(d.referencing_id) AS from_object, o1.type_desc AS from_type,
       COALESCE(d.referenced_database_name + '.', '') + COALESCE(d.referenced_schema_name, OBJECT_SCHEMA_NAME(d.referenced_id), 'dbo') + '.' + d.referenced_entity_name AS to_object,
       o2.type_desc AS to_type,
       CASE WHEN d.is_caller_dependent = 1 THEN 'reference (caller dependent)' WHEN d.referenced_id IS NULL THEN 'reference (unresolved)' ELSE 'reference' END AS kind
FROM sys.sql_expression_dependencies d
JOIN sys.objects o1 ON o1.object_id = d.referencing_id
LEFT JOIN sys.objects o2 ON o2.object_id = d.referenced_id
WHERE d.referencing_id = OBJECT_ID(${object}) AND d.referencing_minor_id = 0
UNION
SELECT 'usedBy',
       OBJECT_SCHEMA_NAME(d.referencing_id) + '.' + OBJECT_NAME(d.referencing_id), o1.type_desc,
       OBJECT_SCHEMA_NAME(d.referenced_id) + '.' + OBJECT_NAME(d.referenced_id), o2.type_desc, 'reference'
FROM sys.sql_expression_dependencies d
JOIN sys.objects o1 ON o1.object_id = d.referencing_id
JOIN sys.objects o2 ON o2.object_id = d.referenced_id
WHERE d.referenced_id = OBJECT_ID(${object})
UNION
SELECT 'usedBy', OBJECT_SCHEMA_NAME(t.object_id) + '.' + t.name, 'SQL_TRIGGER',
       OBJECT_SCHEMA_NAME(t.parent_id) + '.' + OBJECT_NAME(t.parent_id), 'USER_TABLE', 'trigger on'
FROM sys.triggers t
WHERE t.parent_id = OBJECT_ID(${object})
UNION
SELECT 'uses', OBJECT_SCHEMA_NAME(fk.parent_object_id) + '.' + OBJECT_NAME(fk.parent_object_id), 'USER_TABLE',
       OBJECT_SCHEMA_NAME(fk.referenced_object_id) + '.' + OBJECT_NAME(fk.referenced_object_id), 'USER_TABLE', 'foreign key ' + fk.name
FROM sys.foreign_keys fk
WHERE fk.parent_object_id = OBJECT_ID(${object})
UNION
SELECT 'usedBy', OBJECT_SCHEMA_NAME(fk.parent_object_id) + '.' + OBJECT_NAME(fk.parent_object_id), 'USER_TABLE',
       OBJECT_SCHEMA_NAME(fk.referenced_object_id) + '.' + OBJECT_NAME(fk.referenced_object_id), 'USER_TABLE', 'foreign key ' + fk.name
FROM sys.foreign_keys fk
WHERE fk.referenced_object_id = OBJECT_ID(${object})

-- @family: postgresql
-- @note: PL/pgSQL bodies are not tracked by pg_depend; edges of kind "mentioned in source" come from a name search in the function source and can be false positives.
-- @statement: edges
WITH rel AS (SELECT to_regclass(${object}) AS oid),
     fn AS (SELECT p.oid, p.prosrc, n.nspname || '.' || p.proname AS name
            FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname || '.' || p.proname = ${object}
               OR (p.proname = ${object} AND n.nspname = ANY (current_schemas(false))))
SELECT DISTINCT 'usedBy' AS direction, v.oid::regclass::text AS from_object,
       CASE v.relkind WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'VIEW' END AS from_type,
       d.refobjid::regclass::text AS to_object, 'TABLE' AS to_type, 'view reads' AS kind
FROM pg_depend d JOIN pg_rewrite r ON r.oid = d.objid JOIN pg_class v ON v.oid = r.ev_class, rel
WHERE d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass AND d.refobjid = rel.oid AND v.oid <> rel.oid
UNION
SELECT DISTINCT 'uses', r.ev_class::regclass::text, 'VIEW', d.refobjid::regclass::text,
       CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END, 'view reads'
FROM pg_depend d JOIN pg_rewrite r ON r.oid = d.objid JOIN pg_class c ON c.oid = d.refobjid, rel
WHERE d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass AND r.ev_class = rel.oid AND d.refobjid <> rel.oid
UNION
SELECT 'usedBy', n.nspname || '.' || t.tgname, 'TRIGGER', rel.oid::regclass::text, 'TABLE', 'trigger on'
FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace, rel
WHERE t.tgrelid = rel.oid AND NOT t.tgisinternal
UNION
SELECT 'uses', n.nspname || '.' || t.tgname, 'TRIGGER', pn.nspname || '.' || p.proname, 'FUNCTION', 'trigger calls'
FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_proc p ON p.oid = t.tgfoid JOIN pg_namespace pn ON pn.oid = p.pronamespace, rel
WHERE t.tgrelid = rel.oid AND NOT t.tgisinternal
UNION
SELECT 'uses', con.conrelid::regclass::text, 'TABLE', con.confrelid::regclass::text, 'TABLE', 'foreign key ' || con.conname
FROM pg_constraint con, rel WHERE con.contype = 'f' AND con.conrelid = rel.oid
UNION
SELECT 'usedBy', con.conrelid::regclass::text, 'TABLE', con.confrelid::regclass::text, 'TABLE', 'foreign key ' || con.conname
FROM pg_constraint con, rel WHERE con.contype = 'f' AND con.confrelid = rel.oid
UNION
SELECT 'uses', fn.name, 'FUNCTION', n.nspname || '.' || c.relname,
       CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END, 'mentioned in source'
FROM fn, pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f') AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
  AND fn.prosrc ~* ('\m' || c.relname || '\M')
UNION
SELECT 'uses', fn.name, 'FUNCTION', n.nspname || '.' || p.proname, 'FUNCTION', 'mentioned in source'
FROM fn, pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND p.oid <> fn.oid
  AND fn.prosrc ~* ('\m' || p.proname || '\s*\(')

-- @family: mysql
-- @note: MySQL/MariaDB do not track routine dependencies; edges of kind "mentioned in source" come from a name search and can be false positives.
-- @statement: edges
SELECT 'uses' AS direction, CONCAT(k.TABLE_SCHEMA, '.', k.TABLE_NAME) AS from_object, 'TABLE' AS from_type,
       CONCAT(k.REFERENCED_TABLE_SCHEMA, '.', k.REFERENCED_TABLE_NAME) AS to_object, 'TABLE' AS to_type,
       CONCAT('foreign key ', k.CONSTRAINT_NAME) AS kind
FROM information_schema.KEY_COLUMN_USAGE k
WHERE k.REFERENCED_TABLE_NAME IS NOT NULL AND k.TABLE_NAME = SUBSTRING_INDEX(${object}, '.', -1)
  AND k.TABLE_SCHEMA = IF(LOCATE('.', ${object}) > 0, SUBSTRING_INDEX(${object}, '.', 1), DATABASE())
UNION
SELECT 'usedBy', CONCAT(k.TABLE_SCHEMA, '.', k.TABLE_NAME), 'TABLE', CONCAT(k.REFERENCED_TABLE_SCHEMA, '.', k.REFERENCED_TABLE_NAME), 'TABLE',
       CONCAT('foreign key ', k.CONSTRAINT_NAME)
FROM information_schema.KEY_COLUMN_USAGE k
WHERE k.REFERENCED_TABLE_NAME = SUBSTRING_INDEX(${object}, '.', -1)
  AND k.REFERENCED_TABLE_SCHEMA = IF(LOCATE('.', ${object}) > 0, SUBSTRING_INDEX(${object}, '.', 1), DATABASE())
UNION
SELECT 'usedBy', CONCAT(t.TRIGGER_SCHEMA, '.', t.TRIGGER_NAME), 'TRIGGER', CONCAT(t.EVENT_OBJECT_SCHEMA, '.', t.EVENT_OBJECT_TABLE), 'TABLE', 'trigger on'
FROM information_schema.TRIGGERS t
WHERE t.EVENT_OBJECT_TABLE = SUBSTRING_INDEX(${object}, '.', -1)
  AND t.EVENT_OBJECT_SCHEMA = IF(LOCATE('.', ${object}) > 0, SUBSTRING_INDEX(${object}, '.', 1), DATABASE())
UNION
SELECT 'uses', CONCAT(r.ROUTINE_SCHEMA, '.', r.ROUTINE_NAME), r.ROUTINE_TYPE, CONCAT(tb.TABLE_SCHEMA, '.', tb.TABLE_NAME), tb.TABLE_TYPE, 'mentioned in source'
FROM information_schema.ROUTINES r
JOIN information_schema.TABLES tb ON tb.TABLE_SCHEMA = r.ROUTINE_SCHEMA
WHERE r.ROUTINE_NAME = SUBSTRING_INDEX(${object}, '.', -1)
  AND r.ROUTINE_SCHEMA = IF(LOCATE('.', ${object}) > 0, SUBSTRING_INDEX(${object}, '.', 1), DATABASE())
  AND r.ROUTINE_DEFINITION REGEXP CONCAT('\\b', tb.TABLE_NAME, '\\b')
UNION
SELECT 'uses', CONCAT(r.ROUTINE_SCHEMA, '.', r.ROUTINE_NAME), r.ROUTINE_TYPE, CONCAT(c.ROUTINE_SCHEMA, '.', c.ROUTINE_NAME), c.ROUTINE_TYPE, 'mentioned in source'
FROM information_schema.ROUTINES r
JOIN information_schema.ROUTINES c ON c.ROUTINE_SCHEMA = r.ROUTINE_SCHEMA AND c.ROUTINE_NAME <> r.ROUTINE_NAME
WHERE r.ROUTINE_NAME = SUBSTRING_INDEX(${object}, '.', -1)
  AND r.ROUTINE_SCHEMA = IF(LOCATE('.', ${object}) > 0, SUBSTRING_INDEX(${object}, '.', 1), DATABASE())
  AND r.ROUTINE_DEFINITION LIKE CONCAT('%', c.ROUTINE_NAME, '(%')

-- @family: oracle
-- @statement: edges
SELECT 'uses' AS direction, d.owner || '.' || d.name AS from_object, d.type AS from_type,
       d.referenced_owner || '.' || d.referenced_name AS to_object, d.referenced_type AS to_type, d.dependency_type AS kind
FROM all_dependencies d
WHERE d.name = UPPER(SUBSTR(${object}, INSTR(${object}, '.') + 1))
  AND d.owner = CASE WHEN INSTR(${object}, '.') > 0 THEN UPPER(SUBSTR(${object}, 1, INSTR(${object}, '.') - 1)) ELSE SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') END
  AND d.referenced_owner NOT IN ('SYS', 'PUBLIC')
UNION
SELECT 'usedBy', d.owner || '.' || d.name, d.type, d.referenced_owner || '.' || d.referenced_name, d.referenced_type, d.dependency_type
FROM all_dependencies d
WHERE d.referenced_name = UPPER(SUBSTR(${object}, INSTR(${object}, '.') + 1))
  AND d.referenced_owner = CASE WHEN INSTR(${object}, '.') > 0 THEN UPPER(SUBSTR(${object}, 1, INSTR(${object}, '.') - 1)) ELSE SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') END
