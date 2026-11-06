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

import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.PrefUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 예약 실행 결과 기록: 쿼리마다 실행 시각, 서버, 걸린 시간, 행 수, 상태·오류, 앞 몇 행.
 *
 * 설계:
 * <ul>
 *     <li>기록은 이 PC의 것이라 쿼리 파일(공유될 수 있음)이 아니라 작업 공간 메타데이터 폴더
 *     ({@code .metadata/chromite-query-library/history/<쿼리 ID>.tsv})에 쓴다. 한 실행이 한 줄(탭으로 나눈 칸, 칸 안의 탭·줄바꿈·역슬래시는
 *     {@code \t}, {@code \n}, {@code \\})이라 덧붙이기만 하면 되고, 파일을 열어 읽을 수도 있다.</li>
 *     <li>앞 행 스냅숏은 열 이름 한 줄과 행들을 {@code \u001F}(칸), {@code \u001E}(행)으로 이어 한 칸에 넣는다.</li>
 *     <li>보존: 쿼리마다 최근 N개, M일 안의 것만 남긴다(Tools &gt; Options의 Query Library). 덧붙일 때 줄인다.</li>
 *     <li>여러 스레드(예약 실행 Job, 기록 대화상자)가 같은 파일을 쓰고 읽으므로 이 클래스의 메서드는 동기화한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library ③: 최초 작성 (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 */
final class QueryLibraryHistory {

    private static final Log log = Log.getLog(QueryLibraryHistory.class);

    static final String PREF_MAX_RUNS = "queryLibrary.history.maxRuns"; //$NON-NLS-1$
    static final String PREF_MAX_DAYS = "queryLibrary.history.maxDays"; //$NON-NLS-1$
    static final int DEFAULT_MAX_RUNS = 100;
    static final int DEFAULT_MAX_DAYS = 30;
    private static final char CELL = '\u001F';
    private static final char ROW = '\u001E';

    /**
     * Result of a run.
     */
    enum Status {
        OK, ALERT, ERROR, SKIPPED;

        /**
         * Text for people; the file keeps {@link #name()} so that histories read the same in every language.
         */
        @NotNull
        String getLabel() {
            return switch (this) {
                case OK -> ToolsMessages.history_status_ok;
                case ALERT -> ToolsMessages.history_status_alert;
                case ERROR -> ToolsMessages.history_status_error;
                case SKIPPED -> ToolsMessages.history_status_skipped;
            };
        }
    }

    /**
     * One run on one server.
     *
     * @param snapshot column names first, then the first rows (all as text)
     */
    record Run(
        @NotNull LocalDateTime time,
        @NotNull String server,
        long durationMillis,
        long rows,
        @NotNull Status status,
        @NotNull String message,
        @NotNull List<List<String>> snapshot
    ) {
    }

    private QueryLibraryHistory() {
    }

    static {
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        preferences.setDefault(PREF_MAX_RUNS, DEFAULT_MAX_RUNS);
        preferences.setDefault(PREF_MAX_DAYS, DEFAULT_MAX_DAYS);
    }

    static int getMaxRuns() {
        return Math.max(1, ChromiteSwingActivator.getInstance().getPreferences().getInt(PREF_MAX_RUNS));
    }

    static int getMaxDays() {
        return Math.max(1, ChromiteSwingActivator.getInstance().getPreferences().getInt(PREF_MAX_DAYS));
    }

    static void setRetention(int maxRuns, int maxDays) {
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        preferences.setValue(PREF_MAX_RUNS, Math.max(1, maxRuns));
        preferences.setValue(PREF_MAX_DAYS, Math.max(1, maxDays));
        PrefUtils.savePreferenceStore(preferences);
    }

    @NotNull
    private static Path getFile(@NotNull String queryKey) {
        Path folder = DBWorkbench.getPlatform().getWorkspace().getMetadataFolder()
            .resolve("chromite-query-library").resolve("history"); //$NON-NLS-1$ //$NON-NLS-2$
        // Keys are UUIDs; anything else (a file key) is made a safe file name
        return folder.resolve(queryKey.replaceAll("[^A-Za-z0-9._-]", "_") + ".tsv"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Adds a run and drops the runs outside the retention.
     */
    static synchronized void append(@NotNull String queryKey, @NotNull Run run) {
        Path file = getFile(queryKey);
        try {
            Files.createDirectories(file.getParent());
            List<Run> runs = new ArrayList<>(read(file));
            runs.add(run);
            LocalDateTime oldest = LocalDateTime.now().minusDays(getMaxDays());
            runs.removeIf(r -> r.time().isBefore(oldest));
            int maxRuns = getMaxRuns();
            if (runs.size() > maxRuns) {
                runs = new ArrayList<>(runs.subList(runs.size() - maxRuns, runs.size()));
            }
            StringBuilder text = new StringBuilder();
            for (Run r : runs) {
                text.append(format(r)).append('\n');
            }
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Can't write query history " + file, e);
        }
    }

    /**
     * Runs of a query, oldest first.
     */
    @NotNull
    static synchronized List<Run> getRuns(@NotNull String queryKey) {
        return read(getFile(queryKey));
    }

    @NotNull
    private static List<Run> read(@NotNull Path file) {
        List<Run> runs = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return runs;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Run run = parse(line);
                if (run != null) {
                    runs.add(run);
                }
            }
        } catch (IOException e) {
            log.warn("Can't read query history " + file, e);
        }
        return runs;
    }

    @NotNull
    private static String format(@NotNull Run run) {
        StringBuilder snapshot = new StringBuilder();
        for (int i = 0; i < run.snapshot().size(); i++) {
            if (i > 0) {
                snapshot.append(ROW);
            }
            snapshot.append(String.join(String.valueOf(CELL), run.snapshot().get(i)));
        }
        return String.join("\t", run.time().toString(), escape(run.server()), String.valueOf(run.durationMillis()), //$NON-NLS-1$
            String.valueOf(run.rows()), run.status().name(), escape(run.message()), escape(snapshot.toString()));
    }

    @Nullable
    private static Run parse(@NotNull String line) {
        String[] cells = line.split("\t", -1); //$NON-NLS-1$
        if (cells.length < 7) {
            return null;
        }
        try {
            List<List<String>> snapshot = new ArrayList<>();
            String snapshotText = unescape(cells[6]);
            if (!snapshotText.isEmpty()) {
                for (String row : snapshotText.split(String.valueOf(ROW), -1)) {
                    snapshot.add(List.of(row.split(String.valueOf(CELL), -1)));
                }
            }
            return new Run(LocalDateTime.parse(cells[0]), unescape(cells[1]), Long.parseLong(cells[2]), Long.parseLong(cells[3]),
                Status.valueOf(cells[4]), unescape(cells[5]), snapshot);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            log.debug("Bad history line: " + line);
            return null;
        }
    }

    @NotNull
    private static String escape(@NotNull String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
    }

    @NotNull
    private static String unescape(@NotNull String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                result.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
