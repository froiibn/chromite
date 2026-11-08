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

import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.agent.AIEditorBridge;
import com.seizia.chromite.model.sql.SQLScriptElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The editor bridge of the agent tools ({@link AIEditorBridge}): opens, lists, reads and edits the main window's query
 * windows for {@code openInEditor}, {@code listQueryWindows}, {@code getQueryWindowText} and {@code editQueryWindow}.
 *
 * 설계:
 * <ul>
 *     <li>도구는 작업 스레드(채팅)나 HTTP 스레드(MCP)에서 부른다. 창 읽기와 고치기는 모두 EDT에서 하고({@link #onEdt}) 결과를 기다린다.
 *     새 창 열기는 기다릴 결과가 없어 지금처럼 EDT에 넘기기만 한다.</li>
 *     <li>창 ID는 창 객체마다 처음 볼 때 정하는 {@code w1}, {@code w2} 같은 번호다(약한 참조라 닫힌 창은 잊는다). 탭 위치는 창을 닫거나 옮기면
 *     바뀌고 제목은 겹칠 수 있어 ID로 쓰지 않는다. 모델이 ID 대신 제목을 넘기면 하나로 정해질 때만 받아 준다.</li>
 *     <li>고치기는 한 번의 묶은 편집({@code beginAtomicEdit})이라 Ctrl+Z 한 번에 되돌아간다. 실행·저장은 하지 않고, 바뀐 글을 선택해 그 창을
 *     앞에 보인다(초점은 옮기지 않는다: 사용자가 채팅에 쓰는 중일 수 있다). 쿼리가 실행 중인 창은 고치지 않는다. 실행 중인 문장의 위치
 *     (오류 줄 표시 등)가 어긋나기 때문이다. 이 검사는 미리 보기에서도 해서 확인을 묻기 전에 거절한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 기존 새 창 열기(MainFrame의 람다)에 쿼리 창 목록·읽기·고치기를 더함 (docs/chromite/ai-agent.md "편집기 도구")
 */
final class AiQueryWindowBridge implements AIEditorBridge {

    private static final String ID_PREFIX = "w"; //$NON-NLS-1$

    private final Supplier<List<SqlEditorPanel>> windows;
    private final Supplier<SqlEditorPanel> activeWindow;
    private final Consumer<SqlEditorPanel> showWindow;
    private final AiAssistantPanel assistantPanel;
    // EDT only
    private final Map<SqlEditorPanel, String> ids = new WeakHashMap<>();
    private int lastId;

    /**
     * @param windows      the open query windows in tab order (EDT)
     * @param activeWindow the selected query window or null (EDT)
     * @param showWindow   selects a query window's tab (EDT)
     */
    AiQueryWindowBridge(
        @NotNull Supplier<List<SqlEditorPanel>> windows,
        @NotNull Supplier<SqlEditorPanel> activeWindow,
        @NotNull Consumer<SqlEditorPanel> showWindow,
        @NotNull AiAssistantPanel assistantPanel
    ) {
        this.windows = windows;
        this.activeWindow = activeWindow;
        this.showWindow = showWindow;
        this.assistantPanel = assistantPanel;
    }

    @Override
    public void openSqlEditor(@Nullable DBPDataSourceContainer connection, @NotNull String sql, @Nullable String title) {
        SwingUtilities.invokeLater(() -> assistantPanel.openInEditor(connection, sql, title));
    }

    @NotNull
    @Override
    public List<QueryWindow> listQueryWindows() throws DBException {
        return onEdt(() -> {
            SqlEditorPanel active = activeWindow.get();
            List<QueryWindow> list = new ArrayList<>();
            for (SqlEditorPanel editor : windows.get()) {
                list.add(describe(editor, active));
            }
            return list;
        });
    }

    @NotNull
    @Override
    public QueryWindowText getQueryWindowText(@Nullable String windowId, @NotNull TextPart part) throws DBException {
        return onEdt(() -> {
            SqlEditorPanel editor = find(windowId);
            QueryWindow window = describe(editor, activeWindow.get());
            SqlEditorPanel.SqlTextArea textArea = editor.getTextArea();
            String text = textArea.getText();
            return switch (part) {
                case ALL -> new QueryWindowText(window, part, text, 0, text.length());
                case SELECTION -> new QueryWindowText(window, part, CommonUtils.notEmpty(textArea.getSelectedText()),
                    window.selectionStart(), window.selectionEnd());
                case STATEMENT -> {
                    SQLScriptElement element = AiAssistantPanel.findStatementAtCaret(editor);
                    if (element == null) {
                        // No connected connection (or no parser result): the whole text, as "Ask Chromite Agent" does
                        yield new QueryWindowText(window, TextPart.ALL, text, 0, text.length());
                    }
                    int start = Math.max(0, Math.min(element.getOffset(), text.length()));
                    int end = Math.min(text.length(), start + element.getLength());
                    yield new QueryWindowText(window, part, text.substring(start, end), start, end);
                }
            };
        });
    }

    @NotNull
    @Override
    public QueryWindowEdit editQueryWindow(
        @Nullable String windowId,
        @NotNull EditMode mode,
        @NotNull String text,
        boolean apply
    ) throws DBException {
        // The document holds "\n" line breaks; the file format restores the file's own when it is saved
        String newText = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        return onEdt(() -> {
            SqlEditorPanel editor = find(windowId);
            SqlEditorPanel.SqlTextArea textArea = editor.getTextArea();
            if (editor.isExecuting()) {
                throw new DBException("Query window '" + editor.getFileName() + "' is running a query; edit it when the query has finished");
            }
            if (!textArea.isEditable()) {
                throw new DBException("Query window '" + editor.getFileName() + "' is read-only");
            }
            String before = textArea.getText();
            int start;
            int end;
            switch (mode) {
                case INSERT -> {
                    start = textArea.getCaretPosition();
                    end = start;
                }
                case REPLACE_SELECTION -> {
                    start = textArea.getSelectionStart();
                    end = textArea.getSelectionEnd();
                    if (start == end) {
                        throw new DBException("Nothing is selected in query window '" + editor.getFileName() +
                            "'; use mode insert or replaceAll");
                    }
                }
                default -> {
                    start = 0;
                    end = before.length();
                }
            }
            QueryWindowEdit edit = new QueryWindowEdit(describe(editor, activeWindow.get()), before, start, end, newText);
            if (apply) {
                textArea.beginAtomicEdit();
                try {
                    textArea.replaceRange(newText, start, end);
                } finally {
                    textArea.endAtomicEdit();
                }
                showWindow.accept(editor);
                textArea.select(start, start + newText.length());
            }
            return edit;
        });
    }

    // EDT
    @NotNull
    private QueryWindow describe(@NotNull SqlEditorPanel editor, @Nullable SqlEditorPanel active) {
        SqlEditorPanel.SqlTextArea textArea = editor.getTextArea();
        SqlEditorPanel.DefaultObjectNames names = editor.getDefaultObjectNames();
        Path file = editor.getFile();
        return new QueryWindow(
            getId(editor),
            editor.getFileName(),
            editor.getDataSourceContainer(),
            names == null ? null : CommonUtils.nullIfEmpty(names.label()),
            file == null ? null : file.toString(),
            editor.isDirty(),
            editor == active,
            editor.isExecuting(),
            textArea.getDocument().getLength(),
            textArea.getCaretPosition(),
            textArea.getSelectionStart(),
            textArea.getSelectionEnd());
    }

    // EDT
    @NotNull
    private String getId(@NotNull SqlEditorPanel editor) {
        return ids.computeIfAbsent(editor, e -> ID_PREFIX + (++lastId));
    }

    /**
     * The window with the ID (or, if no window has that ID, the one window with that title), or the active window. EDT.
     */
    @NotNull
    private SqlEditorPanel find(@Nullable String windowId) throws DBException {
        List<SqlEditorPanel> open = windows.get();
        if (CommonUtils.isEmptyTrimmed(windowId)) {
            SqlEditorPanel active = activeWindow.get();
            if (active != null) {
                return active;
            }
            if (open.size() == 1) {
                return open.getFirst();
            }
            throw new DBException(open.isEmpty()
                ? "No query window is open"
                : "No query window is active; pass a window id from listQueryWindows");
        }
        String key = windowId.trim();
        List<SqlEditorPanel> byTitle = new ArrayList<>();
        for (SqlEditorPanel editor : open) {
            if (key.equals(getId(editor))) {
                return editor;
            }
            if (editor.getFileName().toLowerCase(Locale.ROOT).equals(key.toLowerCase(Locale.ROOT))) {
                byTitle.add(editor);
            }
        }
        if (byTitle.size() == 1) {
            return byTitle.getFirst();
        }
        StringBuilder known = new StringBuilder();
        for (SqlEditorPanel editor : open) {
            known.append(known.isEmpty() ? "" : ", ").append(getId(editor)).append(" (").append(editor.getFileName()).append(')'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        throw new DBException("No query window '" + key + "'" + (byTitle.size() > 1 ? " (several windows have this title)" : "") +
            ". Open windows: " + (known.isEmpty() ? "none" : known));
    }

    /**
     * Runs the task on the EDT and waits for its result; a {@link DBException} of the task is thrown as it is.
     */
    private static <T> T onEdt(@NotNull Callable<T> task) throws DBException {
        if (SwingUtilities.isEventDispatchThread()) {
            return call(task);
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<DBException> error = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(call(task));
                } catch (DBException e) {
                    error.set(e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBException("Interrupted while reading the query windows", e);
        } catch (InvocationTargetException e) {
            throw new DBException("Can't read the query windows", e.getCause());
        }
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    private static <T> T call(@NotNull Callable<T> task) throws DBException {
        try {
            return task.call();
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException("Can't read the query windows", e);
        }
    }
}
