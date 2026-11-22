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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.DocumentTabMessages;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 메인 창 문서 탭(쿼리 창, DDL 탭, 도구 문서, 테이블 디자이너)의 오른쪽 클릭 메뉴와 그 메뉴가 바꾸는 탭 상태: 탭 고정, 탭 위치,
 * 여러 행 배치, 닫은 쿼리 창 다시 열기(앱 변경 #42).
 *
 * 설계:
 * <ul>
 *     <li>메뉴: 저장, 이름 바꾸기 │ 연결 변경(쿼리 창, {@link SqlEditorPanel#changeConnection}) │ 닫기, 모든 탭 닫기, 다른 탭 닫기 │ 닫은 탭 다시 열기 │ 전체 경로 복사, 상위 폴더 열기 │ 탭 고정 │
 *     새 창으로 이동(쿼리 창, {@link Host#moveToNewWindow}), 탭 위치 ▸, 여러 행에 탭 표시. 탭 줄의 빈 곳에서는 닫은 탭 다시 열기와 배치 항목만 보인다.
 *     열 때마다 누른 탭으로 만든다.
 *     쿼리 창의 항목(저장, 이름 바꾸기, 연결 변경, 경로 복사, 상위 폴더 열기)은 쿼리 창 탭에서만 보이고, 저장 전 창의 경로 항목과 실행 중인
 *     창의 연결 변경은 꺼진다({@link ContextMenus}의 규칙).</li>
 *     <li>닫기는 모두 메인 창의 닫기 경로({@link Host#closeDocument}: 저장하지 않은 변경 질문, 결과 표 변경, 열린 트랜잭션 질문)를 탭마다
 *     지난다. 모든 탭·다른 탭 닫기는 고정 탭을 빼고 왼쪽부터 닫고, 하나라도 취소하면 거기서 멈춘다(끝내기·모두 저장과 같은 규칙).</li>
 *     <li>저장은 쿼리 창만, Ctrl+S와 같은 경로({@link Host#saveQueryWindow}). 이름 바꾸기는 파일이 있으면 Scripts 창의 이름 바꾸기처럼
 *     같은 폴더에서 파일 이름을 바꾸고(확장자 유지, 같은 이름이 있으면 " (2)"), 저장 전 창은 이름(탭 제목, 저장할 때 제안하는 파일 이름)만
 *     바꾼다. Query Library 창은 확장의 이름 바꾸기({@link SqlEditorExtension#rename})다.</li>
 *     <li>탭 고정: 고정 탭은 왼쪽에 고정한 순서대로 모이고, 아이콘 뒤에 핀을 그리며, 닫기 단추와 가운데 단추 닫기가 없다(메뉴의 닫기는 된다).
 *     상태는 탭 컴포넌트의 속성({@link #isPinned})이라 다시 시작 후 복원({@link QueryWindowRecovery})이 쿼리 창과 함께 저장한다. 탭은
 *     문서 탭 창의 한 옮기기 경로({@link MainFrame.EditorTabbedPane#moveTab})로 옮긴다. 끌어서 순서 바꾸기({@link DocumentTabDrag})도
 *     같은 경로를 쓰고, 고정 탭은 고정 탭 사이에서만 옮겨 이 규칙을 지킨다.</li>
 *     <li>닫은 탭 다시 열기: 닫은 쿼리 창을 다시 시작 후 복원과 같은 기록({@link QueryWindowRecovery.Window}: 파일, 연결, 기본 DB/스키마,
 *     저장하지 않은 글, 커서, 고정)으로 최근 {@value #MAX_CLOSED_WINDOWS}개까지 메모리에 둔다. 다시 열 때 연결하거나 실행하지 않는다.
 *     복원과 같은 규칙으로 Query Library 창과 고치지 않은 Modify 창은 넣지 않는다. 저장하지 않고 닫은 글도 돌아온다(고침 상태로).</li>
 *     <li>탭 위치(위쪽·왼쪽·오른쪽)와 여러 행 배치는 설정에 저장한다. 여러 행에서는 고른 탭의 행을 내용 쪽으로 옮기지 않는다
 *     ({@code rotateTabRuns: false}, {@link MainFrame}): 누를 때마다 행이 바뀌면 탭을 다시 찾아야 하기 때문이다. 한 행 배치에서는 FlatLaf가
 *     마우스 휠로 탭 줄을 넘긴다.</li>
 *     <li>새 창으로 이동(앱 변경 #173): 쿼리 창만 메뉴 항목과 탭 줄 밖으로 끌어 놓기로 별도 창({@link QueryWindowFrame})으로 옮긴다. 그 창이
 *     편집기·결과·상태 칸·DB 콤보를 끼우고, 명령은 메인 창의 "활성 편집기" 규칙({@link MainFrame})으로 그 창에 적용된다. 다른 문서(DDL 탭, 도구
 *     문서, 테이블 디자이너)는 항목이 숨고 탭 줄 밖에 놓아도 아무 일이 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성(앱 변경 #42) (docs/chromite/swing-frontend.md "문서 탭 메뉴")
 * - 2026-11-12 닫은 탭 다시 열기에 Ctrl+Shift+T(macOS ⇧⌘T) (앱 변경 #66, docs/chromite/shortcuts.md)
 * - 2026-11-12 쿼리 창의 연결 변경 항목(Query &gt; Change Connection…과 같은 명령, 앱 변경 #73) (docs/chromite/swing-frontend.md "쿼리 창의 연결 변경")
 * - 2026-11-16 쿼리 창이 아닌 탭에서 쿼리 창 항목을 끄는 대신 숨김, 구분선 정리(앱 변경 #137, docs/chromite/context-menus.md)
 * - 2026-11-22 탭 옮기기를 {@link MainFrame.EditorTabbedPane#moveTab} 하나로(여기의 빼고 다시 넣기를 지움), 끌어서 순서 바꾸기
 *   {@link DocumentTabDrag}를 붙임(앱 변경 #171, docs/chromite/swing-frontend.md "문서 탭 메뉴")
 * - 2026-11-22 쿼리 창의 "새 창으로 이동" 항목과 탭 줄 밖에 놓기, 부동을 넣지 않은 이유 삭제(앱 변경 #173, docs/chromite/swing-frontend.md
 *   "쿼리 창을 별도 창으로")
 */
final class DocumentTabMenu {

    /**
     * The main window side.
     */
    interface Host {
        /**
         * Closes a document tab through its own questions (unsaved changes, result changes, open transaction).
         *
         * @return false if the user canceled
         */
        boolean closeDocument(@NotNull Component document);

        /**
         * Save (Ctrl+S) of a query window.
         *
         * @return false if it was not saved (canceled or failed)
         */
        boolean saveQueryWindow(@NotNull SqlEditorPanel editor);

        /**
         * A query window's file was renamed: script links, recent files and the Scripts tool window follow it.
         */
        void queryFileRenamed(@NotNull Path from, @NotNull Path to);

        /**
         * Opens a closed query window again, or shows the open window of its file.
         *
         * @return null if it can't be opened (its file is gone)
         */
        @Nullable
        SqlEditorPanel reopenQueryWindow(@NotNull QueryWindowRecovery.Window window);

        @NotNull
        Action getSaveAction();

        /**
         * Moves a query window into a window of its own (app change #173).
         *
         * @param drop screen point where its tab was dropped, or null
         */
        void moveToNewWindow(@NotNull SqlEditorPanel editor, @Nullable Point drop);
    }

    static final int MAX_CLOSED_WINDOWS = 10;
    private static final String PINNED_KEY = "chromite.documentTab.pinned"; //$NON-NLS-1$
    private static final String PREF_PLACEMENT = "documentTabs.placement"; //$NON-NLS-1$
    private static final String PREF_MULTIPLE_ROWS = "documentTabs.multipleRows"; //$NON-NLS-1$
    private static final String PLACEMENT_TOP = "top"; //$NON-NLS-1$
    private static final String PLACEMENT_LEFT = "left"; //$NON-NLS-1$
    private static final String PLACEMENT_RIGHT = "right"; //$NON-NLS-1$

    private final MainFrame.EditorTabbedPane tabs;
    private final Host host;
    private final Deque<QueryWindowRecovery.Window> closedWindows = new ArrayDeque<>();
    private final Action reopenAction;

    DocumentTabMenu(@NotNull MainFrame.EditorTabbedPane tabs, @NotNull Host host) {
        this.tabs = tabs;
        this.host = host;
        reopenAction = new AbstractAction(DocumentTabMessages.tab_reopen_closed) {
            @Override
            public void actionPerformed(ActionEvent e) {
                reopenClosed();
            }
        };
        // Ctrl+Shift+T reopens a closed tab in browsers and IDEs. The Window menu item carries the key, so it
        // works while the tab menu is closed
        reopenAction.putValue(Action.ACCELERATOR_KEY, UiKeys.command(KeyEvent.VK_T, InputEvent.SHIFT_DOWN_MASK));
        reopenAction.setEnabled(false);
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showMenu(e);
                }
            }
        });
        DBPPreferenceStore preferences = getPreferences();
        tabs.setTabPlacement(toPlacement(preferences.getString(PREF_PLACEMENT)));
        tabs.setTabLayoutPolicy(preferences.getBoolean(PREF_MULTIPLE_ROWS) ? JTabbedPane.WRAP_TAB_LAYOUT : JTabbedPane.SCROLL_TAB_LAYOUT);
        // A query window's tab dropped off the tab strip goes into a window of its own; other documents stay
        DocumentTabDrag.install(tabs, (document, drop) -> {
            if (document instanceof SqlEditorPanel editor) {
                host.moveToNewWindow(editor, drop);
            }
        });
    }

    /**
     * Reopen Closed Tab, also in the Window menu: the tab menu can't be reached once the last tab is closed.
     */
    @NotNull
    Action getReopenAction() {
        return reopenAction;
    }

    static boolean isPinned(@NotNull Component document) {
        return document instanceof JComponent component && Boolean.TRUE.equals(component.getClientProperty(PINNED_KEY));
    }

    /**
     * The tab icon of a document: its own icon, with the pin after it while the tab is pinned.
     */
    @Nullable
    static Icon getTabIcon(@NotNull Component document, @Nullable Icon icon) {
        Icon base = icon instanceof PinnedIcon pinned ? pinned.base : icon;
        return base != null && isPinned(document) ? new PinnedIcon(base) : base;
    }

    /**
     * Records a query window that is being closed (its questions answered), so that it can be opened again.
     */
    void queryWindowClosing(@NotNull SqlEditorPanel editor) {
        QueryWindowRecovery.Window window = QueryWindowRecovery.capture(editor);
        if (window == null) {
            return;
        }
        closedWindows.push(window);
        while (closedWindows.size() > MAX_CLOSED_WINDOWS) {
            closedWindows.removeLast();
        }
        reopenAction.setEnabled(true);
    }

    /**
     * Pins or unpins a document tab: pinned tabs come first, in the order they were pinned; an unpinned tab goes right
     * after them.
     */
    void setPinned(@NotNull Component document, boolean pinned) {
        int index = tabs.indexOfComponent(document);
        if (index < 0 || !(document instanceof JComponent component) || isPinned(document) == pinned) {
            return;
        }
        int target = 0;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (i != index && isPinned(tabs.getComponentAt(i))) {
                target++;
            }
        }
        component.putClientProperty(PINNED_KEY, pinned ? Boolean.TRUE : null);
        component.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, !pinned);
        tabs.moveTab(index, target);
        tabs.setIconAt(target, getTabIcon(document, tabs.getIconAt(target)));
    }

    private void showMenu(@NotNull MouseEvent e) {
        int index = tabs.indexAtLocation(e.getX(), e.getY());
        Component document = index >= 0 ? tabs.getComponentAt(index) : null;
        JPopupMenu menu = new JPopupMenu();
        if (document != null) {
            SqlEditorPanel editor = document instanceof SqlEditorPanel queryWindow ? queryWindow : null;
            Path file = editor != null ? editor.getFile() : null;
            // Query window commands apply to query windows only; a window not saved yet has no file to copy or show (now)
            boolean queryWindow = editor != null;
            JMenuItem saveItem = addItem(menu, DocumentTabMessages.tab_save, queryWindow, true, () -> host.saveQueryWindow(editor));
            saveItem.setAccelerator((KeyStroke) host.getSaveAction().getValue(Action.ACCELERATOR_KEY));
            addItem(menu, DocumentTabMessages.tab_rename, queryWindow, true, () -> rename(editor));
            menu.addSeparator();
            // The query window's one way to another server; it comes to the front first, so its tool bar shows what changes
            addItem(menu, EditorMessages.editor_sql_change_connection, queryWindow, queryWindow && editor.canChangeConnection(), () -> {
                tabs.setSelectedComponent(editor);
                editor.changeConnection();
            });
            menu.addSeparator();
            addItem(menu, DocumentTabMessages.tab_close, true, true, () -> host.closeDocument(document));
            addItem(menu, DocumentTabMessages.tab_close_all, true, hasUnpinned(null), () -> closeUnpinned(null));
            addItem(menu, DocumentTabMessages.tab_close_others, true, hasUnpinned(document), () -> closeUnpinned(document));
            menu.addSeparator();
            menu.add(new JMenuItem(reopenAction));
            menu.addSeparator();
            addItem(menu, DocumentTabMessages.tab_copy_path, queryWindow, file != null, () -> copyPath(file));
            // The desktop's file manager is a kind of platform, not a state
            addItem(menu, DocumentTabMessages.tab_open_folder, queryWindow && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.OPEN), file != null, () -> openFolder(file));
            menu.addSeparator();
            boolean pinned = isPinned(document);
            addItem(menu, pinned ? DocumentTabMessages.tab_unpin : DocumentTabMessages.tab_pin, true, true, () -> setPinned(document, !pinned));
        } else {
            menu.add(new JMenuItem(reopenAction));
        }
        menu.addSeparator();
        if (document != null) {
            // With the tab arrangement items; query windows only (the other documents follow the main window's tab)
            addItem(menu, DocumentTabMessages.tab_move_to_window, document instanceof SqlEditorPanel, true,
                () -> host.moveToNewWindow((SqlEditorPanel) document, null));
        }
        JMenu positionMenu = new JMenu(DocumentTabMessages.tab_position);
        addPositionItem(positionMenu, DocumentTabMessages.tab_position_top, SwingConstants.TOP);
        addPositionItem(positionMenu, DocumentTabMessages.tab_position_left, SwingConstants.LEFT);
        addPositionItem(positionMenu, DocumentTabMessages.tab_position_right, SwingConstants.RIGHT);
        menu.add(positionMenu);
        JCheckBoxMenuItem rowsItem = new JCheckBoxMenuItem(DocumentTabMessages.tab_multiple_rows,
            tabs.getTabLayoutPolicy() == JTabbedPane.WRAP_TAB_LAYOUT);
        rowsItem.addActionListener(event -> setMultipleRows(rowsItem.isSelected()));
        menu.add(rowsItem);
        // Only the items that apply to the tab, and no separator at either end or twice in a row
        ContextMenus.tidy(menu);
        menu.show(tabs, e.getX(), e.getY());
    }

    /**
     * @param applies the item applies to the kind of tab (shown, {@link ContextMenus})
     * @param possible it is possible now (enabled)
     */
    @NotNull
    private static JMenuItem addItem(@NotNull JPopupMenu menu, @NotNull String text, boolean applies, boolean possible, @NotNull Runnable task) {
        JMenuItem item = new JMenuItem(text);
        ContextMenus.set(item, applies, possible);
        item.addActionListener(e -> task.run());
        menu.add(item);
        return item;
    }

    private void addPositionItem(@NotNull JMenu menu, @NotNull String text, int placement) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(text, tabs.getTabPlacement() == placement);
        item.addActionListener(e -> setPlacement(placement));
        menu.add(item);
    }

    /**
     * @param except the tab that stays (Close Other Tabs), or null (Close All Tabs)
     */
    private boolean hasUnpinned(@Nullable Component except) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component component = tabs.getComponentAt(i);
            if (component != except && !isPinned(component)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Close All Tabs / Close Other Tabs: each tab through its own close questions, left to right, pinned tabs stay; the
     * first tab that is not closed (canceled) stops the others.
     */
    private void closeUnpinned(@Nullable Component except) {
        List<Component> documents = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component component = tabs.getComponentAt(i);
            if (component != except && !isPinned(component)) {
                documents.add(component);
            }
        }
        for (Component document : documents) {
            // Skip a tab that is gone already (closed while an earlier question was open)
            if (tabs.indexOfComponent(document) >= 0 && !host.closeDocument(document)) {
                return;
            }
        }
    }

    private void reopenClosed() {
        // A window whose file is gone can't come back: the one closed before it opens instead
        while (!closedWindows.isEmpty()) {
            if (host.reopenQueryWindow(closedWindows.pop()) != null) {
                break;
            }
        }
        reopenAction.setEnabled(!closedWindows.isEmpty());
    }

    /**
     * Rename: a saved query window renames its file in its folder (like Rename in the Scripts tool window: the extension
     * stays, an existing name gets " (2)"), a window that was never saved changes its name only; a Query Library window
     * renames its query.
     */
    private void rename(@NotNull SqlEditorPanel editor) {
        if (editor.getExtension() != null) {
            editor.getExtension().rename(editor);
            return;
        }
        String fileName = editor.getFileName();
        int dot = fileName.lastIndexOf('.');
        String extension = dot > 0 ? fileName.substring(dot) : ""; //$NON-NLS-1$
        String current = fileName.substring(0, fileName.length() - extension.length());
        Object input = JOptionPane.showInputDialog(SwingUtilities.getWindowAncestor(tabs), NLS.bind(DocumentTabMessages.tab_rename_prompt, fileName),
            DocumentTabMessages.tab_rename_title, JOptionPane.PLAIN_MESSAGE, null, null, current);
        String name = input != null ? input.toString().strip() : ""; //$NON-NLS-1$
        if (name.isEmpty()) {
            return;
        }
        if (!extension.isEmpty() && name.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
            name = name.substring(0, name.length() - extension.length());
        }
        String stem = QueryLibraryFile.toFileName(name);
        if ((stem + extension).equals(fileName)) {
            return;
        }
        Path file = editor.getFile();
        if (file == null) {
            editor.setFileName(stem + extension);
            return;
        }
        Path source = file.toAbsolutePath().normalize();
        try {
            Path target = QueryLibrary.uniquePath(source.getParent(), stem, extension);
            Files.move(source, target);
            editor.fileMoved(target);
            host.queryFileRenamed(source, target);
        } catch (IOException | InvalidPathException e) {
            DBWorkbench.getPlatformUI().showError(DocumentTabMessages.tab_rename_title, NLS.bind(DocumentTabMessages.tab_rename_error, fileName), e);
        }
    }

    private static void copyPath(@NotNull Path file) {
        StringSelection selection = new StringSelection(file.toAbsolutePath().toString());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
    }

    private static void openFolder(@NotNull Path file) {
        Path folder = file.toAbsolutePath().getParent();
        try {
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | RuntimeException e) {
            DBWorkbench.getPlatformUI().showError(DocumentTabMessages.tab_open_folder, NLS.bind(DocumentTabMessages.tab_open_folder_error, folder), e);
        }
    }

    private void setPlacement(int placement) {
        tabs.setTabPlacement(placement);
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_PLACEMENT, switch (placement) {
            case SwingConstants.LEFT -> PLACEMENT_LEFT;
            case SwingConstants.RIGHT -> PLACEMENT_RIGHT;
            default -> PLACEMENT_TOP;
        });
        PrefUtils.savePreferenceStore(preferences);
    }

    private void setMultipleRows(boolean multipleRows) {
        tabs.setTabLayoutPolicy(multipleRows ? JTabbedPane.WRAP_TAB_LAYOUT : JTabbedPane.SCROLL_TAB_LAYOUT);
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_MULTIPLE_ROWS, multipleRows);
        PrefUtils.savePreferenceStore(preferences);
    }

    private static int toPlacement(@Nullable String value) {
        if (PLACEMENT_LEFT.equals(value)) {
            return SwingConstants.LEFT;
        }
        return PLACEMENT_RIGHT.equals(value) ? SwingConstants.RIGHT : SwingConstants.TOP;
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * A document's icon with the pin after it (pinned tab). The document's icon paints itself, so a running query
     * window's spinner keeps turning.
     */
    private static final class PinnedIcon implements Icon {
        private final Icon base;
        private final Icon pin = UiIcons.get("pin"); //$NON-NLS-1$

        PinnedIcon(@NotNull Icon base) {
            this.base = base;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            int height = getIconHeight();
            base.paintIcon(c, g, x, y + (height - base.getIconHeight()) / 2);
            pin.paintIcon(c, g, x + base.getIconWidth() + getGap(), y + (height - pin.getIconHeight()) / 2);
        }

        @Override
        public int getIconWidth() {
            return base.getIconWidth() + getGap() + pin.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return Math.max(base.getIconHeight(), pin.getIconHeight());
        }

        private static int getGap() {
            return UIScale.scale(4);
        }
    }
}
