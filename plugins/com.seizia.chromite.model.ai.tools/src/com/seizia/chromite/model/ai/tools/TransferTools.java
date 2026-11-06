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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolConnections;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Transfer tools: export a query or table to CSV/JSON/XLSX, import a CSV into a table, copy a table between connections.
 *
 * 설계(docs/chromite/ai-agent.md "설계 결정과 이유" — 데이터 전송은 간단한 자체 구현):
 * <ul>
 *     <li>Swing 마법사의 데이터 전송 모델은 화면 설정이 많아 도구 인수로 옮기기 어렵다. 도구는 JDBC로 읽어 스트림으로 쓴다(XLSX는 Apache POI의
 *     스트리밍 통합 문서). 기본 최대 100,000행이고, 그보다 크면 마법사를 쓰라고 안내한다.</li>
 *     <li>{@code importCsv}·{@code copyTable}의 {@code connection} 인수는 쓰는 쪽(대상) 연결이다. 정책·읽기 전용 검사가 실제로 바뀌는 연결에 걸리게
 *     하기 위해서다. 읽는 쪽은 {@code sourceConnection}.</li>
 *     <li>파일은 절대 경로만 받고, 있는 파일은 {@code overwrite=true}일 때만 덮어쓴다.</li>
 *     <li>대상에 넣기는 {@link AgentSql.Mode#ATOMIC}: 자동 커밋 연결이면 한 호출이 한 트랜잭션이라 중간 실패 시 반만 들어가지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public class TransferTools extends AgentTool implements AIToolPreviewProvider {

    private static final int DEFAULT_MAX_ROWS = 100_000;
    private static final int MAX_ROWS = 1_000_000;

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        return switch (toolId) {
            case "exportData" -> export(context, args);
            case "importCsv" -> importCsv(context, args);
            case "copyTable" -> copyTable(context, args);
            default -> throw unknownTool(toolId);
        };
    }

    // ------------------------------------------------------------------ export

    @NotNull
    private static JsonElement export(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        DbFamily family = DbFamily.of(container);
        String format = AgentArgs.getString(args, "format") == null ? "csv" : AgentArgs.getString(args, "format").toLowerCase(Locale.ROOT);
        if (!format.equals("csv") && !format.equals("json") && !format.equals("xlsx")) {
            throw new AIToolArgumentException("Argument 'format' must be csv, json or xlsx");
        }
        Path file = outputFile(args);
        int maxRows = maxRows(args);
        String delimiter = AgentArgs.getString(args, "delimiter") == null ? "," : AgentArgs.getString(args, "delimiter");
        String sqlArg = AgentArgs.getString(args, "sql");
        String table = AgentArgs.getString(args, "table");
        if ((sqlArg == null) == (table == null)) {
            throw new AIToolArgumentException("Pass either 'sql' or 'table'");
        }
        long[] written = new long[1];
        AgentSql.run(context.getMonitor(), container, AgentArgs.getString(args, "database"), AgentSql.Mode.READ_ONLY, (connection, runner) -> {
            String sql = sqlArg != null ? AgentSqlClassifier.stripTrailingDelimiter(sqlArg)
                : "SELECT * FROM " + AgentNames.resolve(connection, family, table).quoted(dataSource);
            QueryTools.requireReadOnly(dataSource, sql, "exportData");
            try (Statement statement = connection.createStatement()) {
                statement.setMaxRows(maxRows + 1);
                statement.setFetchSize(1000);
                runner.track(statement, true);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    written[0] = switch (format) {
                        case "json" -> writeJson(rs, file, maxRows, runner);
                        case "xlsx" -> writeXlsx(rs, file, maxRows, runner);
                        default -> writeCsv(rs, file, maxRows, delimiter, runner);
                    };
                } finally {
                    runner.track(statement, false);
                }
            }
            return null;
        });
        JsonObject json = new JsonObject();
        json.addProperty("file", file.toString());
        json.addProperty("format", format);
        json.addProperty("rows", Math.min(written[0], maxRows));
        json.addProperty("truncated", written[0] > maxRows);
        try {
            json.addProperty("bytes", Files.size(file));
        } catch (IOException e) {
            // size is informational
        }
        return json;
    }

    private static long writeCsv(@NotNull ResultSet rs, @NotNull Path file, int maxRows, @NotNull String delimiter, @NotNull AgentSql.Runner runner)
        throws SQLException, IOException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        long rows = 0;
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 1; i <= count; i++) {
                out.write((i > 1 ? delimiter : "") + csv(meta.getColumnLabel(i), delimiter));
            }
            out.write("\r\n");
            while (rs.next()) {
                if (++rows > maxRows || runner.getMonitor().isCanceled()) {
                    break;
                }
                for (int i = 1; i <= count; i++) {
                    Object value = rs.getObject(i);
                    out.write((i > 1 ? delimiter : "") + (value == null ? "" : csv(text(value), delimiter)));
                }
                out.write("\r\n");
            }
        }
        return rows;
    }

    @NotNull
    private static String csv(@NotNull String value, @NotNull String delimiter) {
        if (value.contains(delimiter) || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static long writeJson(@NotNull ResultSet rs, @NotNull Path file, int maxRows, @NotNull AgentSql.Runner runner)
        throws SQLException, IOException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        long rows = 0;
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             JsonWriter writer = new JsonWriter(out)) {
            writer.setIndent("  ");
            writer.beginArray();
            while (rs.next()) {
                if (++rows > maxRows || runner.getMonitor().isCanceled()) {
                    break;
                }
                writer.beginObject();
                for (int i = 1; i <= count; i++) {
                    writer.name(meta.getColumnLabel(i));
                    AgentJson.GSON.toJson(AgentJson.readValue(rs, i, meta.getColumnType(i), Integer.MAX_VALUE / 4).json(), writer);
                }
                writer.endObject();
            }
            writer.endArray();
        }
        return rows;
    }

    private static long writeXlsx(@NotNull ResultSet rs, @NotNull Path file, int maxRows, @NotNull AgentSql.Runner runner)
        throws SQLException, IOException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        long rows = 0;
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(500)) {
            Sheet sheet = workbook.createSheet("Data");
            Row header = sheet.createRow(0);
            for (int i = 1; i <= count; i++) {
                header.createCell(i - 1).setCellValue(meta.getColumnLabel(i));
            }
            while (rs.next()) {
                if (++rows > maxRows || runner.getMonitor().isCanceled()) {
                    break;
                }
                Row row = sheet.createRow((int) rows);
                for (int i = 1; i <= count; i++) {
                    Object value = rs.getObject(i);
                    if (value == null) {
                        continue;
                    }
                    Cell cell = row.createCell(i - 1);
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else if (value instanceof Boolean b) {
                        cell.setCellValue(b);
                    } else {
                        String text = text(value);
                        // Excel cells hold at most 32767 characters
                        cell.setCellValue(text.length() > 32767 ? text.substring(0, 32767) : text);
                    }
                }
            }
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                workbook.write(out);
            }
            workbook.dispose();
        }
        return rows;
    }

    @NotNull
    private static String text(@NotNull Object value) throws SQLException {
        if (value instanceof java.sql.Clob clob) {
            return clob.getSubString(1, (int) Math.min(clob.length(), Integer.MAX_VALUE / 4));
        }
        if (value instanceof byte[] bytes) {
            return "0x" + java.util.HexFormat.of().formatHex(bytes);
        }
        return value.toString();
    }

    // ------------------------------------------------------------------ import

    @NotNull
    private static JsonElement importCsv(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        DbFamily family = DbFamily.of(container);
        Path file = Path.of(AgentArgs.require(args, "file"));
        if (!file.isAbsolute() || !Files.isRegularFile(file)) {
            throw new AIToolArgumentException("Argument 'file' must be the absolute path of an existing CSV file");
        }
        String delimiter = AgentArgs.getString(args, "delimiter") == null ? "," : AgentArgs.getString(args, "delimiter");
        if (delimiter.length() != 1) {
            throw new AIToolArgumentException("Argument 'delimiter' must be one character");
        }
        Charset charset = Charset.forName(AgentArgs.getString(args, "encoding") == null ? "UTF-8" : AgentArgs.getString(args, "encoding"));
        int maxRows = maxRows(args);
        List<List<String>> records;
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            records = CsvReader.read(reader, delimiter.charAt(0), maxRows + 1);
        } catch (IOException e) {
            throw new DBException("Can't read " + file + ": " + e.getMessage(), e);
        }
        if (records.isEmpty()) {
            throw new AIToolArgumentException("The CSV file is empty");
        }
        List<String> header = records.getFirst();
        if (!header.isEmpty() && header.getFirst().startsWith("﻿")) {
            header.set(0, header.getFirst().substring(1));
        }
        List<List<String>> data = records.subList(1, Math.min(records.size(), maxRows + 1));
        String table = AgentArgs.require(args, "table");
        int inserted = AgentSql.run(context.getMonitor(), container, AgentArgs.getString(args, "database"), AgentSql.Mode.ATOMIC,
            (connection, runner) -> {
                AgentNames.QName name = AgentNames.resolve(connection, family, table);
                String quoted = name.quoted(dataSource);
                AgentValues.TableColumns columns = AgentValues.columns(connection, quoted);
                List<String> targetColumns = new ArrayList<>();
                for (String h : header) {
                    targetColumns.add(columns.column(h.trim()));
                }
                return insertBatch(connection, runner, dataSource, quoted, columns, targetColumns, data);
            });
        JsonObject json = new JsonObject();
        json.addProperty("inserted", inserted);
        json.addProperty("truncated", records.size() - 1 > maxRows);
        QueryTools.addTransactionState(container, json);
        return json;
    }

    private static int insertBatch(
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull DBPDataSource dataSource,
        @NotNull String quotedTable,
        @NotNull AgentValues.TableColumns columns,
        @NotNull List<String> targetColumns,
        @NotNull List<? extends List<?>> rows
    ) throws SQLException {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quotedTable).append(" (");
        for (int i = 0; i < targetColumns.size(); i++) {
            sql.append(i > 0 ? ", " : "").append(DBUtils.getQuotedIdentifier(dataSource, targetColumns.get(i)));
        }
        sql.append(") VALUES (").append("?, ".repeat(targetColumns.size() - 1)).append("?)");
        int inserted = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            runner.track(statement, true);
            try {
                int pending = 0;
                for (List<?> row : rows) {
                    if (runner.getMonitor().isCanceled()) {
                        throw new SQLException("Canceled");
                    }
                    for (int i = 0; i < targetColumns.size(); i++) {
                        Object value = i < row.size() ? row.get(i) : null;
                        if (value instanceof String s && s.isEmpty()) {
                            value = null;
                        }
                        AgentValues.bind(statement, i + 1, value, columns.type(targetColumns.get(i)));
                    }
                    statement.addBatch();
                    if (++pending == 500) {
                        inserted += sum(statement.executeBatch());
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    inserted += sum(statement.executeBatch());
                }
            } finally {
                runner.track(statement, false);
            }
        }
        return inserted;
    }

    private static int sum(@NotNull int[] counts) {
        int total = 0;
        for (int c : counts) {
            // SUCCESS_NO_INFO (-2): the row was inserted but the driver does not count
            total += c == Statement.SUCCESS_NO_INFO ? 1 : Math.max(c, 0);
        }
        return total;
    }

    // ------------------------------------------------------------------ copy

    @NotNull
    private static JsonElement copyTable(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer target = requireConnection(context);
        String sourceName = AgentArgs.getString(args, "sourceConnection");
        DBPDataSourceContainer source = sourceName == null ? target : AIToolConnections.find(sourceName);
        DBPDataSource sourceDs = AgentSql.connect(context.getMonitor(), source);
        DBPDataSource targetDs = AgentSql.connect(context.getMonitor(), target);
        DbFamily sourceFamily = DbFamily.of(source);
        DbFamily targetFamily = DbFamily.of(target);
        String sourceTable = AgentArgs.require(args, "table");
        String targetTable = AgentArgs.getString(args, "targetTable") == null ? sourceTable : AgentArgs.getString(args, "targetTable");
        boolean createTarget = AgentArgs.getBoolean(args, "createTarget", false);
        int maxRows = maxRows(args);
        List<String> columnNames = new ArrayList<>();
        List<String> createColumns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean[] truncated = new boolean[1];
        AgentSql.run(context.getMonitor(), source, AgentArgs.getString(args, "sourceDatabase"), AgentSql.Mode.READ_ONLY, (connection, runner) -> {
            String sql = "SELECT * FROM " + AgentNames.resolve(connection, sourceFamily, sourceTable).quoted(sourceDs);
            try (Statement statement = connection.createStatement()) {
                statement.setMaxRows(maxRows + 1);
                statement.setFetchSize(1000);
                runner.track(statement, true);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        columnNames.add(meta.getColumnName(i));
                        createColumns.add(DBUtils.getQuotedIdentifier(targetDs, meta.getColumnName(i)) + " " +
                            targetType(meta, i, sourceFamily, targetFamily) +
                            (meta.isNullable(i) == ResultSetMetaData.columnNoNulls ? " NOT NULL" : ""));
                    }
                    while (rs.next()) {
                        if (rows.size() >= maxRows) {
                            truncated[0] = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>(columnNames.size());
                        for (int i = 1; i <= columnNames.size(); i++) {
                            Object value = rs.getObject(i);
                            row.add(value == null ? null : value instanceof Number || value instanceof Boolean ? value.toString() : text(value));
                        }
                        rows.add(row);
                    }
                } finally {
                    runner.track(statement, false);
                }
            }
            return null;
        });
        int inserted = AgentSql.run(context.getMonitor(), target, AgentArgs.getString(args, "database"), AgentSql.Mode.ATOMIC, (connection, runner) -> {
            AgentNames.QName name = AgentNames.resolve(connection, targetFamily, targetTable);
            String quoted = name.quoted(targetDs);
            if (createTarget) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + quoted + " (\n    " + String.join(",\n    ", createColumns) + "\n)");
                }
            }
            AgentValues.TableColumns columns = AgentValues.columns(connection, quoted);
            List<String> targetColumns = new ArrayList<>();
            for (String column : columnNames) {
                targetColumns.add(columns.column(column));
            }
            return rows.isEmpty() ? 0 : insertBatch(connection, runner, targetDs, quoted, columns, targetColumns, rows);
        });
        JsonObject json = new JsonObject();
        json.addProperty("source", source.getName() + ": " + sourceTable);
        json.addProperty("target", target.getName() + ": " + targetTable);
        json.addProperty("created", createTarget);
        json.addProperty("copied", inserted);
        json.addProperty("truncated", truncated[0]);
        QueryTools.addTransactionState(target, json);
        return json;
    }

    // Same family: the source type name with its length; otherwise a portable type for the JDBC type
    @NotNull
    private static String targetType(@NotNull ResultSetMetaData meta, int column, @NotNull DbFamily source, @NotNull DbFamily target)
        throws SQLException {
        int type = meta.getColumnType(column);
        int precision = meta.getPrecision(column);
        int scale = meta.getScale(column);
        if (source == target) {
            String name = meta.getColumnTypeName(column);
            boolean sized = type == Types.VARCHAR || type == Types.NVARCHAR || type == Types.CHAR || type == Types.NCHAR ||
                type == Types.VARBINARY || type == Types.BINARY;
            if (sized && precision > 0 && precision < 100_000) {
                return name + "(" + precision + ")";
            }
            if ((type == Types.DECIMAL || type == Types.NUMERIC) && precision > 0) {
                return name + "(" + precision + "," + scale + ")";
            }
            return name.replace(" identity", "");
        }
        boolean oracle = target == DbFamily.ORACLE;
        boolean sqlServer = target == DbFamily.SQLSERVER;
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT -> oracle ? "NUMBER(5)" : "SMALLINT";
            case Types.INTEGER -> oracle ? "NUMBER(10)" : "INTEGER";
            case Types.BIGINT -> oracle ? "NUMBER(19)" : "BIGINT";
            case Types.DECIMAL, Types.NUMERIC -> (oracle ? "NUMBER" : "DECIMAL") + (precision > 0 && precision <= 38 ? "(" + precision + "," + scale + ")" : "");
            case Types.REAL, Types.FLOAT -> oracle ? "BINARY_FLOAT" : "REAL";
            case Types.DOUBLE -> oracle ? "BINARY_DOUBLE" : sqlServer ? "FLOAT" : "DOUBLE PRECISION";
            case Types.BIT, Types.BOOLEAN -> sqlServer ? "BIT" : oracle ? "NUMBER(1)" : "BOOLEAN";
            case Types.DATE -> "DATE";
            case Types.TIME -> oracle ? "VARCHAR2(20)" : "TIME";
            case Types.TIMESTAMP -> sqlServer ? "DATETIME2" : oracle ? "TIMESTAMP" : target == DbFamily.POSTGRESQL ? "TIMESTAMP" : "DATETIME";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                sqlServer ? "VARBINARY(MAX)" : oracle ? "BLOB" : target == DbFamily.POSTGRESQL ? "BYTEA" : "LONGBLOB";
            case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR -> precision > 0 && precision <= 4000
                ? (sqlServer ? "NVARCHAR(" : oracle ? "VARCHAR2(" : "VARCHAR(") + precision + ")"
                : sqlServer ? "NVARCHAR(MAX)" : oracle ? "CLOB" : "TEXT";
            default -> sqlServer ? "NVARCHAR(MAX)" : oracle ? "CLOB" : "TEXT";
        };
    }

    // ------------------------------------------------------------------ helpers

    @NotNull
    private static Path outputFile(@NotNull Map<String, Object> args) throws AIToolArgumentException {
        Path file = Path.of(AgentArgs.require(args, "file"));
        if (!file.isAbsolute()) {
            throw new AIToolArgumentException("Argument 'file' must be an absolute path");
        }
        if (Files.exists(file) && !AgentArgs.getBoolean(args, "overwrite", false)) {
            throw new AIToolArgumentException("File " + file + " exists; pass overwrite=true to replace it");
        }
        if (file.getParent() == null || !Files.isDirectory(file.getParent())) {
            throw new AIToolArgumentException("Folder " + file.getParent() + " does not exist");
        }
        return file;
    }

    private static int maxRows(@NotNull Map<String, Object> args) {
        return Math.max(1, Math.min(MAX_ROWS, AgentArgs.getInt(args, "maxRows", DEFAULT_MAX_ROWS)));
    }

    @Nullable
    @Override
    public String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) {
        return switch (context.getTool().getId()) {
            case "exportData" -> "Export " + (arguments.get("sql") != null ? arguments.get("sql") : arguments.get("table")) +
                " to " + arguments.get("file");
            case "importCsv" -> "Import " + arguments.get("file") + " into " + arguments.get("table");
            case "copyTable" -> "Copy " + arguments.get("table") + " from " +
                (arguments.get("sourceConnection") == null ? "the same connection" : arguments.get("sourceConnection")) +
                " into " + (arguments.get("targetTable") == null ? arguments.get("table") : arguments.get("targetTable")) +
                (AgentArgs.getBoolean(arguments, "createTarget", false) ? " (creates the table)" : "");
            default -> null;
        };
    }

    /**
     * Minimal RFC 4180 reader: quoted fields with doubled quotes and line breaks.
     */
    static final class CsvReader {
        private CsvReader() {
        }

        @NotNull
        static List<List<String>> read(@NotNull BufferedReader reader, char delimiter, int maxRecords) throws IOException {
            List<List<String>> records = new ArrayList<>();
            List<String> record = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean fieldStarted = false;
            int c;
            while ((c = reader.read()) != -1) {
                char ch = (char) c;
                if (quoted) {
                    if (ch == '"') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            quoted = false;
                            if (next != -1) {
                                reader.reset();
                            }
                        }
                    } else {
                        field.append(ch);
                    }
                } else if (ch == '"' && field.isEmpty()) {
                    quoted = true;
                    fieldStarted = true;
                } else if (ch == delimiter) {
                    record.add(field.toString());
                    field.setLength(0);
                    fieldStarted = true;
                } else if (ch == '\n' || ch == '\r') {
                    if (ch == '\r') {
                        reader.mark(1);
                        if (reader.read() != '\n') {
                            reader.reset();
                        }
                    }
                    if (fieldStarted || !field.isEmpty() || !record.isEmpty()) {
                        record.add(field.toString());
                        records.add(record);
                        if (records.size() >= maxRecords) {
                            return records;
                        }
                    }
                    record = new ArrayList<>();
                    field.setLength(0);
                    fieldStarted = false;
                } else {
                    field.append(ch);
                    fieldStarted = true;
                }
            }
            if (fieldStarted || !field.isEmpty() || !record.isEmpty()) {
                record.add(field.toString());
                records.add(record);
            }
            return records;
        }
    }
}
