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
import com.seizia.chromite.ui.swing.internal.AiMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DocumentTabMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Query Library 쿼리를 편집·실행하는 쿼리 창 확장({@link SqlEditorExtension}): 이름·설명·기본 대상·기본 DB/스키마·매개변수 머리말,
 * 라이브러리로 저장, 실행 전 매개변수 묻기.
 *
 * 설계:
 * <ul>
 *     <li>편집과 실행은 같은 쿼리 창이다(쿼리 창의 구문 색, 자동완성, 찾기, GO 배치, 여러 결과, Messages를 그대로 씀). 트리의 Run은 이 쿼리의 창이
 *     같은 연결로 열려 있고 실행 중이 아니면 그 창에서, 아니면 새 창을 열어 실행한다. 다른 서버에서 같은 쿼리를 나란히 볼 수 있게 하기 위해서다.</li>
 *     <li>창은 쿼리 파일 이름(쿼리 이름)과 연결로 제목을 단다. 저장(Ctrl+S, 닫을 때 저장)은 파일에 SQL만 쓰지 않고 머리말과 함께
 *     {@link QueryLibrary#save}로 쓴다. 이름을 바꾸면 파일 이름도 바뀐다.</li>
 *     <li>머리말 입력란을 바꾸면 쿼리 창을 "고침"으로 표시한다. 매개변수 정의는 "Parameters…"에서 지금 SQL의 매개변수에 대해 정한다.</li>
 *     <li>머리말 오른쪽 끝의 저장 단추(아이콘만, 사용자 결정 2026-11-08)는 메인 창의 Save(Ctrl+S) 동작을 그대로 부른다
 *     ({@link QueryLibraryPanel.Host#getSaveAction}). 저장 경로를 하나로 두어 읽기 전용 루트 안내, 이름 검사, 충돌 질문이 단축키와 같게
 *     하기 위해서다. 창이 고침 상태일 때만 켜진다({@link SqlEditorPanel#DIRTY_PROPERTY}).</li>
 *     <li>Database·Schema 칸은 대상 연결의 DB/스키마 목록이 있는 콤보다({@link QueryLibraryDefaultsPicker}).</li>
 *     <li>실행 대상 찾기({@link #resolveTarget}): 정해진 연결은 데이터 소스 ID → 이름 순으로 찾고, 없거나 "묻기"면 연결을 고르게 한다.</li>
 *     <li>공유 루트에서는 다른 사람이 같은 쿼리를 먼저 저장할 수 있다. 창은 연 때(또는 마지막 저장 때)의 쿼리를 기억하고, 저장할 때 파일의
 *     편집 항목(이름, 설명, 대상, DB/스키마, 매개변수, SQL)이 그와 다르면 덮어쓰기 전에 묻는다(Overwrite / Reload / Cancel).
 *     창이 다루지 않는 항목만 다른 경우(Schedule… 저장 등)는 묻지 않는다. 비교는 내용으로 하므로 고친 시각이 필요 없고, 질문에 보이는
 *     고친 시각은 파일의 수정 시각이다. 라이브러리를 다시 읽을 때마다({@link #libraryRead}) 열린 창도 확인해,
 *     고치지 않은 창은 새 내용으로 바꾸고 알림 줄을 보이며, 고친 창은 Reload를 제안한다.</li>
 *     <li>Save As는 라이브러리 쿼리를 바꾸지 않고 SQL만(머리말 없이) 평범한 {@code .sql} 파일로 내보낸다({@link #exportSql}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "실행 흐름")
 * - 2026-11-06 Query Library ③: 저장할 때 파일의 예약 정의와 모르는 머리말을 유지 (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 쿼리 창 도구 막대가 메인 창으로 옮겨져 머리말 위치 설명을 고침 (docs/chromite/swing-frontend.md "SQL Editor 도구 막대")
 * - 2026-11-07 Thresholds…: AI 에이전트 모니터링 판정용 {@code @threshold} 머리말 편집({@link QueryLibraryThresholdsDialog}). 저장할 때 파일의
 *   모르는 머리말 줄에서 기준값 블록만 바꾼다 (docs/chromite/ai-agent.md "Query Library 기준값")
 * - 2026-11-07 ④ 공유: 저장할 때 다른 사람의 저장을 알아채고 묻기, 디스크에서 바뀐 쿼리 알림·다시 읽기, Save As는 평범한 .sql로 내보내기
 *   (docs/chromite/swing-query-library.md "공유")
 * - 2026-11-08 Save As .sql 덮어쓰기 확인은 바꾸기 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-08 머리말 단추의 최소 크기를 글자가 들어가기 전에 고정하지 않음: 좁은 창에서 "Para…", "Thres…"로 잘리던 문제
 *   (docs/chromite/swing-query-library.md "머리말 배치")
 * - 2026-11-08 저장 충돌 질문의 고친 시각은 파일의 수정 시각(파일에 {@code @updated}를 쓰지 않음) (docs/chromite/swing-query-library.md "저장 형식")
 * - 2026-11-08 머리말 오른쪽 끝에 저장 아이콘 단추(Ctrl+S와 같은 동작, 고쳤을 때만 켜짐) (docs/chromite/swing-query-library.md "머리말 저장 단추")
 * - 2026-11-08 Database·Schema 글 칸을 대상 연결의 목록이 있는 콤보로 바꿈({@link QueryLibraryDefaultsPicker})
 *   (docs/chromite/swing-query-library.md "머리말 DB/스키마 목록")
 * - 2026-11-09 저장 단추 도구 설명의 키 글자를 플랫폼 키에서 만듦({@link UiKeys}, 앱 변경 #24) (docs/chromite/shortcuts.md)
 * - 2026-11-12 {@link #rename}: 문서 탭 메뉴의 이름 바꾸기 = 트리의 Rename과 같은 경로, 고친 창은 이름 칸에(앱 변경 #42)
 *   (docs/chromite/swing-frontend.md "문서 탭 메뉴")
 */
final class QueryLibraryEditor implements SqlEditorExtension {

    private final QueryLibraryPanel.Host host;
    // The query as last saved, with the header fields applied on save
    private QueryLibraryItem item;
    @Nullable
    private SqlEditorPanel editor;
    private final JPanel header;
    private final JTextField nameField = new JTextField(18);
    private final JTextField descriptionField = new JTextField(30);
    private final JComboBox<Object> targetCombo = new JComboBox<>();
    private final QueryLibraryDefaultsPicker defaultsPicker = new QueryLibraryDefaultsPicker(this::getListSource, this::fieldChanged);
    private final JButton parametersButton = new JButton();
    private final JButton thresholdsButton = new JButton();
    private final JButton saveButton = createSaveButton();
    // Edited thresholds; null while unchanged (the file's blocks are kept as they are)
    @Nullable
    private List<QueryLibraryThresholdsDialog.Threshold> thresholds;
    private List<QueryLibraryParameter> parameters = new ArrayList<>();
    private boolean updating;
    // Changed on disk by someone else
    private final JLabel diskNoticeLabel = new JLabel();
    private final JButton diskNoticeReload = new JButton();
    private final JPanel diskNotice;
    private boolean diskNoticeWarning;
    // The disk version the notice is about, and whether the user closed the notice for it (not shown again for it)
    @Nullable
    private String noticedContent;
    private boolean dismissed;

    private QueryLibraryEditor(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item) {
        this.host = host;
        this.item = item.copy();
        this.parameters = new ArrayList<>(this.item.getParameters());
        diskNotice = createDiskNotice();
        header = new JPanel(new BorderLayout());
        header.add(diskNotice, BorderLayout.NORTH);
        header.add(createHeader(), BorderLayout.CENTER);
        loadFields();
    }

    //////////////////////////////////////////////////////////
    // Opening and running (EDT)

    /**
     * Opens a query for editing: its window if it is open already, else a new one with the query's default target
     * (if it can be found without asking).
     */
    static void edit(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item) {
        SqlEditorPanel existing = findWindow(host, item, null, false);
        if (existing != null) {
            host.showQueryWindow(existing);
            return;
        }
        openWindow(host, item, findDefaultTarget(host, item));
    }

    /**
     * Runs a query on its default target ({@code container == null}) or on the given connection, in its window
     * (reused if open on the same connection and idle) or a new one.
     */
    static void run(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item, @Nullable DBPDataSourceContainer container) {
        DBPDataSourceContainer target = container != null ? container : resolveTarget(host, item);
        if (target == null) {
            return;
        }
        SqlEditorPanel editor = findWindow(host, item, target, true);
        if (editor == null) {
            editor = openWindow(host, item, target);
        } else {
            host.showQueryWindow(editor);
        }
        editor.execute();
    }

    @Nullable
    private static SqlEditorPanel findWindow(
        @NotNull QueryLibraryPanel.Host host,
        @NotNull QueryLibraryItem item,
        @Nullable DBPDataSourceContainer container,
        boolean idleOnly
    ) {
        for (SqlEditorPanel window : host.getQueryWindows()) {
            if (window.getExtension() instanceof QueryLibraryEditor libraryEditor
                && libraryEditor.item.getStateKey().equals(item.getStateKey())
                && (container == null || window.getDataSourceContainer() == container)
                && (!idleOnly || !window.isExecuting())) {
                return window;
            }
        }
        return null;
    }

    @NotNull
    private static SqlEditorPanel openWindow(
        @NotNull QueryLibraryPanel.Host host,
        @NotNull QueryLibraryItem item,
        @Nullable DBPDataSourceContainer container
    ) {
        Path file = QueryLibrary.getInstance().resolveFile(item);
        String fileName = file != null ? String.valueOf(file.getFileName()) : QueryLibraryFile.toFileName(item.getName()) + QueryLibraryFile.EXTENSION;
        SqlEditorPanel editor = host.openQueryWindow(container, fileName);
        editor.setContent(SqlFileContent.newFile(item.getSql()), file);
        QueryLibraryEditor extension = new QueryLibraryEditor(host, item);
        extension.editor = editor;
        editor.setExtension(extension);
        editor.addPropertyChangeListener(SqlEditorPanel.DIRTY_PROPERTY, e -> extension.saveButton.setEnabled(editor.isDirty()));
        return editor;
    }

    /**
     * The connection to run a query on by default: its fixed connection (by id, else by name), the current connection,
     * or the one the user picks.
     */
    @Nullable
    static DBPDataSourceContainer resolveTarget(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item) {
        DBPDataSourceContainer target = findDefaultTarget(host, item);
        if (target != null) {
            return target;
        }
        String reason = switch (item.getTargetMode()) {
            case CONNECTION -> NLS.bind(ToolsMessages.library_editor_connection_missing, CommonUtils.notEmpty(item.getTargetName()));
            case CURRENT -> ToolsMessages.library_editor_no_current;
            case ASK -> null;
        };
        return chooseConnection(host.getComponent(), NLS.bind(ToolsMessages.library_editor_run_title, item.getName()), reason,
            host.getCurrentConnection());
    }

    @Nullable
    private static DBPDataSourceContainer findDefaultTarget(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item) {
        return switch (item.getTargetMode()) {
            case CONNECTION -> findConnection(item.getTargetId(), item.getTargetName());
            case CURRENT -> host.getCurrentConnection();
            case ASK -> null;
        };
    }

    /**
     * A connection by data source id, else by name (a shared query on another computer has other ids), in any project.
     */
    @Nullable
    static DBPDataSourceContainer findConnection(@Nullable String id, @Nullable String name) {
        List<? extends DBPProject> projects = DBWorkbench.getPlatform().getWorkspace().getProjects();
        if (id != null) {
            for (DBPProject project : projects) {
                DBPDataSourceContainer container = project.getDataSourceRegistry().getDataSource(id);
                if (container != null) {
                    return container;
                }
            }
        }
        if (name != null) {
            for (DBPProject project : projects) {
                DBPDataSourceContainer container = project.getDataSourceRegistry().findDataSourceByName(name);
                if (container != null) {
                    return container;
                }
            }
        }
        return null;
    }

    /**
     * The connections of the workspace (not hidden or temporary), by name.
     */
    @NotNull
    static List<DBPDataSourceContainer> getConnections() {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                if (!container.isHidden() && !container.isTemporary()) {
                    result.add(container);
                }
            }
        }
        result.sort(Comparator.comparing(DBPDataSourceContainer::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Asks for a connection ("Run on…", or a default target that is not available).
     */
    @Nullable
    static DBPDataSourceContainer chooseConnection(
        @NotNull Component parent,
        @NotNull String title,
        @Nullable String reason,
        @Nullable DBPDataSourceContainer preselected
    ) {
        List<DBPDataSourceContainer> connections = getConnections();
        if (connections.isEmpty()) {
            JOptionPane.showMessageDialog(parent, ToolsMessages.library_editor_no_connections, title, JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        JComboBox<DBPDataSourceContainer> combo = new JComboBox<>(connections.toArray(new DBPDataSourceContainer[0]));
        combo.setRenderer(new ConnectionRenderer());
        if (preselected != null) {
            combo.setSelectedItem(preselected);
        }
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        if (reason != null) {
            panel.add(new JLabel(reason), BorderLayout.NORTH);
        }
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.add(new JLabel(ToolsMessages.library_editor_connection), BorderLayout.WEST);
        row.add(combo, BorderLayout.CENTER);
        panel.add(row, BorderLayout.CENTER);
        int answer = JOptionPane.showConfirmDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return answer == JOptionPane.OK_OPTION && combo.getSelectedItem() instanceof DBPDataSourceContainer selected ? selected : null;
    }

    //////////////////////////////////////////////////////////
    // SqlEditorExtension

    @NotNull
    @Override
    public JComponent getHeader() {
        return header;
    }

    @NotNull
    @Override
    public Icon getTabIcon() {
        return UiIcons.get("library-query"); //$NON-NLS-1$
    }

    @Override
    public boolean save(@NotNull SqlEditorPanel editor) {
        QueryLibrary library = QueryLibrary.getInstance();
        Path file = library.resolveFile(item);
        if (file == null || library.isReadOnly(file)) {
            JOptionPane.showMessageDialog(editor, ToolsMessages.library_editor_read_only,
                ToolsMessages.library_editor_save, JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        String name = nameField.getText().strip();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(editor, ToolsMessages.library_error_enter_name, ToolsMessages.library_editor_save, JOptionPane.INFORMATION_MESSAGE);
            nameField.requestFocusInWindow();
            return false;
        }
        QueryLibraryItem current = null;
        if (Files.isRegularFile(file)) {
            try {
                current = QueryLibraryFile.read(file);
            } catch (IOException e) {
                DBWorkbench.getPlatformUI().showError(ToolsMessages.library_editor_save, NLS.bind(ToolsMessages.library_error_read, file), e);
                return false;
            }
            // Someone else saved this query after it was opened here: ask before overwriting their change
            if (!getEditedContent(current).equals(getEditedContent(item)) && !confirmOverwrite(editor, current, file)) {
                return false;
            }
        } else if (JOptionPane.showConfirmDialog(editor, NLS.bind(ToolsMessages.library_conflict_deleted, file),
            ToolsMessages.library_editor_save, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return false;
        }
        QueryLibraryItem updated = item.copy();
        updated.setFile(file);
        applyFields(updated, editor.getContent().text());
        // The schedule and unknown header lines are edited elsewhere (Schedule…, other versions): keep the file's
        if (current != null) {
            updated.setSchedule(current.getSchedule());
            updated.getExtraHeader().clear();
            updated.getExtraHeader().addAll(current.getExtraHeader());
        }
        if (thresholds != null) {
            List<String> extraHeader = QueryLibraryThresholdsDialog.replace(updated.getExtraHeader(), thresholds);
            updated.getExtraHeader().clear();
            updated.getExtraHeader().addAll(extraHeader);
        }
        try {
            library.save(updated);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_editor_save, NLS.bind(ToolsMessages.library_error_save_to, file), e);
            return false;
        }
        item = updated;
        thresholds = null;
        diskNotice.setVisible(false);
        Path savedFile = updated.getFile();
        if (savedFile != null) {
            editor.saved(savedFile, editor.getContent());
        }
        return true;
    }

    /**
     * File &gt; Save As of a library query: its SQL (without the header) as a plain {@code .sql} file. The window stays
     * the library query.
     */
    /**
     * Rename in the document tab menu, the same as Rename in the Query Library tree ({@link QueryLibrary#renameQuery}:
     * the name in the file and the file name), and the window follows the file. A window with unsaved changes gets the
     * name in its Name field instead, which its next save writes (with the file name): renaming the file under it would
     * offer Reload, which drops the changes.
     */
    @Override
    public void rename(@NotNull SqlEditorPanel editor) {
        Object input = JOptionPane.showInputDialog(editor, ToolsMessages.library_rename_query_prompt, ToolsMessages.library_rename,
            JOptionPane.PLAIN_MESSAGE, null, null, nameField.getText().strip());
        String name = input != null ? input.toString().strip() : ""; //$NON-NLS-1$
        if (name.isEmpty() || name.equals(nameField.getText().strip())) {
            return;
        }
        if (editor.isDirty()) {
            nameField.setText(name);
            JOptionPane.showMessageDialog(editor, NLS.bind(DocumentTabMessages.tab_library_rename_modified, editor.getFileName()),
                ToolsMessages.library_rename, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            Path file = QueryLibrary.getInstance().renameQuery(item, name);
            int caret = editor.getTextArea().getCaretPosition();
            reload(QueryLibraryFile.read(file));
            editor.getTextArea().setCaretPosition(Math.clamp(caret, 0, editor.getTextArea().getDocument().getLength()));
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_rename, NLS.bind(ToolsMessages.library_error_rename, editor.getFileName()), e);
        }
    }

    @Override
    public boolean saveAs(@NotNull SqlEditorPanel editor) {
        Path file = QueryLibrary.getInstance().resolveFile(item);
        SqlFileContent format = editor.getContent();
        if (file != null && Files.isRegularFile(file)) {
            try {
                // The charset and line separator of the library file
                format = SqlFileContent.read(file).withText(format.text());
            } catch (IOException e) {
                // A file in a charset that can't be read: the window's format
            }
        }
        return exportSql(editor, format, QueryLibraryFile.toFileName(nameField.getText().strip()) + QueryLibraryFile.EXTENSION);
    }

    /**
     * Asks for a file and writes the SQL text into it (Save As .sql).
     *
     * @return false if canceled or failed
     */
    static boolean exportSql(@NotNull Component parent, @NotNull SqlFileContent content, @NotNull String fileName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(ToolsMessages.library_save_sql_title);
        chooser.setFileFilter(new FileNameExtensionFilter(ToolsMessages.library_save_sql_filter, "sql")); //$NON-NLS-1$
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), fileName));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        Path target = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!target.getFileName().toString().contains(".")) { //$NON-NLS-1$
            target = target.resolveSibling(target.getFileName() + QueryLibraryFile.EXTENSION);
        }
        if (QueryLibrary.getInstance().findRoot(target) != null) {
            // A plain file in a root would show up as a query without a header next to the library query
            JOptionPane.showMessageDialog(parent, ToolsMessages.library_save_sql_in_root, ToolsMessages.library_save_sql_title,
                JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        if (Files.exists(target) && !SwingPlatformUI.confirmChange(parent, NLS.bind(ToolsMessages.library_save_sql_exists, target.getFileName()),
            ToolsMessages.library_save_sql_title, CoreMessages.common_replace, JOptionPane.WARNING_MESSAGE)) {
            return false;
        }
        try {
            content.write(target);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_save_sql_title, NLS.bind(ToolsMessages.library_error_save_to, target), e);
            return false;
        }
        return true;
    }

    //////////////////////////////////////////////////////////
    // Changes made by others (EDT)

    /**
     * The parts of a query that this window edits, as file text: name, description, target, database, schema,
     * parameters and SQL. The creation time, the schedule and unknown header lines are left out: a Schedule… save or
     * another version's header is not a change of what the window shows.
     */
    @NotNull
    private static String getEditedContent(@NotNull QueryLibraryItem query) {
        QueryLibraryItem copy = query.copy();
        copy.setCreated(null);
        copy.setSchedule(null);
        copy.getExtraHeader().clear();
        return QueryLibraryFile.format(copy);
    }

    /**
     * Save found the file changed by someone else since it was opened here: Overwrite, Reload (their version, the
     * window's changes are dropped) or Cancel.
     *
     * @param file the query's file: its modification time is the time of the other save
     * @return true to overwrite
     */
    private boolean confirmOverwrite(@NotNull SqlEditorPanel editor, @NotNull QueryLibraryItem current, @NotNull Path file) {
        String[] options = {ToolsMessages.library_conflict_overwrite, ToolsMessages.library_conflict_reload, ToolsMessages.button_cancel};
        String updated;
        try {
            updated = LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault()).withNano(0).toString()
                .replace('T', ' ');
        } catch (IOException e) {
            updated = "?"; //$NON-NLS-1$
        }
        int answer = JOptionPane.showOptionDialog(editor, NLS.bind(ToolsMessages.library_conflict_message, current.getName(), updated),
            ToolsMessages.library_conflict_title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        if (answer == 1) {
            reload(current);
        }
        return answer == 0;
    }

    /**
     * Shows the file's version in the window (header fields and SQL), dropping the window's changes.
     */
    private void reload(@NotNull QueryLibraryItem current) {
        item = current.copy();
        parameters = new ArrayList<>(item.getParameters());
        thresholds = null;
        loadFields();
        diskNotice.setVisible(false);
        if (editor != null) {
            editor.setContent(editor.getContent().withText(current.getSql()), current.getFile());
        }
    }

    /**
     * After the library was read (automatically or by Refresh): the windows whose query changed on disk. A window
     * without changes shows the new version and says so; a changed window keeps its text and offers Reload (its Save
     * asks before overwriting).
     *
     * @param loaded the queries read, by {@link QueryLibraryItem#getStateKey}
     */
    static void libraryRead(@NotNull QueryLibraryPanel.Host host, @NotNull Map<String, QueryLibraryItem> loaded) {
        for (SqlEditorPanel window : host.getQueryWindows()) {
            if (window.getExtension() instanceof QueryLibraryEditor libraryEditor) {
                libraryEditor.checkDisk(window, loaded.get(libraryEditor.item.getStateKey()));
            }
        }
    }

    private void checkDisk(@NotNull SqlEditorPanel window, @Nullable QueryLibraryItem onDisk) {
        String diskContent = onDisk != null ? getEditedContent(onDisk) : null;
        if (diskContent != null && diskContent.equals(getEditedContent(item))) {
            // The same as here (also after someone undid their change): a warning is out of date, a "reloaded" note stays
            if (diskNoticeWarning) {
                diskNotice.setVisible(false);
                diskNoticeWarning = false;
            }
            noticedContent = null;
            return;
        }
        if (Objects.equals(diskContent, noticedContent) && (diskNotice.isVisible() || dismissed)) {
            return;
        }
        noticedContent = diskContent;
        dismissed = false;
        String time = LocalTime.now().withNano(0).toString();
        if (onDisk == null) {
            showDiskNotice(true, NLS.bind(ToolsMessages.library_disk_missing, time), false);
        } else if (!window.isDirty()) {
            reload(onDisk);
            showDiskNotice(false, NLS.bind(ToolsMessages.library_disk_reloaded, time), false);
        } else {
            showDiskNotice(true, NLS.bind(ToolsMessages.library_disk_changed, time), true);
        }
    }

    private void showDiskNotice(boolean warning, @NotNull String text, boolean offerReload) {
        diskNoticeWarning = warning;
        diskNoticeLabel.setIcon(UiIcons.get(warning ? "warning" : "info")); //$NON-NLS-1$ //$NON-NLS-2$
        diskNoticeLabel.setText(text);
        diskNoticeReload.setVisible(offerReload);
        diskNotice.setVisible(true);
        diskNotice.revalidate();
    }

    /**
     * Reload in the notice: the file as it is now, the window's changes are dropped.
     */
    private void reloadFromDisk() {
        Path file = QueryLibrary.getInstance().resolveFile(item);
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            reload(QueryLibraryFile.read(file));
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_disk_reload, NLS.bind(ToolsMessages.library_error_read, file), e);
        }
    }

    /**
     * The notice above the header fields: the query changed on disk (reloaded, or Reload offered) or disappeared.
     */
    @NotNull
    private JPanel createDiskNotice() {
        JPanel panel = new JPanel(new BorderLayout(UIScale.scale(8), 0));
        panel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(2), UIScale.scale(10)));
        diskNoticeLabel.setIconTextGap(UIScale.scale(6));
        panel.add(diskNoticeLabel, BorderLayout.CENTER);
        diskNoticeReload.setText(ToolsMessages.library_disk_reload);
        diskNoticeReload.setToolTipText(ToolsMessages.library_disk_reload_tip);
        diskNoticeReload.addActionListener(e -> reloadFromDisk());
        JButton dismiss = new JButton(ToolsMessages.library_disk_dismiss);
        dismiss.addActionListener(e -> {
            dismissed = true;
            panel.setVisible(false);
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(4), 0));
        buttons.setOpaque(false);
        buttons.add(diskNoticeReload);
        buttons.add(dismiss);
        panel.add(buttons, BorderLayout.EAST);
        panel.setVisible(false);
        return panel;
    }

    @Nullable
    @Override
    public SqlParameterBinding prepareExecution(@NotNull SqlEditorPanel editor, @NotNull DBPDataSourceContainer container, @NotNull String sql) {
        QueryLibraryItem current = item.copy();
        applyFields(current, editor.getContent().text());
        return askParameters(editor, current, container, sql);
    }

    /**
     * Asks for the values of the parameters of the SQL that runs (none: no dialog) and makes its binding.
     *
     * @return the binding, or null if canceled
     */
    @Nullable
    static SqlParameterBinding askParameters(
        @NotNull Component parent,
        @NotNull QueryLibraryItem item,
        @Nullable DBPDataSourceContainer container,
        @NotNull String sql
    ) {
        Map<String, Boolean> kinds = QueryParameterBinder.detect(sql);
        Map<String, String> values = new LinkedHashMap<>();
        if (!kinds.isEmpty()) {
            List<QueryLibraryParameter> definitions = QueryParameterDefinitionsDialog.merge(item.getParameters(), kinds);
            Map<String, String> lastValues = QueryLibraryState.getLastValues(item);
            Map<String, String> answer = QueryParameterDialog.open(parent, item.getName(), definitions, kinds, lastValues, container);
            if (answer == null) {
                return null;
            }
            Map<String, String> remembered = new LinkedHashMap<>(lastValues);
            remembered.putAll(answer);
            QueryLibraryState.setLastValues(item, remembered);
            values.putAll(answer);
        }
        QueryParameterBinder binder = new QueryParameterBinder(item, values);
        return binder.isNeeded() ? binder : SqlParameterBinding.NONE;
    }

    //////////////////////////////////////////////////////////
    // Header (EDT)

    /**
     * Two rows above the editor of the query window: Name, Description, Save | Target, Database, Schema, Parameters…,
     * Thresholds….
     */
    @NotNull
    private JPanel createHeader() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(10), UIScale.scale(6), UIScale.scale(10)));
        targetCombo.setRenderer(new ConnectionRenderer());
        targetCombo.setToolTipText(ToolsMessages.library_editor_target_tip);
        nameField.setToolTipText(ToolsMessages.library_editor_name_tip);
        descriptionField.setToolTipText(ToolsMessages.library_editor_description_tip);
        descriptionField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ToolsMessages.library_field_description);
        parametersButton.setToolTipText(ToolsMessages.library_editor_parameters_tip);
        parametersButton.addActionListener(e -> editParameters());
        thresholdsButton.setToolTipText(AiMessages.ai_thresholds_tip);
        thresholdsButton.addActionListener(e -> editThresholds());
        // Icon only (user decision 2026-11-08): the name stays for screen readers, the tool tip names the shortcut
        saveButton.getAccessibleContext().setAccessibleName(ToolsMessages.library_editor_save);
        // Enabled while the window is modified
        saveButton.setEnabled(false);

        // Only Name and Target have labels; the other fields show placeholders, so both rows fit a narrow document area
        addCell(panel, new JLabel(ToolsMessages.library_field_name), 0, 0, 0, 1);
        addCell(panel, nameField, 1, 0, 0.35, 1);
        addCell(panel, descriptionField, 2, 0, 0.65, 3);
        addCell(panel, new JLabel(ToolsMessages.library_editor_target), 0, 1, 0, 1);
        addCell(panel, targetCombo, 1, 1, 0.35, 1);
        addCell(panel, defaultsPicker.getDatabaseCombo(), 2, 1, 0.3, 1);
        addCell(panel, defaultsPicker.getSchemaCombo(), 3, 1, 0.3, 1);
        addCell(panel, parametersButton, 4, 1, 0, 1);
        addCell(panel, thresholdsButton, 5, 1, 0, 1);
        // The right end of the first row, right-aligned with Thresholds… below it
        GridBagConstraints save = new GridBagConstraints();
        save.gridx = 5;
        save.gridy = 0;
        save.anchor = GridBagConstraints.EAST;
        save.insets = new Insets(UIScale.scale(2), 0, UIScale.scale(2), UIScale.scale(6));
        panel.add(saveButton, save);

        DocumentListener modified = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        };
        for (JTextField field : List.of(nameField, descriptionField)) {
            field.getDocument().addDocumentListener(modified);
        }
        targetCombo.addActionListener(e -> {
            fieldChanged();
            defaultsPicker.sourceChanged();
        });
        return panel;
    }

    private static void addCell(@NotNull JPanel panel, @NotNull JComponent component, int x, int y, double weight, int width) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = width;
        c.weightx = weight;
        c.fill = weight > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        // Fields shrink to a usable width at most. Labels and buttons keep their own minimum (their preferred size), which
        // follows their current text: the buttons get their text ("Parameters (2)…") only after this, so a size fixed here
        // was that of an empty button and GridBagLayout cut the label ("Para…") whenever the row did not fit its preferred width
        if (weight > 0) {
            Dimension preferred = component.getPreferredSize();
            component.setMinimumSize(new Dimension(UIScale.scale(70), preferred.height));
            component.setPreferredSize(new Dimension(UIScale.scale(70), preferred.height));
        }
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(UIScale.scale(2), 0, UIScale.scale(2), UIScale.scale(6));
        panel.add(component, c);
    }

    private void loadFields() {
        updating = true;
        try {
            nameField.setText(item.getName());
            descriptionField.setText(CommonUtils.notEmpty(item.getDescription()));
            defaultsPicker.setValues(item.getDatabase(), item.getSchema());
            // Show the start of long names and descriptions
            for (JTextField field : List.of(nameField, descriptionField)) {
                field.setCaretPosition(0);
            }
            DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
            model.addElement(QueryLibraryItem.TargetMode.ASK);
            model.addElement(QueryLibraryItem.TargetMode.CURRENT);
            Object selected = item.getTargetMode();
            DBPDataSourceContainer fixed = item.getTargetMode() == QueryLibraryItem.TargetMode.CONNECTION
                ? findConnection(item.getTargetId(), item.getTargetName()) : null;
            for (DBPDataSourceContainer container : getConnections()) {
                model.addElement(container);
                if (container == fixed) {
                    selected = container;
                }
            }
            if (item.getTargetMode() == QueryLibraryItem.TargetMode.CONNECTION && fixed == null) {
                // Keep a connection of another computer as it is
                MissingConnection missing = new MissingConnection(item.getTargetId(), CommonUtils.notEmpty(item.getTargetName()));
                model.addElement(missing);
                selected = missing;
            }
            model.setSelectedItem(selected);
            targetCombo.setModel(model);
            defaultsPicker.sourceChanged();
            updateParametersButton();
            updateThresholdsButton();
        } finally {
            updating = false;
        }
    }

    private void fieldChanged() {
        if (!updating && editor != null) {
            editor.markModified();
        }
    }

    /**
     * Where the Database and Schema lists come from: the fixed target, else this window's connection (the target is
     * "Ask when run" or "Current connection").
     */
    @NotNull
    private QueryLibraryDefaultsPicker.Source getListSource() {
        Object selected = targetCombo.getSelectedItem();
        if (selected instanceof DBPDataSourceContainer container) {
            return new QueryLibraryDefaultsPicker.Source(container, true);
        }
        if (selected instanceof MissingConnection) {
            return new QueryLibraryDefaultsPicker.Source(null, true);
        }
        return new QueryLibraryDefaultsPicker.Source(editor != null ? editor.getDataSourceContainer() : null, false);
    }

    /**
     * The header's Save button: the save icon like the tool bars' icon buttons; its tool tip is the action's description.
     */
    @NotNull
    private JButton createSaveButton() {
        Action action = new AbstractAction(ToolsMessages.library_editor_save) {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveFromHeader(e);
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, UiKeys.format(ToolsMessages.library_editor_save_tip, UiKeys.command(KeyEvent.VK_S)));
        return UiIcons.createActionButton(action, "save"); //$NON-NLS-1$
    }

    /**
     * The header's Save button: the main window's File &gt; Save (Ctrl+S) on this window.
     */
    private void saveFromHeader(@NotNull ActionEvent event) {
        if (editor == null) {
            return;
        }
        // The action saves the active query window
        host.showQueryWindow(editor);
        host.getSaveAction().actionPerformed(event);
    }

    private void updateParametersButton() {
        long defined = parameters.stream().filter(p -> !p.isPlain()).count();
        parametersButton.setText(defined > 0 ? NLS.bind(ToolsMessages.library_editor_parameters_count, defined) : ToolsMessages.library_editor_parameters);
    }

    private void editParameters() {
        if (editor == null) {
            return;
        }
        Map<String, Boolean> kinds = QueryParameterBinder.detect(editor.getContent().text());
        List<QueryLibraryParameter> edited = QueryParameterDefinitionsDialog.open(editor, parameters, kinds);
        if (edited != null) {
            parameters = edited;
            updateParametersButton();
            editor.markModified();
        }
    }

    @NotNull
    private List<QueryLibraryThresholdsDialog.Threshold> getThresholds() {
        return thresholds != null ? thresholds : QueryLibraryThresholdsDialog.parse(item.getExtraHeader());
    }

    private void updateThresholdsButton() {
        int count = getThresholds().size();
        thresholdsButton.setText(count > 0 ? NLS.bind(AiMessages.ai_thresholds_button_count, count) : AiMessages.ai_thresholds_button);
    }

    private void editThresholds() {
        if (editor == null) {
            return;
        }
        List<QueryLibraryThresholdsDialog.Threshold> edited = QueryLibraryThresholdsDialog.open(editor, nameField.getText().strip(), getThresholds());
        if (edited != null) {
            thresholds = edited;
            updateThresholdsButton();
            editor.markModified();
        }
    }

    /**
     * Puts the header fields and the SQL into a copy of the query. Definitions of parameters that are no longer in the
     * SQL are dropped.
     */
    private void applyFields(@NotNull QueryLibraryItem target, @NotNull String sql) {
        String name = nameField.getText().strip();
        if (!name.isEmpty()) {
            target.setName(name);
        }
        target.setDescription(descriptionField.getText().strip());
        target.setDatabase(defaultsPicker.getDatabase());
        target.setSchema(defaultsPicker.getSchema());
        Object selected = targetCombo.getSelectedItem();
        if (selected instanceof QueryLibraryItem.TargetMode mode) {
            target.setTargetMode(mode);
            target.setTarget(null, null);
        } else if (selected instanceof DBPDataSourceContainer container) {
            target.setTargetMode(QueryLibraryItem.TargetMode.CONNECTION);
            target.setTarget(container.getId(), container.getName());
        } else if (selected instanceof MissingConnection missing) {
            target.setTargetMode(QueryLibraryItem.TargetMode.CONNECTION);
            target.setTarget(missing.id(), missing.name());
        }
        target.setSql(sql);
        target.getParameters().clear();
        target.getParameters().addAll(QueryParameterDefinitionsDialog.merge(parameters, QueryParameterBinder.detect(sql)));
    }

    /**
     * The fixed target of a query that is not a connection here (written by another computer).
     */
    private record MissingConnection(@Nullable String id, @NotNull String name) {
    }

    /**
     * Target items: "Ask when run", "Current connection", connections with their driver icons.
     */
    private static final class ConnectionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DBPDataSourceContainer container) {
                setText(container.getName());
                setIcon(NodeIcons.getIcon(container.getDriver().getIcon()));
            } else if (value instanceof QueryLibraryItem.TargetMode mode) {
                setText(mode.getLabel());
                setIcon(null);
            } else if (value instanceof MissingConnection missing) {
                setText(NLS.bind(ToolsMessages.library_connection_not_found, missing.name()));
                setIcon(null);
            }
            return this;
        }
    }
}
