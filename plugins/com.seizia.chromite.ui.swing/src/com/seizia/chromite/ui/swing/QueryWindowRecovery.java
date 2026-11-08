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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.ScriptsMessages;
import com.seizia.chromite.utils.GeneralUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 쿼리 창 자동 저장과 다시 시작할 때 복원(SWT 감사 B-S38, A-S17).
 *
 * 설계:
 * <ul>
 *     <li>열린 쿼리 창의 목록(순서, 활성 창), 파일, 연결, 기본 DB/스키마, 커서 위치와, 저장하지 않은 글(고친 창, 파일이 없는 창)을
 *     작업 공간 {@code .metadata/chromite-recovery/query-windows.json} 한 파일에 쓴다. 파일 하나를 임시 파일에 쓰고 바꿔 넣으므로
 *     ({@code ATOMIC_MOVE}) 쓰는 중에 프로세스가 죽어도 이전 상태나 새 상태 중 하나가 남는다. 고치지 않은 파일 창은 경로만 적는다
 *     (내용은 파일에 있다).</li>
 *     <li>저장 시점: {@value #SAVE_INTERVAL_MS}ms마다 EDT에서 상태를 JSON으로 만들어 지난번과 다를 때만 쓰고(쓰기는 작업 스레드),
 *     메인 창이 닫힐 때({@code MainFrame#dispose}) 마지막으로 한 번 바로 쓴다. 비정상 종료(프로세스 강제 종료)에서는 마지막 주기의 상태가 남는다.
 *     파일 감시나 문서 리스너를 두지 않은 것은 비교 한 번이 싸고, 연결·DB 콤보·저장·탭 열고 닫기까지 모든 바뀜을 한 곳에서 잡기 때문이다.</li>
 *     <li>끝낼 때의 저장 질문(Save / Don't Save / Cancel)은 그대로다. Don't Save는 "파일에 쓰지 않음"이고 글은 복원 파일에 남아 다음 시작에서
 *     고친 창으로 돌아온다(복원 질문에서 Discard로 버릴 수 있다).</li>
 *     <li>복원: 메인 창이 열린 뒤 한 번 "쿼리 창 N개를 복원할까요?"를 묻는다(기본 단추 Restore: 아무것도 실행하지 않음, Discard).
 *     창은 연결하지 않고 연다. 연결과 기본 DB/스키마는 창에 정해 두고, 사용자가 실행할 때 연결된다({@link SqlEditorPanel#setRestoredDefaults}).
 *     어떤 문장도 다시 실행하지 않는다. 묻는 동안과 그 전에는 주기 저장을 시작하지 않아 지난 세션의 파일을 덮어쓰지 않는다.</li>
 *     <li>Discard(또는 대화상자 닫기)는 파일을 {@code query-windows.discarded.json}으로 옮겨 한 번 더 남긴다(잘못 누른 경우 손으로 되살릴 수 있게).</li>
 *     <li>넣지 않는 창: Query Library 쿼리 창(확장, 라이브러리에 저장된다), 고치지 않은 Modify 창(개체 정의가 그 사이 바뀌었을 수 있어 옛 정의를
 *     실행할 위험). 고친 Modify 창은 사용자의 글을 잃지 않도록 평범한 쿼리 창으로 복원한다.</li>
 *     <li>연결은 프로젝트 이름과 연결 ID로 찾는다(이름이 바뀌어도 찾는다). 없어진 연결이면 연결 없이 연다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-query-window-recovery.md)
 * - 2026-11-08 복원 질문이 열린 동안 메인 창이 닫히면(앱을 밖에서 끝냄) 질문이 닫힌 것을 Discard로 보고 지난 세션의 파일을 옮긴 뒤,
 *   이미 멈춘 쓰기 실행기에 저장을 넣어 {@code RejectedExecutionException}이 났다. 이제 그때는 복원도 버리기도 하지 않고 파일을 그대로 둔다
 */
final class QueryWindowRecovery {

    private static final Log log = Log.getLog(QueryWindowRecovery.class);

    /**
     * The main window side: its query windows (the same methods as {@link QueryLibraryPanel.Host}).
     */
    interface Host {
        @NotNull
        Component getComponent();

        @NotNull
        List<SqlEditorPanel> getQueryWindows();

        @Nullable
        SqlEditorPanel getActiveQueryWindow();

        void showQueryWindow(@NotNull SqlEditorPanel editor);

        @NotNull
        SqlEditorPanel openQueryWindow(@Nullable DBPDataSourceContainer container, @NotNull String fileName);
    }

    static final int SAVE_INTERVAL_MS = 10_000;
    private static final String FOLDER = "chromite-recovery"; //$NON-NLS-1$
    private static final String SESSION_FILE = "query-windows.json"; //$NON-NLS-1$
    private static final String DISCARDED_FILE = "query-windows.discarded.json"; //$NON-NLS-1$
    private static final int VERSION = 1;
    private static final Gson GSON = new Gson();

    /**
     * One query window.
     *
     * @param name       window name ({@code SQLQueryN.sql} or the file name)
     * @param file       absolute path of its file, null when it was never saved
     * @param project    project of the connection
     * @param connection connection id, null without a connection
     * @param catalog    default database, or null
     * @param schema     default schema, or null
     * @param modified   the text differs from the file (or a never saved window was typed in)
     * @param text       the text when it is not in the file (modified, or never saved); null otherwise
     * @param caret      caret offset
     */
    record Window(
        @NotNull String name,
        @Nullable String file,
        @Nullable String project,
        @Nullable String connection,
        @Nullable String catalog,
        @Nullable String schema,
        boolean modified,
        @Nullable String text,
        int caret
    ) {
    }

    /**
     * @param active index of the active window in {@code windows}, -1 when another document was active
     */
    record Session(int version, int active, @Nullable List<Window> windows) {
    }

    private final Host host;
    private final Timer timer;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Query window recovery"); //$NON-NLS-1$
        thread.setDaemon(true);
        return thread;
    });
    private final Object writeLock = new Object();
    // EDT only: saving runs between start() and stop()
    private boolean started;
    // EDT only: stop() ran. The main window can close while the modal restore question of start() is still open
    private boolean stopped;
    // EDT only: the JSON written last, so that an unchanged state is not written again
    @Nullable
    private String lastJson;
    // EDT only: the number of the newest write
    private long writeSerial;
    // Under writeLock: a write that was queued before a newer one already written is skipped
    private long writtenSerial;

    QueryWindowRecovery(@NotNull Host host) {
        this.host = host;
        this.timer = new Timer(SAVE_INTERVAL_MS, e -> saveIfChanged());
    }

    /**
     * Asks once whether to restore the query windows of the last session (Restore is the default; nothing runs), then
     * starts saving periodically. Call in the EDT once the main window is shown.
     */
    void start() {
        if (started || stopped) {
            return;
        }
        Session session = read();
        List<Window> windows = session == null || session.windows() == null ? List.of() : session.windows();
        if (!windows.isEmpty()) {
            String[] options = {ScriptsMessages.recovery_restore, ScriptsMessages.recovery_discard};
            int answer = JOptionPane.showOptionDialog(host.getComponent(),
                NLS.bind(windows.size() == 1 ? ScriptsMessages.recovery_question_one : ScriptsMessages.recovery_question_many, windows.size()),
                GeneralUtils.getProductName(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (stopped) {
                // The main window closed while the question was open (its dialog closed with it): not an answer. The
                // last session's file stays for the next start, and nothing may be saved after stop()
                return;
            }
            if (answer == 0) {
                restore(windows, session.active());
            } else {
                discard();
            }
        }
        started = true;
        // The restored windows are the new state
        saveIfChanged();
        timer.start();
    }

    /**
     * Writes the state one last time, in this thread, and stops. Called when the main window closes, while its query
     * windows are still there.
     */
    void stop() {
        stopped = true;
        if (!started) {
            // The question was not answered: the last session's file stays as it is
            writer.shutdown();
            return;
        }
        started = false;
        timer.stop();
        String json = GSON.toJson(snapshot());
        if (!json.equals(lastJson)) {
            lastJson = json;
            write(json, ++writeSerial);
        }
        writer.shutdown();
    }

    private void saveIfChanged() {
        if (!started) {
            return;
        }
        String json;
        try {
            json = GSON.toJson(snapshot());
        } catch (RuntimeException e) {
            log.warn("Can't record the query windows", e);
            return;
        }
        if (json.equals(lastJson)) {
            return;
        }
        lastJson = json;
        long serial = ++writeSerial;
        writer.execute(() -> write(json, serial));
    }

    /**
     * The state of the query windows now (EDT).
     */
    @NotNull
    private Session snapshot() {
        List<Window> windows = new ArrayList<>();
        SqlEditorPanel active = host.getActiveQueryWindow();
        int activeIndex = -1;
        for (SqlEditorPanel editor : host.getQueryWindows()) {
            boolean modified = editor.isDirty();
            if (editor.getExtension() != null || (editor.getSourceObject() != null && !modified)) {
                // A Query Library query is saved to its library; an unchanged Modify window would bring back an old definition
                continue;
            }
            Path file = editor.getFile();
            DBPDataSourceContainer container = editor.getDataSourceContainer();
            SqlEditorPanel.DefaultObjectNames defaults = editor.getDefaultObjectNames();
            if (editor == active) {
                activeIndex = windows.size();
            }
            windows.add(new Window(
                editor.getFileName(),
                file != null ? file.toString() : null,
                container != null ? container.getProject().getName() : null,
                container != null ? container.getId() : null,
                defaults != null ? defaults.catalog() : null,
                defaults != null ? defaults.schema() : null,
                modified,
                modified || file == null ? editor.getContent().text() : null,
                editor.getTextArea().getCaretPosition()));
        }
        return new Session(VERSION, activeIndex, windows);
    }

    /**
     * Opens the windows again, without connecting and without running anything.
     */
    private void restore(@NotNull List<Window> windows, int active) {
        SqlEditorPanel activeEditor = null;
        for (int i = 0; i < windows.size(); i++) {
            SqlEditorPanel editor = null;
            try {
                editor = restore(windows.get(i));
            } catch (RuntimeException e) {
                log.warn("Can't restore query window " + windows.get(i).name(), e);
            }
            if (i == active) {
                activeEditor = editor;
            }
        }
        if (activeEditor != null) {
            host.showQueryWindow(activeEditor);
        }
    }

    @Nullable
    private SqlEditorPanel restore(@NotNull Window window) {
        Path file = window.file() != null ? Path.of(window.file()) : null;
        // The format (charset, BOM, line separator) of the file it saves to
        SqlFileContent content = SqlFileContent.newFile(""); //$NON-NLS-1$
        if (file != null && Files.isRegularFile(file)) {
            try {
                content = SqlFileContent.read(file);
            } catch (IOException e) {
                log.warn("Can't read " + file, e);
                if (window.text() == null) {
                    return null;
                }
            }
        } else if (file != null && window.text() == null) {
            // Saved, unchanged, and the file is gone: nothing to bring back
            log.debug("File of restored query window not found: " + file);
            return null;
        }
        String text = window.text() != null ? window.text() : content.text();
        SqlEditorPanel editor = host.openQueryWindow(findConnection(window), file != null ? file.getFileName().toString() : window.name());
        editor.setContent(content.withText(text), file);
        if (window.modified()) {
            editor.markModified();
        }
        editor.getTextArea().setCaretPosition(Math.clamp(window.caret(), 0, editor.getTextArea().getDocument().getLength()));
        if (window.catalog() != null || window.schema() != null) {
            editor.setRestoredDefaults(new SqlEditorPanel.DefaultObjectNames(window.catalog(), window.schema()));
        }
        return editor;
    }

    @Nullable
    private static DBPDataSourceContainer findConnection(@NotNull Window window) {
        if (window.connection() == null) {
            return null;
        }
        DBPWorkspace workspace = DBWorkbench.getPlatform().getWorkspace();
        DBPProject project = window.project() != null ? workspace.getProject(window.project()) : null;
        DBPDataSourceContainer found = project != null ? project.getDataSourceRegistry().getDataSource(window.connection()) : null;
        if (found != null) {
            return found;
        }
        // The project was renamed: connection ids are unique in the workspace
        for (DBPProject other : workspace.getProjects()) {
            DBPDataSourceContainer container = other.getDataSourceRegistry().getDataSource(window.connection());
            if (container != null) {
                return container;
            }
        }
        return null;
    }

    @NotNull
    private static Path getFile(@NotNull String name) {
        return GeneralUtils.getMetadataFolder().resolve(FOLDER).resolve(name);
    }

    @Nullable
    private static Session read() {
        Path file = getFile(SESSION_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Session session = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Session.class);
            if (session != null && session.version() > VERSION) {
                log.warn("Query window recovery file of a newer version ignored: " + file);
                return null;
            }
            return session;
        } catch (IOException | JsonParseException e) {
            log.warn("Can't read the query window recovery file " + file, e);
            return null;
        }
    }

    /**
     * Keeps the last session's file once more as the discarded one, instead of deleting it.
     */
    private static void discard() {
        Path file = getFile(SESSION_FILE);
        try {
            Files.move(file, getFile(DISCARDED_FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Can't move the query window recovery file " + file, e);
        }
    }

    /**
     * Writes the state through a temporary file, so that a crash while writing leaves the old or the new state. A write
     * queued before a newer one that is written already is skipped (the final write of {@link #stop} runs in the EDT).
     */
    private void write(@NotNull String json, long serial) {
        synchronized (writeLock) {
            if (serial < writtenSerial) {
                return;
            }
            Path file = getFile(SESSION_FILE);
            Path temp = file.resolveSibling(SESSION_FILE + ".tmp"); //$NON-NLS-1$
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                writtenSerial = serial;
            } catch (IOException e) {
                log.warn("Can't write the query window recovery file " + file, e);
            }
        }
    }
}
