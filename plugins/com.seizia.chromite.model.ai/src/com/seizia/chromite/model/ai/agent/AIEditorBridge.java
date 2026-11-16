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
package com.seizia.chromite.model.ai.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;

import java.util.List;

/**
 * The query windows of the running application, for the editor tools: open SQL in a new window ({@code openInEditor}), list the
 * open windows ({@code listQueryWindows}), read their text ({@code getQueryWindowText}) and edit it ({@code editQueryWindow}).
 * Implemented by the Swing frontend (phase 2) and registered with {@link AIAgentServices#setEditorBridge}.
 *
 * 설계:
 * <ul>
 *     <li>도구는 모델 번들에 있고 쿼리 창은 Swing에 있다. 모델이 Swing에 의존하지 않도록 인터페이스와 값 레코드만 둔다.</li>
 *     <li>메서드는 아무 스레드에서나 부를 수 있다. 구현이 EDT에서 창을 읽고 고친 뒤 결과를 돌려준다(도구는 작업·MCP 스레드에서 돈다).</li>
 *     <li>창은 구현이 정한 ID로 가리킨다. 제목은 같은 이름이 여럿일 수 있어 ID로 쓰지 않는다. ID가 없으면 활성 창이다.</li>
 *     <li>고치기는 미리 보기와 실제 고치기가 같은 범위 계산을 쓰도록 한 메서드({@link #editQueryWindow})에 {@code apply}를 둔다. 확인 대화상자의
 *     차이와 실제로 바뀌는 글이 어긋나지 않게 하기 위해서다. 고치기는 실행하거나 저장하지 않고, 되돌리기 한 번으로 취소된다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "편집기 연결")
 * - 2026-11-08 열린 쿼리 창 목록·글 읽기·고치기({@link #listQueryWindows}, {@link #getQueryWindowText}, {@link #editQueryWindow}) (docs/chromite/ai-agent.md "편집기 도구")
 */
public interface AIEditorBridge {

    /**
     * Opens a new query window with the text; does not execute it. May be called on any thread.
     *
     * @param connection connection of the new window, or null for none
     * @param title      window title, or null for the default
     */
    void openSqlEditor(
        @Nullable DBPDataSourceContainer connection,
        @NotNull String sql,
        @Nullable String title
    ) throws DBException;

    /**
     * An open query window.
     *
     * @param id             ID to address the window in later calls (valid while the window is open)
     * @param title          file name of the window ({@code SQLQuery1.sql} or the saved file's name)
     * @param connection     connection of the window, or null
     * @param database       default database/schema of the window, or null when unknown
     * @param file           path of the saved file, or null for a query that was never saved
     * @param modified       whether the window has unsaved changes
     * @param active         whether it is the selected document tab
     * @param executing      whether a query runs in the window
     * @param length         length of the text
     * @param caret          caret offset
     * @param selectionStart start of the selection (equals {@code selectionEnd} when nothing is selected)
     * @param selectionEnd   end of the selection
     */
    record QueryWindow(
        @NotNull String id,
        @NotNull String title,
        @Nullable DBPDataSourceContainer connection,
        @Nullable String database,
        @Nullable String file,
        boolean modified,
        boolean active,
        boolean executing,
        int length,
        int caret,
        int selectionStart,
        int selectionEnd
    ) {
        public boolean hasSelection() {
            return selectionEnd > selectionStart;
        }
    }

    /**
     * Which text of a window to read.
     */
    enum TextPart {
        /** The whole text. */
        ALL,
        /** The selected text (empty when nothing is selected). */
        SELECTION,
        /** The statement at the caret, as the connection's SQL parser finds it (the whole text without a connected connection). */
        STATEMENT
    }

    /**
     * Text read from a window.
     *
     * @param start offset of the text in the window
     * @param end   end offset of the text in the window
     */
    record QueryWindowText(@NotNull QueryWindow window, @NotNull TextPart part, @NotNull String text, int start, int end) {
    }

    /**
     * How an edit changes a window's text.
     */
    enum EditMode {
        /** Insert at the caret (replacing nothing). */
        INSERT,
        /** Replace the selected text; fails when nothing is selected. */
        REPLACE_SELECTION,
        /** Replace the whole text. */
        REPLACE_ALL
    }

    /**
     * An edit of a window: the range {@code [start, end)} of {@code before} is replaced by the new text.
     *
     * @param before the whole text before the edit
     */
    record QueryWindowEdit(@NotNull QueryWindow window, @NotNull String before, int start, int end, @NotNull String text) {
        /** The whole text after the edit. */
        @NotNull
        public String after() {
            return before.substring(0, start) + text + before.substring(end);
        }
    }

    /**
     * The open query windows in tab order.
     */
    @NotNull
    List<QueryWindow> listQueryWindows() throws DBException;

    /**
     * Text of a window.
     *
     * @param windowId window ID from {@link #listQueryWindows}, or null for the active window
     */
    @NotNull
    QueryWindowText getQueryWindowText(@Nullable String windowId, @NotNull TextPart part) throws DBException;

    /**
     * Computes and (with {@code apply}) makes an edit of a window's text as one undoable edit. Never executes or saves the
     * text. The window shows the new text selected.
     *
     * @param windowId window ID from {@link #listQueryWindows}, or null for the active window
     * @param apply    false only computes the edit (the confirmation preview)
     */
    @NotNull
    QueryWindowEdit editQueryWindow(
        @Nullable String windowId,
        @NotNull EditMode mode,
        @NotNull String text,
        boolean apply
    ) throws DBException;
}
