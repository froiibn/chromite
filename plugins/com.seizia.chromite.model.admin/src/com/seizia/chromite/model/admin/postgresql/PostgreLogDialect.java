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
package com.seizia.chromite.model.admin.postgresql;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.logs.LogMessages;
import com.seizia.chromite.model.admin.logs.ServerLogDialect;
import com.seizia.chromite.model.admin.logs.ServerLogEntry;
import com.seizia.chromite.model.admin.logs.ServerLogPage;
import com.seizia.chromite.model.admin.logs.ServerLogQuery;
import com.seizia.chromite.model.admin.logs.ServerLogSource;
import com.seizia.chromite.model.admin.space.SpaceFormat;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 서버 로그: 로그 수집기(logging_collector)가 쓴 파일을 {@code pg_ls_logdir}·{@code pg_read_binary_file}로 읽는다.
 * 형식은 log_destination의 stderr(글), csvlog, jsonlog(15+)다.
 *
 * 설계:
 * <ul>
 *     <li>권한을 먼저 본다: 세 함수({@code pg_ls_logdir}, {@code pg_current_logfile}, {@code pg_read_binary_file})의 EXECUTE 권한이 있어야 한다.
 *     앞의 둘은 pg_monitor에 EXECUTE가 있지만 셋째는 superuser만 쓸 수 있고, 다른 로그인에는 따로 GRANT EXECUTE해야 한다(pg_read_server_files는
 *     EXECUTE를 주지 않고 데이터 디렉터리 밖 경로만 허용한다, 2026-11-07 PostgreSQL 17의 proacl로 확인). 권한 함수({@code has_function_privilege})로 확인해
 *     실패하는 문장을 보내지 않는다(수동 커밋 연결에서 실패한 문장은 트랜잭션을 깬다). 로그 수집기가 꺼져 있으면 로그는 서버의 표준 오류로만
 *     나가 SQL로 읽을 수 없으므로 그렇게 안내한다.</li>
 *     <li>로그 파일은 클 수 있어 끝에서부터 조각(256 KB)씩 거꾸로 읽는다. 조각의 첫 항목은 앞 조각에 걸쳐 있을 수 있어 버리고, 다음 쪽은 그
 *     항목의 시작 바이트(커서)에서 끝나는 조각부터 읽는다. 한 쪽에 읽는 양은 8 MB로 막는다(조건에 맞는 항목이 드물 때). 바이트로 읽고
 *     UTF-8로 풀어 커서가 문자 경계와 상관없게 한다.</li>
 *     <li>항목의 시작: 글 형식은 공백으로 시작하지 않는 줄(이어지는 줄은 탭으로 시작한다), CSV는 시각으로 시작하는 줄, JSON은 줄 하나다.
 *     글 형식의 DETAIL·HINT·STATEMENT 같은 줄은 앞 항목의 상세로 붙인다. 수준과 시각은 기본 log_line_prefix('%m [%p] ')의 모양으로
 *     찾고, 다른 접두어여도 "수준:  " 모양이면 수준을 찾는다.</li>
 *     <li>시각은 로그에 적힌 대로(log_timezone) 보인다. "최근 N" 범위는 서버의 now()를 log_timezone으로 바꾼 시각과 로그 시각을 비교한다
 *     (로그의 시간대 약어를 클라이언트에서 풀지 않는다: KST 같은 약어는 Java가 모를 수 있다). 파일은 시간 순서로 쓰이므로 범위보다 오래된
 *     항목을 만나면 더 읽지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public class PostgreLogDialect extends ServerLogDialect {

    private static final Log log = Log.getLog(PostgreLogDialect.class);

    private static final String STDERR = "stderr"; //$NON-NLS-1$
    private static final String CSVLOG = "csvlog"; //$NON-NLS-1$
    private static final String JSONLOG = "jsonlog"; //$NON-NLS-1$
    private static final int CHUNK_BYTES = 256 * 1024;
    private static final int MAX_BYTES_PER_PAGE = 8 * 1024 * 1024;

    // Level filter ids: one level, or ERRORS for ERROR, FATAL and PANIC
    private static final String ERRORS = "errors"; //$NON-NLS-1$
    private static final Set<String> ERROR_LEVELS = Set.of("ERROR", "FATAL", "PANIC"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final String[] LEVELS = {"PANIC", "FATAL", "ERROR", "WARNING", "LOG", "NOTICE", "INFO", "DEBUG"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
    // Lines of the text format that belong to the entry before them
    private static final Set<String> CONTINUATION_LEVELS = Set.of("DETAIL", "HINT", "CONTEXT", "STATEMENT", "QUERY", "LOCATION"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private static final Pattern TIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)(?: ([A-Za-z][A-Za-z0-9_/+\\-]*|[+\\-]\\d{2}(?::?\\d{2})?))?"); //$NON-NLS-1$
    private static final Pattern LEVEL = Pattern.compile("\\b(DEBUG[1-5]?|LOG|INFO|NOTICE|WARNING|ERROR|FATAL|PANIC|DETAIL|HINT|CONTEXT|STATEMENT|QUERY|LOCATION):  "); //$NON-NLS-1$
    private static final Pattern PID = Pattern.compile("\\[(\\d+)\\]"); //$NON-NLS-1$
    private static final DateTimeFormatter LOG_TIME = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss") //$NON-NLS-1$
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
        .toFormatter();

    @NotNull
    private final PostgreDataSource dataSource;

    public PostgreLogDialect(@NotNull PostgreDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public List<ServerLogSource> listSources(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException {
        String label = LogMessages.log_postgres_server_log;
        // pg_ls_logdir and pg_current_logfile: PostgreSQL 10
        if (!dataSource.isServerVersionAtLeast(10, 0)) {
            return List.of(ServerLogSource.unavailable(STDERR, label, LogMessages.log_postgres_version));
        }
        try {
            String collector = AdminSql.queryString(session, "SELECT current_setting('logging_collector')"); //$NON-NLS-1$
            String destination = CommonUtils.notEmpty(AdminSql.queryString(session, "SELECT current_setting('log_destination')")); //$NON-NLS-1$
            if (!"on".equalsIgnoreCase(collector)) { //$NON-NLS-1$
                return List.of(ServerLogSource.unavailable(STDERR, label, LogMessages.log_postgres_no_collector));
            }
            String allowed = AdminSql.queryString(session, "SELECT has_function_privilege('pg_ls_logdir()', 'EXECUTE') " //$NON-NLS-1$
                + "AND has_function_privilege('pg_current_logfile(text)', 'EXECUTE') " //$NON-NLS-1$
                + "AND has_function_privilege('pg_read_binary_file(text,bigint,bigint,boolean)', 'EXECUTE')"); //$NON-NLS-1$
            if (!"t".equals(allowed) && !"true".equalsIgnoreCase(allowed)) { //$NON-NLS-1$ //$NON-NLS-2$
                return List.of(ServerLogSource.unavailable(STDERR, label, LogMessages.log_postgres_no_permission));
            }
            List<String> formats = new ArrayList<>();
            for (String format : new String[]{CSVLOG, JSONLOG, STDERR}) {
                for (String item : destination.split(",")) { //$NON-NLS-1$
                    if (item.trim().equalsIgnoreCase(format)) {
                        formats.add(format);
                    }
                }
            }
            if (formats.isEmpty()) {
                return List.of(ServerLogSource.unavailable(STDERR, label, NLS.bind(LogMessages.log_postgres_no_file_destination, destination)));
            }
            List<String[]> files = new ArrayList<>();
            // The modification time in the time zone the log entries are written in (log_timezone), not the client's
            AdminSql.query(session, "SELECT name, size, to_char(modification AT TIME ZONE current_setting('log_timezone'), " //$NON-NLS-1$
                    + "'YYYY-MM-DD HH24:MI:SS') FROM pg_ls_logdir() ORDER BY modification DESC, name DESC", //$NON-NLS-1$
                rs -> files.add(new String[]{rs.getString(1), SpaceFormat.bytes(rs.getLong(2)), CommonUtils.notEmpty(rs.getString(3))}));
            List<ServerLogSource.Option> levels = new ArrayList<>();
            levels.add(new ServerLogSource.Option(ERRORS, LogMessages.log_postgres_level_errors));
            for (String level : LEVELS) {
                levels.add(new ServerLogSource.Option(level, level));
            }
            List<ServerLogSource> sources = new ArrayList<>();
            for (String format : formats) {
                String current = AdminSql.queryString(session, "SELECT pg_current_logfile(?)", format); //$NON-NLS-1$
                // pg_current_logfile gives the path the server reads files with (log_directory/name)
                String directory = current != null && current.contains("/") ? current.substring(0, current.lastIndexOf('/') + 1) : null; //$NON-NLS-1$
                if (directory == null) {
                    String setting = AdminSql.queryString(session, "SELECT setting FROM pg_settings WHERE name = 'log_directory'"); //$NON-NLS-1$
                    directory = setting == null ? "log/" : setting + "/"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                List<ServerLogSource.Option> archives = new ArrayList<>();
                for (String[] file : files) {
                    if (format.equals(formatOf(file[0]))) {
                        String path = directory + file[0];
                        String fileLabel = NLS.bind(LogMessages.log_postgres_file, new Object[]{file[0], file[1], file[2]});
                        if (path.equals(current)) {
                            archives.add(0, new ServerLogSource.Option(path, NLS.bind(LogMessages.log_archive_current_file, fileLabel)));
                        } else {
                            archives.add(new ServerLogSource.Option(path, fileLabel));
                        }
                    }
                }
                String formatLabel = switch (format) {
                    case CSVLOG -> LogMessages.log_postgres_format_csv;
                    case JSONLOG -> LogMessages.log_postgres_format_json;
                    default -> LogMessages.log_postgres_format_text;
                };
                sources.add(new ServerLogSource(format, NLS.bind(LogMessages.log_postgres_server_log_format, formatLabel),
                    archives.isEmpty() ? LogMessages.log_postgres_no_files : null, archives, levels, LogMessages.log_col_process));
            }
            return sources;
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @NotNull
    private static String formatOf(@NotNull String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") ? CSVLOG : lower.endsWith(".json") ? JSONLOG : STDERR; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An entry with the byte where it starts in the file (the cursor of the next page).
     */
    private record Parsed(long offset, @NotNull ServerLogEntry entry) {
    }

    @NotNull
    @Override
    public ServerLogPage read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull ServerLogQuery query)
        throws DBException {
        String path = query.archiveId();
        if (path == null) {
            return new ServerLogPage(List.of(), null);
        }
        String format = query.sourceId();
        try {
            String name = path.substring(path.lastIndexOf('/') + 1);
            String size = AdminSql.queryString(session, "SELECT size FROM pg_ls_logdir() WHERE name = ?", name); //$NON-NLS-1$
            if (size == null) {
                // Removed by the log rotation since the list was read
                return new ServerLogPage(List.of(), null);
            }
            // "Last N": the server's now in the time zone the log is written in (log_timezone), compared with the log times
            LocalDateTime since = null;
            if (query.last() != null) {
                String sinceText = AdminSql.queryString(session, "SELECT to_char((now() - make_interval(secs => ?::float8)) " //$NON-NLS-1$
                    + "AT TIME ZONE current_setting('log_timezone'), 'YYYY-MM-DD HH24:MI:SS')", query.last().toSeconds()); //$NON-NLS-1$
                since = sinceText == null ? null : parseTime(sinceText);
            }
            if (query.from() != null && (since == null || query.from().isAfter(since))) {
                since = query.from();
            }
            long end = query.cursor() != null ? Long.parseLong(query.cursor()) : Long.parseLong(size);
            String text = query.searchText();
            List<ServerLogEntry> entries = new ArrayList<>();
            long bytesRead = 0;
            int chunk = CHUNK_BYTES;
            boolean done = false;
            while (end > 0 && !done && bytesRead < MAX_BYTES_PER_PAGE && !monitor.isCanceled()) {
                long start = Math.max(0, end - chunk);
                byte[] bytes = readBytes(session, path, start, (int) (end - start));
                bytesRead += bytes.length;
                int first = start == 0 ? 0 : firstEntryStart(bytes, format);
                if (first < 0) {
                    if (chunk < MAX_BYTES_PER_PAGE) {
                        // One entry longer than the chunk: read a larger one
                        chunk *= 4;
                        continue;
                    }
                    first = 0;
                }
                List<Parsed> parsed = parse(bytes, first, start, format);
                long chunkEnd = end;
                // The oldest entry of this chunk starts here; older entries are in the next chunk
                end = start + first;
                for (int i = parsed.size() - 1; i >= 0; i--) {
                    ServerLogEntry entry = parsed.get(i).entry();
                    if (since != null && entry.time() != null && entry.time().isBefore(since)) {
                        // The file is written in time order: nothing older matches
                        end = 0;
                        done = true;
                        break;
                    }
                    if (entries.size() >= query.limit()) {
                        // Page full: the next page starts with this entry, which ends where the newer one starts
                        end = i + 1 < parsed.size() ? parsed.get(i + 1).offset() : chunkEnd;
                        done = true;
                        break;
                    }
                    if (query.to() != null && entry.time() != null && entry.time().isAfter(query.to())) {
                        continue;
                    }
                    if (matchesLevel(entry.level(), query.levelId()) && containsText(entry.message(), entry.detail(), text)) {
                        entries.add(entry);
                    }
                }
                chunk = CHUNK_BYTES;
            }
            return new ServerLogPage(entries, end > 0 ? String.valueOf(end) : null);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @NotNull
    private static byte[] readBytes(@NotNull JDBCSession session, @NotNull String path, long offset, int length) throws SQLException {
        List<byte[]> result = new ArrayList<>();
        AdminSql.query(session, "SELECT pg_read_binary_file(?, ?, ?, true)", rs -> result.add(rs.getBytes(1)), path, offset, (long) length); //$NON-NLS-1$
        return result.isEmpty() || result.get(0) == null ? new byte[0] : result.get(0);
    }

    private static boolean matchesLevel(@Nullable String level, @Nullable String levelId) {
        if (levelId == null) {
            return true;
        }
        if (level == null) {
            return false;
        }
        if (ERRORS.equals(levelId)) {
            return ERROR_LEVELS.contains(level);
        }
        // DEBUG covers DEBUG1..DEBUG5
        return level.equals(levelId) || level.startsWith(levelId) && "DEBUG".equals(levelId); //$NON-NLS-1$
    }

    /**
     * Byte index of the first entry that starts in the chunk (after a line break), or -1.
     */
    private static int firstEntryStart(@NotNull byte[] bytes, @NotNull String format) {
        for (int i = 0; i < bytes.length - 1; i++) {
            if (bytes[i] == '\n' && isEntryStart(bytes, i + 1, format)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean isEntryStart(@NotNull byte[] bytes, int index, @NotNull String format) {
        if (index >= bytes.length) {
            return false;
        }
        byte b = bytes[index];
        return switch (format) {
            case JSONLOG -> b == '{';
            // A record starts with log_time ("2026-11-07 ...")
            case CSVLOG -> index + 4 < bytes.length && Character.isDigit(b) && Character.isDigit(bytes[index + 1])
                && Character.isDigit(bytes[index + 2]) && Character.isDigit(bytes[index + 3]) && bytes[index + 4] == '-';
            // Continuation lines of the text format start with a tab
            default -> b != '\t' && b != ' ' && b != '\n' && b != '\r';
        };
    }

    /**
     * Entries of a chunk from a known entry start, oldest first.
     */
    @NotNull
    private static List<Parsed> parse(@NotNull byte[] bytes, int first, long chunkStart, @NotNull String format) {
        List<Parsed> result = new ArrayList<>();
        int entryStart = first;
        for (int i = first + 1; i <= bytes.length; i++) {
            boolean boundary = i == bytes.length || (bytes[i - 1] == '\n' && isEntryStart(bytes, i, format));
            if (!boundary) {
                continue;
            }
            if (i > entryStart) {
                String text = new String(bytes, entryStart, i - entryStart, StandardCharsets.UTF_8);
                Parsed parsed = parseEntry(text, chunkStart + entryStart, format);
                if (parsed != null) {
                    // Text format: DETAIL, HINT, STATEMENT lines go to the entry before them
                    if (STDERR.equals(format) && !result.isEmpty() && parsed.entry().level() != null
                        && CONTINUATION_LEVELS.contains(parsed.entry().level())) {
                        Parsed previous = result.remove(result.size() - 1);
                        ServerLogEntry entry = previous.entry();
                        String detail = (entry.detail() == null ? "" : entry.detail() + "\n") + parsed.entry().level() + ": " + parsed.entry().message(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        result.add(new Parsed(previous.offset(), new ServerLogEntry(entry.time(), entry.level(), entry.source(), entry.message(), detail)));
                    } else {
                        result.add(parsed);
                    }
                }
            }
            entryStart = i;
        }
        return result;
    }

    @Nullable
    private static Parsed parseEntry(@NotNull String text, long offset, @NotNull String format) {
        String trimmed = text.stripTrailing();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return switch (format) {
                case JSONLOG -> parseJson(trimmed, offset);
                case CSVLOG -> parseCsv(trimmed, offset);
                default -> parseText(trimmed, offset);
            };
        } catch (RuntimeException e) {
            // A damaged or cut entry: show it as it is
            log.debug("Error parsing a log entry", e); //$NON-NLS-1$
            return new Parsed(offset, new ServerLogEntry(null, null, null, trimmed, null));
        }
    }

    @NotNull
    private static Parsed parseText(@NotNull String text, long offset) {
        Matcher timeMatcher = TIME.matcher(text);
        LocalDateTime time = timeMatcher.find() ? parseTime(timeMatcher.group(1)) : null;
        String level = null;
        String message = text;
        Matcher levelMatcher = LEVEL.matcher(text);
        if (levelMatcher.find()) {
            level = levelMatcher.group(1);
            message = text.substring(levelMatcher.end());
        }
        // The process id of the prefix ("[123]" of the default '%m [%p] '), before the level
        String source = null;
        Matcher pid = PID.matcher(level == null ? text : text.substring(0, levelMatcher.start()));
        if (pid.find()) {
            source = pid.group(1);
        }
        // Continuation lines start with a tab
        message = message.replace("\n\t", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        return new Parsed(offset, new ServerLogEntry(time, normalizeLevel(level), source, message, null));
    }

    @NotNull
    private static Parsed parseCsv(@NotNull String text, long offset) {
        List<String> fields = splitCsv(text);
        // log_time, user_name, database_name, process_id, connection_from, session_id, session_line_num, command_tag,
        // session_start_time, virtual_transaction_id, transaction_id, error_severity, sql_state_code, message, detail, hint,
        // internal_query, internal_query_pos, context, query, query_pos, location, application_name, backend_type, ...
        LocalDateTime time = parseLeadingTime(field(fields, 0));
        String source = source(field(fields, 1), field(fields, 2), field(fields, 3), field(fields, 22));
        StringBuilder detail = new StringBuilder();
        appendDetail(detail, "SQLSTATE", field(fields, 12)); //$NON-NLS-1$
        appendDetail(detail, "DETAIL", field(fields, 14)); //$NON-NLS-1$
        appendDetail(detail, "HINT", field(fields, 15)); //$NON-NLS-1$
        appendDetail(detail, "CONTEXT", field(fields, 18)); //$NON-NLS-1$
        appendDetail(detail, "STATEMENT", field(fields, 19)); //$NON-NLS-1$
        appendDetail(detail, "LOCATION", field(fields, 21)); //$NON-NLS-1$
        appendDetail(detail, "BACKEND", field(fields, 23)); //$NON-NLS-1$
        return new Parsed(offset, new ServerLogEntry(time, normalizeLevel(field(fields, 11)), source,
            CommonUtils.notEmpty(field(fields, 13)), detail.isEmpty() ? null : detail.toString()));
    }

    @NotNull
    private static Parsed parseJson(@NotNull String text, long offset) {
        JsonObject object = JsonParser.parseString(text).getAsJsonObject();
        LocalDateTime time = parseLeadingTime(string(object, "timestamp")); //$NON-NLS-1$
        String source = source(string(object, "user"), string(object, "dbname"), string(object, "pid"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            string(object, "application_name")); //$NON-NLS-1$
        StringBuilder detail = new StringBuilder();
        appendDetail(detail, "SQLSTATE", string(object, "state_code")); //$NON-NLS-1$ //$NON-NLS-2$
        appendDetail(detail, "DETAIL", string(object, "detail")); //$NON-NLS-1$ //$NON-NLS-2$
        appendDetail(detail, "HINT", string(object, "hint")); //$NON-NLS-1$ //$NON-NLS-2$
        appendDetail(detail, "CONTEXT", string(object, "context")); //$NON-NLS-1$ //$NON-NLS-2$
        appendDetail(detail, "STATEMENT", string(object, "statement")); //$NON-NLS-1$ //$NON-NLS-2$
        appendDetail(detail, "BACKEND", string(object, "backend_type")); //$NON-NLS-1$ //$NON-NLS-2$
        return new Parsed(offset, new ServerLogEntry(time, normalizeLevel(string(object, "error_severity")), source, //$NON-NLS-1$
            CommonUtils.notEmpty(string(object, "message")), detail.isEmpty() ? null : detail.toString())); //$NON-NLS-1$
    }

    @Nullable
    private static String string(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    /**
     * "user@database (pid 123, application)" from the parts the entry has.
     */
    @Nullable
    private static String source(@Nullable String user, @Nullable String database, @Nullable String pid, @Nullable String application) {
        StringBuilder text = new StringBuilder();
        if (!CommonUtils.isEmpty(user)) {
            text.append(user);
        }
        if (!CommonUtils.isEmpty(database)) {
            text.append('@').append(database);
        }
        if (!CommonUtils.isEmpty(pid)) {
            text.append(text.isEmpty() ? "" : " ").append('[').append(pid).append(']'); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!CommonUtils.isEmpty(application)) {
            text.append(text.isEmpty() ? "" : " ").append(application); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static void appendDetail(@NotNull StringBuilder detail, @NotNull String name, @Nullable String value) {
        if (!CommonUtils.isEmpty(value)) {
            if (!detail.isEmpty()) {
                detail.append('\n');
            }
            detail.append(name).append(": ").append(value); //$NON-NLS-1$
        }
    }

    @Nullable
    private static String field(@NotNull List<String> fields, int index) {
        if (index >= fields.size()) {
            return null;
        }
        String value = fields.get(index);
        return value.isEmpty() ? null : value;
    }

    /**
     * Fields of a CSV record (RFC 4180: quotes around fields, doubled quotes inside, line breaks inside quotes).
     */
    @NotNull
    private static List<String> splitCsv(@NotNull String record) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    @Nullable
    private static String normalizeLevel(@Nullable String level) {
        return level == null ? null : level.toUpperCase(Locale.ROOT);
    }

    @Nullable
    private static LocalDateTime parseTime(@NotNull String text) {
        try {
            return LocalDateTime.parse(text, LOG_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The time at the start of a log field ("2026-11-07 05:30:00.123 UTC" without its zone), or null.
     */
    @Nullable
    private static LocalDateTime parseLeadingTime(@Nullable String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = TIME.matcher(text);
        return matcher.find() ? parseTime(matcher.group(1)) : null;
    }
}
