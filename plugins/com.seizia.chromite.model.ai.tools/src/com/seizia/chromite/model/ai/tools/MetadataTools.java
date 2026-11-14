/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seizia.chromite.model.ai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.DBStructUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Metadata tools on JDBC {@link DatabaseMetaData}: databases, schemas, tables, columns, keys, indexes, routines, search; DDL from the model.
 *
 * 설계: 목록·구조는 DBeaver 탐색기 캐시가 아니라 드라이버 메타데이터를 매번 읽는다. 에이전트가 방금 만든 테이블도 바로 보여야 하고
 * (탐색기 캐시는 새로 고침 전까지 모른다), JDBC 메타데이터는 모든 드라이버에 있다. 이름 패턴의 {@code _}, {@code %}는 이스케이프하고
 * 결과를 정확한 이름으로 다시 거른다. DDL은 드라이버별 DDL 생성기가 있는 모델 객체로 만든다({@link DBStructUtils}, {@link DBPScriptObject});
 * 찾지 못하면 상위 컨테이너를 새로 고친 뒤 한 번 더 찾는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-15 listIndexes·listForeignKeys를 describeTable에 합침(옛 ID는 별칭. describeTable이 둘을 이미 담는다), 이름 거르기 인수
 *   pattern → contains
 *   (도구 정리, docs/chromite/ai-agent.md "별칭")
 */
public class MetadataTools extends AgentTool {

    // The text a name contains (listSchemas, listTables, listRoutines, searchObjects; "pattern" before 26.1.0)
    private static final String PARAM_CONTAINS = "contains"; //$NON-NLS-1$

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        if (toolId.equals("getObjectDdl")) {
            return objectDdl(context, container, args);
        }
        DbFamily family;
        AgentSql.connect(context.getMonitor(), container);
        family = DbFamily.of(container);
        String database = AgentArgs.getString(args, "database");
        return AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.READ_ONLY, (connection, runner) -> switch (toolId) {
            case "listDatabases" -> listDatabases(connection);
            case "listSchemas" -> listSchemas(connection, args);
            case "listTables" -> listTables(connection, family, args);
            case "describeTable" -> describeTable(connection, family, AgentArgs.require(args, "table"));
            case "listRoutines" -> listRoutines(connection, family, args);
            case "searchObjects" -> searchObjects(connection, args);
            default -> throw unknownTool(toolId);
        });
    }

    @NotNull
    private static JsonObject listDatabases(@NotNull Connection connection) throws SQLException {
        JsonArray list = new JsonArray();
        try (ResultSet rs = connection.getMetaData().getCatalogs()) {
            while (rs.next()) {
                list.add(rs.getString(1));
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("current", connection.getCatalog());
        json.add("databases", list);
        return json;
    }

    @NotNull
    private static JsonObject listSchemas(@NotNull Connection connection, @NotNull Map<String, Object> args) throws SQLException {
        JsonArray list = new JsonArray();
        String pattern = AgentArgs.getString(args, PARAM_CONTAINS);
        try (ResultSet rs = connection.getMetaData().getSchemas(connection.getCatalog(), pattern == null ? null : "%" + pattern + "%")) {
            while (rs.next()) {
                list.add(rs.getString("TABLE_SCHEM"));
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("database", connection.getCatalog());
        json.addProperty("current", AgentNames.currentSchema(connection));
        json.add("schemas", list);
        return json;
    }

    @NotNull
    private static JsonObject listTables(
        @NotNull Connection connection,
        @NotNull DbFamily family,
        @NotNull Map<String, Object> args
    ) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        boolean catalogNames = family == DbFamily.MYSQL || family == DbFamily.MARIADB;
        String schema = AgentArgs.getString(args, "schema");
        String catalog = catalogNames ? (schema != null ? schema : connection.getCatalog()) : connection.getCatalog();
        String schemaPattern = catalogNames ? null : schema == null ? AgentNames.currentSchema(connection) :
            AgentNames.normalize(meta, schema);
        String pattern = AgentArgs.getString(args, PARAM_CONTAINS);
        List<String> types = AgentArgs.getList(args, "types");
        String[] typeArray = types.isEmpty() ? new String[] {"TABLE", "VIEW"} :
            types.stream().map(t -> t.toUpperCase(Locale.ROOT)).toArray(String[]::new);
        AgentLimits limits = AgentLimits.of(args, 1000);
        JsonArray list = new JsonArray();
        boolean more = false;
        try (ResultSet rs = meta.getTables(catalog, schemaPattern,
            pattern == null ? "%" : "%" + AgentNames.pattern(meta, pattern) + "%", typeArray)) {
            while (rs.next()) {
                if (list.size() >= limits.maxRows()) {
                    more = true;
                    break;
                }
                JsonObject table = new JsonObject();
                table.addProperty("schema", catalogNames ? rs.getString("TABLE_CAT") : rs.getString("TABLE_SCHEM"));
                table.addProperty("name", rs.getString("TABLE_NAME"));
                table.addProperty("type", rs.getString("TABLE_TYPE"));
                String remarks = rs.getString("REMARKS");
                if (remarks != null && !remarks.isBlank()) {
                    table.addProperty("comment", remarks);
                }
                list.add(table);
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("database", connection.getCatalog());
        json.addProperty("schema", catalogNames ? catalog : schemaPattern);
        json.add("tables", list);
        json.addProperty("count", list.size());
        json.addProperty("hasMore", more);
        return json;
    }

    @NotNull
    private static JsonObject describeTable(@NotNull Connection connection, @NotNull DbFamily family, @NotNull String table)
        throws SQLException, AIToolArgumentException {
        AgentNames.QName name = AgentNames.resolve(connection, family, table);
        DatabaseMetaData meta = connection.getMetaData();
        JsonArray columns = new JsonArray();
        String tableType = null;
        try (ResultSet rs = meta.getTables(name.catalog(), AgentNames.pattern(meta, name.schema()), AgentNames.pattern(meta, name.name()), null)) {
            while (rs.next()) {
                if (name.name().equals(rs.getString("TABLE_NAME"))) {
                    tableType = rs.getString("TABLE_TYPE");
                    break;
                }
            }
        }
        if (tableType == null) {
            throw new AIToolArgumentException("Table '" + name.display() + "' not found. Call listTables or searchObjects.");
        }
        try (ResultSet rs = meta.getColumns(name.catalog(), AgentNames.pattern(meta, name.schema()), AgentNames.pattern(meta, name.name()), null)) {
            while (rs.next()) {
                if (!name.name().equals(rs.getString("TABLE_NAME"))) {
                    continue;
                }
                JsonObject column = new JsonObject();
                column.addProperty("name", rs.getString("COLUMN_NAME"));
                column.addProperty("type", rs.getString("TYPE_NAME"));
                int size = rs.getInt("COLUMN_SIZE");
                if (!rs.wasNull()) {
                    column.addProperty("size", size);
                }
                int digits = rs.getInt("DECIMAL_DIGITS");
                if (!rs.wasNull() && digits != 0) {
                    column.addProperty("scale", digits);
                }
                column.addProperty("nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                String def = rs.getString("COLUMN_DEF");
                if (def != null) {
                    column.addProperty("default", def);
                }
                String auto = safeString(rs, "IS_AUTOINCREMENT");
                if ("YES".equals(auto)) {
                    column.addProperty("autoIncrement", true);
                }
                String remarks = rs.getString("REMARKS");
                if (remarks != null && !remarks.isBlank()) {
                    column.addProperty("comment", remarks);
                }
                columns.add(column);
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("table", name.display());
        json.addProperty("type", tableType);
        json.add("columns", columns);
        json.add("primaryKey", primaryKey(meta, name));
        json.add("indexes", indexes(connection, name).get("indexes"));
        JsonObject keys = foreignKeys(connection, name);
        json.add("foreignKeys", keys.get("outgoing"));
        json.add("referencedBy", keys.get("incoming"));
        return json;
    }

    @NotNull
    private static JsonElement primaryKey(@NotNull DatabaseMetaData meta, @NotNull AgentNames.QName name) throws SQLException {
        Map<Integer, String> columns = new java.util.TreeMap<>();
        String keyName = null;
        try (ResultSet rs = meta.getPrimaryKeys(name.catalog(), name.schema(), name.name())) {
            while (rs.next()) {
                columns.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                keyName = rs.getString("PK_NAME");
            }
        }
        if (columns.isEmpty()) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonObject pk = new JsonObject();
        pk.addProperty("name", keyName);
        pk.addProperty("columns", String.join(", ", columns.values()));
        return pk;
    }

    /**
     * Indexes of a table: name, unique, columns in order.
     */
    @NotNull
    static JsonObject indexes(@NotNull Connection connection, @NotNull AgentNames.QName name) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        Map<String, JsonObject> indexes = new LinkedHashMap<>();
        Map<String, Map<Integer, String>> columns = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(name.catalog(), name.schema(), name.name(), false, true)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME");
                if (index == null || rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                JsonObject json = indexes.computeIfAbsent(index, k -> new JsonObject());
                json.addProperty("name", index);
                json.addProperty("unique", !rs.getBoolean("NON_UNIQUE"));
                String column = rs.getString("COLUMN_NAME");
                String order = rs.getString("ASC_OR_DESC");
                columns.computeIfAbsent(index, k -> new java.util.TreeMap<>())
                    .put((int) rs.getShort("ORDINAL_POSITION"), "D".equals(order) ? column + " DESC" : column);
                String filter = rs.getString("FILTER_CONDITION");
                if (filter != null && !filter.isBlank()) {
                    json.addProperty("filter", filter);
                }
            }
        }
        JsonArray list = new JsonArray();
        for (Map.Entry<String, JsonObject> e : indexes.entrySet()) {
            e.getValue().addProperty("columns", String.join(", ", columns.get(e.getKey()).values()));
            list.add(e.getValue());
        }
        JsonObject json = new JsonObject();
        json.addProperty("table", name.display());
        json.add("indexes", list);
        return json;
    }

    /**
     * Foreign keys of a table and the foreign keys of other tables that point to it.
     */
    @NotNull
    static JsonObject foreignKeys(@NotNull Connection connection, @NotNull AgentNames.QName name) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        JsonObject json = new JsonObject();
        json.addProperty("table", name.display());
        try (ResultSet rs = meta.getImportedKeys(name.catalog(), name.schema(), name.name())) {
            json.add("outgoing", readKeys(rs));
        }
        try (ResultSet rs = meta.getExportedKeys(name.catalog(), name.schema(), name.name())) {
            json.add("incoming", readKeys(rs));
        }
        return json;
    }

    @NotNull
    private static JsonArray readKeys(@NotNull ResultSet rs) throws SQLException {
        Map<String, JsonObject> keys = new LinkedHashMap<>();
        Map<String, List<String>> from = new LinkedHashMap<>();
        Map<String, List<String>> to = new LinkedHashMap<>();
        while (rs.next()) {
            String fkTable = join(rs.getString("FKTABLE_SCHEM"), rs.getString("FKTABLE_CAT"), rs.getString("FKTABLE_NAME"));
            String pkTable = join(rs.getString("PKTABLE_SCHEM"), rs.getString("PKTABLE_CAT"), rs.getString("PKTABLE_NAME"));
            String keyName = rs.getString("FK_NAME");
            String id = fkTable + "/" + (keyName == null ? pkTable : keyName);
            JsonObject key = keys.computeIfAbsent(id, k -> new JsonObject());
            key.addProperty("name", keyName);
            key.addProperty("table", fkTable);
            key.addProperty("referencedTable", pkTable);
            key.addProperty("onDelete", rule(rs.getShort("DELETE_RULE")));
            key.addProperty("onUpdate", rule(rs.getShort("UPDATE_RULE")));
            from.computeIfAbsent(id, k -> new ArrayList<>()).add(rs.getString("FKCOLUMN_NAME"));
            to.computeIfAbsent(id, k -> new ArrayList<>()).add(rs.getString("PKCOLUMN_NAME"));
        }
        JsonArray list = new JsonArray();
        for (Map.Entry<String, JsonObject> e : keys.entrySet()) {
            e.getValue().addProperty("columns", String.join(", ", from.get(e.getKey())));
            e.getValue().addProperty("referencedColumns", String.join(", ", to.get(e.getKey())));
            list.add(e.getValue());
        }
        return list;
    }

    @NotNull
    private static String rule(short rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    @NotNull
    private static String join(@Nullable String schema, @Nullable String catalog, @NotNull String name) {
        String owner = schema != null ? schema : catalog;
        return owner == null ? name : owner + "." + name;
    }

    @NotNull
    private static JsonObject listRoutines(
        @NotNull Connection connection,
        @NotNull DbFamily family,
        @NotNull Map<String, Object> args
    ) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        boolean catalogNames = family == DbFamily.MYSQL || family == DbFamily.MARIADB;
        String schema = AgentArgs.getString(args, "schema");
        String catalog = catalogNames ? (schema != null ? schema : connection.getCatalog()) : connection.getCatalog();
        String schemaPattern = catalogNames ? null : schema == null ? AgentNames.currentSchema(connection) : AgentNames.normalize(meta, schema);
        String pattern = AgentArgs.getString(args, PARAM_CONTAINS);
        String namePattern = pattern == null ? "%" : "%" + AgentNames.pattern(meta, pattern) + "%";
        Map<String, JsonObject> routines = new LinkedHashMap<>();
        try (ResultSet rs = meta.getProcedures(catalog, schemaPattern, namePattern)) {
            while (rs.next()) {
                String name = rs.getString("PROCEDURE_NAME");
                // SQL Server appends ";1" (procedure number)
                int semicolon = name.indexOf(';');
                name = semicolon > 0 ? name.substring(0, semicolon) : name;
                String owner = catalogNames ? rs.getString("PROCEDURE_CAT") : rs.getString("PROCEDURE_SCHEM");
                JsonObject routine = new JsonObject();
                routine.addProperty("schema", owner);
                routine.addProperty("name", name);
                routine.addProperty("type", rs.getShort("PROCEDURE_TYPE") == DatabaseMetaData.procedureReturnsResult ? "FUNCTION" : "PROCEDURE");
                String remarks = rs.getString("REMARKS");
                if (remarks != null && !remarks.isBlank()) {
                    routine.addProperty("comment", remarks);
                }
                routines.putIfAbsent(owner + "." + name, routine);
            }
        }
        try (ResultSet rs = meta.getFunctions(catalog, schemaPattern, namePattern)) {
            while (rs.next()) {
                String name = rs.getString("FUNCTION_NAME");
                int semicolon = name.indexOf(';');
                name = semicolon > 0 ? name.substring(0, semicolon) : name;
                String owner = catalogNames ? rs.getString("FUNCTION_CAT") : rs.getString("FUNCTION_SCHEM");
                JsonObject routine = routines.get(owner + "." + name);
                if (routine == null) {
                    routine = new JsonObject();
                    routine.addProperty("schema", owner);
                    routine.addProperty("name", name);
                    routines.put(owner + "." + name, routine);
                }
                routine.addProperty("type", "FUNCTION");
            }
        } catch (SQLException | AbstractMethodError e) {
            // old drivers without getFunctions: procedures only
        }
        JsonArray list = new JsonArray();
        routines.values().forEach(list::add);
        JsonObject json = new JsonObject();
        json.add("routines", list);
        json.addProperty("count", list.size());
        json.addProperty("hint", "getRoutineSource gives the source with line numbers and the parameters");
        return json;
    }

    @NotNull
    private static JsonObject searchObjects(@NotNull Connection connection, @NotNull Map<String, Object> args)
        throws SQLException, AIToolArgumentException {
        DatabaseMetaData meta = connection.getMetaData();
        String text = AgentArgs.require(args, PARAM_CONTAINS);
        String pattern = "%" + AgentNames.pattern(meta, text) + "%";
        List<String> types = AgentArgs.getList(args, "types");
        boolean wantTables = types.isEmpty() || types.stream().anyMatch(t -> t.equalsIgnoreCase("table") || t.equalsIgnoreCase("view"));
        boolean wantRoutines = types.isEmpty() || types.stream().anyMatch(t -> t.equalsIgnoreCase("routine") ||
            t.equalsIgnoreCase("procedure") || t.equalsIgnoreCase("function"));
        boolean wantColumns = types.stream().anyMatch(t -> t.equalsIgnoreCase("column"));
        AgentLimits limits = AgentLimits.of(args, 200);
        JsonArray list = new JsonArray();
        String catalog = connection.getCatalog();
        if (wantTables) {
            try (ResultSet rs = meta.getTables(catalog, null, pattern, new String[] {"TABLE", "VIEW"})) {
                while (rs.next() && list.size() < limits.maxRows()) {
                    list.add(found(rs.getString("TABLE_SCHEM"), rs.getString("TABLE_CAT"), rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")));
                }
            }
        }
        if (wantRoutines) {
            try (ResultSet rs = meta.getProcedures(catalog, null, pattern)) {
                while (rs.next() && list.size() < limits.maxRows()) {
                    String name = rs.getString("PROCEDURE_NAME");
                    int semicolon = name.indexOf(';');
                    list.add(found(rs.getString("PROCEDURE_SCHEM"), rs.getString("PROCEDURE_CAT"),
                        semicolon > 0 ? name.substring(0, semicolon) : name, "ROUTINE"));
                }
            }
        }
        if (wantColumns) {
            try (ResultSet rs = meta.getColumns(catalog, null, "%", pattern)) {
                while (rs.next() && list.size() < limits.maxRows()) {
                    JsonObject column = found(rs.getString("TABLE_SCHEM"), rs.getString("TABLE_CAT"), rs.getString("TABLE_NAME"), "COLUMN");
                    column.addProperty("column", rs.getString("COLUMN_NAME"));
                    list.add(column);
                }
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("database", catalog);
        json.add("objects", list);
        json.addProperty("count", list.size());
        json.addProperty("hasMore", list.size() >= limits.maxRows());
        return json;
    }

    @NotNull
    private static JsonObject found(@Nullable String schema, @Nullable String catalog, @NotNull String name, @NotNull String type) {
        JsonObject json = new JsonObject();
        json.addProperty("name", join(schema, catalog, name));
        json.addProperty("type", type);
        return json;
    }

    @Nullable
    private static String safeString(@NotNull ResultSet rs, @NotNull String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ DDL from the model

    @NotNull
    private static JsonElement objectDdl(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull Map<String, Object> args
    ) throws DBException {
        String objectName = AgentArgs.require(args, "object");
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        DbFamily family = DbFamily.of(container);
        AgentNames.QName name = AgentSql.run(context.getMonitor(), container, AgentArgs.getString(args, "database"),
            AgentSql.Mode.READ_ONLY, (connection, runner) -> {
                AgentNames.QName q = AgentNames.resolve(connection, family, objectName);
                // DBeaver's model names databases as catalogs; fill the current one so that the path is complete
                return q.catalog() == null && family != DbFamily.ORACLE && family != DbFamily.POSTGRESQL
                    ? new AgentNames.QName(connection.getCatalog(), q.schema(), q.name())
                    : q;
            });
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
        DBCExecutionContext executionContext = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), true);
        if (root == null || executionContext == null) {
            throw new DBException("The connection has no object model");
        }
        DBSObject object = DBUtils.getObjectByPath(context.getMonitor(), executionContext, root, name.catalog(), name.schema(), name.name());
        if (object == null) {
            // The navigator cache may predate the object (created by the agent or another client): refresh its container once
            DBSObject parent = name.schema() != null
                ? DBUtils.getObjectByPath(context.getMonitor(), executionContext, root, name.catalog(), null, name.schema())
                : name.catalog() != null ? root.getChild(context.getMonitor(), name.catalog()) : null;
            if (parent instanceof DBPRefreshableObject refreshable) {
                refreshable.refreshObject(context.getMonitor());
            }
            object = DBUtils.getObjectByPath(context.getMonitor(), executionContext, root, name.catalog(), name.schema(), name.name());
        }
        if (object == null) {
            throw new AIToolArgumentException("Object '" + name.display() + "' not found");
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put(DBPScriptObject.OPTION_INCLUDE_NESTED_OBJECTS, true);
        options.put(DBPScriptObject.OPTION_INCLUDE_COMMENTS, true);
        String ddl;
        if (object instanceof DBSEntity entity) {
            ddl = DBStructUtils.generateTableDDL(context.getMonitor(), entity, options, false);
        } else if (object instanceof DBPScriptObject script) {
            ddl = script.getObjectDefinitionText(context.getMonitor(), options);
        } else {
            ddl = DBStructUtils.generateObjectDDL(context.getMonitor(), object, options, false);
        }
        JsonObject json = new JsonObject();
        json.addProperty("object", DBUtils.getObjectFullName(object, com.seizia.chromite.model.DBPEvaluationContext.DDL));
        json.addProperty("type", object.getClass().getSimpleName());
        json.addProperty("ddl", ddl);
        return json;
    }
}
