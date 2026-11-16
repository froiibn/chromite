-- listPrincipals: logins, users, roles and memberships
-- @family: sqlserver
-- @requires: VIEW ANY DEFINITION (to see other logins)
-- @statement: serverPrincipals
SELECT p.name, p.type_desc, p.is_disabled, p.default_database_name, p.create_date, p.modify_date,
       (SELECT STRING_AGG(r.name, ',') FROM sys.server_role_members m JOIN sys.server_principals r ON r.principal_id = m.role_principal_id
         WHERE m.member_principal_id = p.principal_id) AS server_roles
FROM sys.server_principals p
WHERE p.type IN ('S', 'U', 'G', 'R', 'E', 'X') AND p.name NOT LIKE '##%'
ORDER BY p.type_desc, p.name
-- @statement: databasePrincipals
SELECT p.name, p.type_desc, p.authentication_type_desc, p.default_schema_name, SUSER_SNAME(p.sid) AS login_name,
       p.create_date,
       (SELECT STRING_AGG(r.name, ',') FROM sys.database_role_members m JOIN sys.database_principals r ON r.principal_id = m.role_principal_id
         WHERE m.member_principal_id = p.principal_id) AS database_roles
FROM sys.database_principals p
WHERE p.type IN ('S', 'U', 'G', 'R', 'E', 'X', 'A') AND p.name NOT IN ('sys', 'INFORMATION_SCHEMA', 'guest') AND p.name NOT LIKE '##%'
ORDER BY p.type_desc, p.name

-- @family: postgresql
SELECT r.rolname AS name, r.rolsuper AS is_superuser, r.rolcanlogin AS can_login, r.rolcreatedb, r.rolcreaterole,
       r.rolreplication, r.rolbypassrls, r.rolconnlimit, r.rolvaliduntil,
       ARRAY(SELECT b.rolname FROM pg_auth_members m JOIN pg_roles b ON b.oid = m.roleid WHERE m.member = r.oid ORDER BY 1)::text AS member_of
FROM pg_roles r
WHERE r.rolname NOT LIKE 'pg\_%'
ORDER BY r.rolcanlogin DESC, r.rolname

-- @family: mysql
-- @requires: SELECT on mysql.user (users statement)
-- @statement: users
SELECT User AS name, Host AS host, account_locked, password_expired, max_connections, plugin
FROM mysql.user
ORDER BY User, Host
-- @statement: privileges
SELECT GRANTEE, PRIVILEGE_TYPE, IS_GRANTABLE FROM information_schema.USER_PRIVILEGES ORDER BY GRANTEE, PRIVILEGE_TYPE

-- @family: oracle
-- @requires: SELECT on DBA_ROLE_PRIVS (roles statement)
-- @statement: users
SELECT username AS name, user_id, created, common, oracle_maintained FROM all_users WHERE oracle_maintained = 'N' ORDER BY username
-- @statement: roles
SELECT grantee, granted_role, admin_option, default_role FROM dba_role_privs
WHERE grantee IN (SELECT username FROM all_users WHERE oracle_maintained = 'N') ORDER BY grantee, granted_role
