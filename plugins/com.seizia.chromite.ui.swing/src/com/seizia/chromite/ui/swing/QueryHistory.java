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
package com.seizia.chromite.ui.swing;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.PrefUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Query history: every statement executed in a query window, kept in the workspace across restarts.
 *
 * 설계:
 * <ul>
 *     <li>DBeaver의 쿼리 관리자(QM, {@code QMUtils.getEventBrowser})는 CE에서 이번 실행의 메모리 기록뿐이고({@code QMRegistryImpl}의 기본 이벤트 브라우저),
 *     파일 기록({@code QMLogFileWriter})은 메타데이터 읽기 같은 내부 쿼리까지 섞인 글자 로그라 다시 읽을 수 없다. 그래서 편집기가 실행한
 *     문장만 Chromite 저장소에 남긴다. 편집기는 엔진의 문장 끝 이벤트({@code SQLQueryListener.onEndQuery})에서 {@link #add}를 부른다.</li>
 *     <li>저장: 작업 공간 {@code .metadata/chromite-query-history.jsonl}에 기록마다 JSON 한 줄을 덧붙인다. 덧붙이기라 실행할 때마다 파일 전체를
 *     다시 쓰지 않고, 앱이 비정상 종료되어도 그때까지의 기록이 남는다. 처음 읽을 때 보관 기간({@link #getRetentionDays}, Tools &gt; Options)이 지난 기록과
 *     깨진 줄을 빼고 파일을 다시 쓴다. 기록 수는 {@value #MAX_ENTRIES}개까지다(오래된 것부터 버림).</li>
 *     <li>연결은 ID와 이름을 함께 남긴다. 다시 열 때는 ID로 찾고, 지웠거나 다른 작업 공간이면 이름만 보인다.</li>
 * </ul>
 * 메서드는 어느 스레드에서 불러도 된다(파일 쓰기는 이 클래스의 잠금 안).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 */
final class QueryHistory {

    private static final Log log = Log.getLog(QueryHistory.class);

    static final String PREF_RETENTION_DAYS = "sqlEditor.history.retentionDays"; //$NON-NLS-1$
    static final int DEFAULT_RETENTION_DAYS = 30;
    static final int MAX_RETENTION_DAYS = 3650;
    private static final int MAX_ENTRIES = 20000;
    private static final String FILE_NAME = "chromite-query-history.jsonl"; //$NON-NLS-1$

    enum Status {
        SUCCESS, ERROR, CANCELED
    }

    /**
     * One executed statement.
     *
     * @param time         start time (epoch milliseconds)
     * @param connectionId data source container id
     * @param database     default catalog/schema when it ran, or null
     * @param durationMs   execution time
     * @param rows         rows fetched or affected, -1 when unknown
     * @param error        error message of a failed statement
     */
    record Entry(
        long time,
        @Nullable String connectionId,
        @Nullable String connectionName,
        @Nullable String database,
        @NotNull String query,
        long durationMs,
        long rows,
        @NotNull Status status,
        @Nullable String error
    ) {
    }

    private static final Gson GSON = new Gson();
    private static final Object lock = new Object();
    // Oldest first; null until the file was read
    @Nullable
    private static List<Entry> entries;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile boolean defaultsSet;

    private QueryHistory() {
    }

    /**
     * Records a statement (nothing when the retention is 0 days).
     */
    static void add(@NotNull Entry entry) {
        if (getRetentionDays() <= 0) {
            return;
        }
        synchronized (lock) {
            List<Entry> list = load();
            list.add(entry);
            try {
                Path file = getFile();
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(entry) + "\n", StandardCharsets.UTF_8, //$NON-NLS-1$
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("Can't write the query history", e);
            }
            if (list.size() > MAX_ENTRIES * 11 / 10) {
                // Compact now and then rather than on every statement
                list.subList(0, list.size() - MAX_ENTRIES).clear();
                rewrite(list);
            }
        }
        listeners.forEach(Runnable::run);
    }

    /**
     * All entries, newest first.
     */
    @NotNull
    static List<Entry> getEntries() {
        synchronized (lock) {
            List<Entry> result = new ArrayList<>(load());
            return result.reversed();
        }
    }

    /**
     * Removes every entry (Query History window: Clear).
     */
    static void clear() {
        synchronized (lock) {
            List<Entry> list = load();
            list.clear();
            rewrite(list);
        }
        listeners.forEach(Runnable::run);
    }

    /**
     * Called (in any thread) after the history changed.
     */
    static void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    static void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    static int getRetentionDays() {
        return Math.max(0, getPreferences().getInt(PREF_RETENTION_DAYS));
    }

    static void setRetentionDays(int days) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_RETENTION_DAYS, Math.clamp(days, 0, MAX_RETENTION_DAYS));
        PrefUtils.savePreferenceStore(preferences);
        synchronized (lock) {
            if (entries != null && prune(entries)) {
                rewrite(entries);
            }
        }
        listeners.forEach(Runnable::run);
    }

    @NotNull
    private static List<Entry> load() {
        if (entries != null) {
            return entries;
        }
        List<Entry> list = new ArrayList<>();
        Path file = getFile();
        boolean dirty = false;
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        Entry entry = GSON.fromJson(line, Entry.class);
                        if (entry != null && entry.query() != null && entry.status() != null) {
                            list.add(entry);
                            continue;
                        }
                    } catch (JsonParseException e) {
                        // A line cut short by a crash; dropped below
                    }
                    dirty = true;
                }
            } catch (IOException e) {
                log.warn("Can't read the query history", e);
            }
        }
        if (list.size() > MAX_ENTRIES) {
            list.subList(0, list.size() - MAX_ENTRIES).clear();
            dirty = true;
        }
        if (prune(list) || dirty) {
            rewrite(list);
        }
        entries = list;
        return list;
    }

    /**
     * Drops the entries older than the retention period.
     *
     * @return true if something was dropped
     */
    private static boolean prune(@NotNull List<Entry> list) {
        int days = getRetentionDays();
        long oldest = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        return list.removeIf(entry -> days <= 0 || entry.time() < oldest);
    }

    private static void rewrite(@NotNull List<Entry> list) {
        Path file = getFile();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp"); //$NON-NLS-1$
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                for (Entry entry : list) {
                    writer.write(GSON.toJson(entry));
                    writer.write('\n');
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Can't write the query history", e);
        }
    }

    @NotNull
    private static Path getFile() {
        return GeneralUtils.getMetadataFolder().resolve(FILE_NAME);
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        if (!defaultsSet) {
            preferences.setDefault(PREF_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
            defaultsSet = true;
        }
        return preferences;
    }
}
