-- listPermissions: grants on an object or of a principal (current database)
-- @family: sqlserver
SELECT
    pr.name AS principal_name,
    pr.type_desc AS principal_type,
    pe.state_desc,
    pe.permission_name,
    pe.class_desc,
    CASE pe.class WHEN 1 THEN OBJECT_SCHEMA_NAME(pe.major_id) + '.' + OBJECT_NAME(pe.major_id)
                  WHEN 3 THEN SCHEMA_NAME(pe.major_id)
                  WHEN 0 THEN DB_NAME() END AS securable,
    COL_NAME(pe.major_id, pe.minor_id) AS column_name,
    gr.name AS grantor
FROM sys.database_permissions pe
JOIN sys.database_principals pr ON pr.principal_id = pe.grantee_principal_id
LEFT JOIN sys.database_principals gr ON gr.principal_id = pe.grantor_principal_id
WHERE (${object} IS NULL OR (pe.class = 1 AND pe.major_id = OBJECT_ID(${object})) OR (pe.class = 3 AND SCHEMA_NAME(pe.major_id) = ${object}))
  AND (${principal} IS NULL OR pr.name = ${principal})
ORDER BY securable, principal_name, permission_name

-- @family: postgresql
SELECT grantee AS principal_name, privilege_type AS permission_name, table_schema || '.' || table_name AS securable,
       'TABLE' AS class_desc, is_grantable, grantor
FROM information_schema.role_table_grants
WHERE (${object}::text IS NULL OR table_name = ${object} OR table_schema || '.' || table_name = ${object})
  AND (${principal}::text IS NULL OR grantee = ${principal})
  AND table_schema NOT IN ('pg_catalog', 'information_schema')
UNION ALL
SELECT grantee, privilege_type, routine_schema || '.' || routine_name, 'ROUTINE', is_grantable, grantor
FROM information_schema.role_routine_grants
WHERE (${object}::text IS NULL OR routine_name = ${object} OR routine_schema || '.' || routine_name = ${object})
  AND (${principal}::text IS NULL OR grantee = ${principal})
  AND routine_schema NOT IN ('pg_catalog', 'information_schema')
ORDER BY 3, 1, 2

-- @family: mysql
SELECT GRANTEE AS principal_name, PRIVILEGE_TYPE AS permission_name, CONCAT(TABLE_SCHEMA, '.', TABLE_NAME) AS securable,
       'TABLE' AS class_desc, IS_GRANTABLE
FROM information_schema.TABLE_PRIVILEGES
WHERE (${object} IS NULL OR TABLE_NAME = SUBSTRING_INDEX(${object}, '.', -1))
  AND (${principal} IS NULL OR GRANTEE LIKE CONCAT('%''', ${principal}, '''%'))
UNION ALL
SELECT GRANTEE, PRIVILEGE_TYPE, TABLE_SCHEMA, 'SCHEMA', IS_GRANTABLE
FROM information_schema.SCHEMA_PRIVILEGES
WHERE (${object} IS NULL OR TABLE_SCHEMA = ${object})
  AND (${principal} IS NULL OR GRANTEE LIKE CONCAT('%''', ${principal}, '''%'))
ORDER BY 3, 1, 2

-- @family: oracle
SELECT grantee AS principal_name, privilege AS permission_name, table_schema || '.' || table_name AS securable,
       type AS class_desc, grantable, grantor
FROM all_tab_privs
WHERE (${object} IS NULL OR table_name = UPPER(SUBSTR(${object}, INSTR(${object}, '.') + 1)))
  AND (${principal} IS NULL OR grantee = UPPER(${principal}))
ORDER BY 3, 1, 2
