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
import com.seizia.chromite.model.admin.ServerDatabaseNames;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.runtime.jobs.ConnectionTestJob;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.List;

/**
 * 연결 대화상자의 Database 칸: 직접 입력하거나, 목록을 펼쳐 서버의 데이터베이스를 고르는 편집 가능한 콤보.
 *
 * 설계: SSMS "연결 속성 &gt; 데이터베이스에 연결: &lt;서버 찾아보기&gt;"와 같다. 저장하기 전이라 연결이 없으므로, 목록을 펼치면
 * 대화상자에 입력한 값으로 임시 연결을 만들어 잠깐 연결하고 목록({@link ServerDatabaseNames})을 읽은 뒤 닫는다.
 * <ul>
 *     <li>임시 연결은 Test Connection과 같은 방법으로 만든다(대화상자가 넘긴 {@link Source}, 새 ID·임시·메타데이터 연결 없음).
 *     SSH 터널·프록시 같은 네트워크 설정도 따르고, {@link ConnectionTestJob}이 읽은 뒤 연결을 끊는다. 작업 스레드에서 돌고 대화상자를
 *     막지 않으며, 그동안 칸 안 오른쪽 끝(펼침 화살표 앞)에 회전하는 도넛({@link SpinnerIcon#createLabel()}, 길이를 모르는 짧은 기다림,
 *     앱 변경 #60)을 보이고 도움말에 "불러오는 중"을 둔다. 칸 아래 줄은 결과 글(오류, 데이터베이스 없음)만 쓴다: 읽는 동안만 줄이 생기면
 *     대화상자가 한 줄 커졌다가 줄어 단추가 두 번 움직였다(앱 변경 #71 c). 그 줄이 이미 보이면(앞의 오류) 읽는 동안 "불러오는 중"으로
 *     바뀌어 크기가 그대로다.</li>
 *     <li>목록은 입력한 DB와 상관없이 읽는다(아직 없는 DB이거나 잘못 적었을 수 있다): 임시 연결의 DB는 드라이버 기본값(PostgreSQL
 *     {@code postgres}, 없으면 로그인의 기본 DB)이다.</li>
 *     <li>저장하지 않은 비밀번호(칸이 비고 저장 안 함)는 연결할 때 모델이 묻는다({@code DataSourceDescriptor.askForPassword}).</li>
 *     <li>실패하면 오류를 칸 아래에 보이고 대화상자는 그대로 둔다. 다음에 펼치면 다시 시도한다.</li>
 *     <li>읽은 목록은 그때의 연결 설정과 함께 기억한다. 설정이 같으면 다시 펼쳐도 연결하지 않고, 서버·로그인·네트워크 설정 등이
 *     바뀌었으면 다시 읽는다.</li>
 *     <li>칸 자체는 원래의 글 칸이다(콤보의 편집기로 씀). 저장·검사·이름 짓기는 지금처럼 그 칸의 글을 쓴다. 목록에서 고르면 그 글이 바뀐다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성 (docs/chromite/swing-conn-advanced.md "데이터베이스 목록에서 고르기")
 * - 2026-11-11 진행 표시를 길이 모르는 막대에서 도넛 스피너로(사용자 결정, 앱 변경 #60)
 * - 2026-11-12 스피너를 상태 표시줄과 같은 {@link SpinnerIcon}으로(스피너 구현 하나, 앱 변경 #60)
 * - 2026-11-12 스피너를 칸 아래 줄에서 칸 안으로: 읽는 동안 대화상자가 커졌다 줄던 것(앱 변경 #71 c)
 */
final class ConnectionDatabaseList {

    /**
     * What the dialog provides: the check of the form and the temporary connection.
     */
    interface Source {
        /**
         * Why no connection can be made from the form (the database itself is not needed), or {@code null}.
         */
        @Nullable
        String validate();

        /**
         * A temporary connection made from the form, to connect to read the list. The caller disposes it.
         */
        @NotNull
        DataSourceDescriptor createConnection();
    }

    private final JTextField databaseField;
    private final Source source;
    private final JComboBox<String> combo = new JComboBox<>();
    private final JPanel statusPanel = new JPanel(new BorderLayout());
    /**
     * Unknown length, short wait: a donut, not a bar (user decision, app change 60). Inside the field while the list
     * loads, so that no row comes and goes.
     */
    private final JLabel spinner = SpinnerIcon.createLabel();
    private final JLabel statusLabel = new JLabel() {
        @Override
        public Dimension getPreferredSize() {
            // A long error must not widen the dialog (it packs to the form): the full text is in the tool tip
            Dimension size = super.getPreferredSize();
            return new Dimension(0, size.height);
        }
    };

    private boolean loading;
    /**
     * The settings the shown list was read with; {@code null} before a list was read.
     */
    @Nullable
    private DBPConnectionConfiguration listConfiguration;

    ConnectionDatabaseList(@NotNull JTextField databaseField, @NotNull Source source, @NotNull String toolTip) {
        this.databaseField = databaseField;
        this.source = source;

        combo.setEditable(true);
        combo.setEditor(new FieldEditor());
        combo.setToolTipText(toolTip);
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                listRequested();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        // The URL mode disables the field (ConnectionSettingsPanel's manual components): the list follows it
        databaseField.addPropertyChangeListener("enabled", e -> combo.setEnabled(databaseField.isEnabled())); //$NON-NLS-1$

        spinner.setToolTipText(ConnectionMessages.settings_database_list_loading);
        // space.4 before the arrow button: the field has no padding of its own on that side in the combo
        spinner.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, UIScale.scale(4)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        statusPanel.setVisible(false);
    }

    /**
     * The Database row of the form.
     */
    @NotNull
    JComponent getCombo() {
        return combo;
    }

    /**
     * The row under the Database row: an error or "no databases" (and "loading" while the list loads again after one of
     * them). Hidden otherwise (a hidden row takes no space in the form's grid). A row of its own keeps the Database label
     * beside the combo.
     */
    @NotNull
    JComponent getStatusLine() {
        return statusPanel;
    }

    private void listRequested() {
        if (loading) {
            SwingUtilities.invokeLater(combo::hidePopup);
            return;
        }
        String problem = source.validate();
        if (problem != null) {
            showError(problem, problem);
            SwingUtilities.invokeLater(combo::hidePopup);
            return;
        }
        DataSourceDescriptor connection = source.createConnection();
        if (listConfiguration != null && listConfiguration.equals(connection.getConnectionConfiguration())) {
            connection.dispose();
            // The list is current: only point it at the typed name
            combo.getModel().setSelectedItem(databaseField.getText());
            return;
        }
        SwingUtilities.invokeLater(combo::hidePopup);
        load(connection);
    }

    private void load(@NotNull DataSourceDescriptor connection) {
        setLoading(true);
        listConfiguration = null;
        setItems(List.of());
        if (statusPanel.isVisible()) {
            // The row of an earlier result stays until this one is known (a row that comes and goes moves the buttons)
            showStatus(ConnectionMessages.settings_database_list_loading, null, false);
        }

        DBPConnectionConfiguration configuration = new DBPConnectionConfiguration(connection.getConnectionConfiguration());
        ListReader reader = new ListReader();
        ConnectionTestJob job = new ConnectionTestJob(connection, reader::read);
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent event) {
                SwingUtilities.invokeLater(() -> {
                    connection.dispose();
                    loaded(job, reader, configuration);
                });
            }
        });
        job.schedule();
    }

    private void loaded(@NotNull ConnectionTestJob job, @NotNull ListReader reader, @NotNull DBPConnectionConfiguration configuration) {
        setLoading(false);
        Throwable error = job.getConnectError() != null ? job.getConnectError() : reader.error;
        if (error != null) {
            showError(NLS.bind(ConnectionMessages.settings_database_list_error, ErrorText.summary(error)),
                NLS.bind(ConnectionMessages.settings_database_list_error, ErrorText.describe(error)));
            return;
        }
        if (reader.names == null) {
            // Canceled (the password prompt): nothing to report
            hideStatus();
            return;
        }
        listConfiguration = configuration;
        setItems(reader.names);
        if (reader.names.isEmpty()) {
            showStatus(ConnectionMessages.settings_database_list_empty, null, false);
            return;
        }
        hideStatus();
        Window window = SwingUtilities.getWindowAncestor(combo);
        if (combo.isShowing() && window != null && window.isActive()) {
            // The list was asked for by opening it: open it now that it has items
            combo.showPopup();
        }
    }

    /**
     * Replaces the items, keeping the typed name (a model fills an empty selection with its first item).
     */
    private void setItems(@NotNull List<String> names) {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(names.toArray(new String[0]));
        model.setSelectedItem(databaseField.getText());
        combo.setModel(model);
    }

    /**
     * The spinner at the end of the Database field while the list loads.
     */
    private void setLoading(boolean loading) {
        this.loading = loading;
        databaseField.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, loading ? spinner : null);
    }

    private void showError(@NotNull String text, @NotNull String toolTip) {
        showStatus(text, toolTip, true);
    }

    private void showStatus(@NotNull String text, @Nullable String toolTip, boolean error) {
        statusLabel.setText(text);
        statusLabel.setToolTipText(toolTip == null ? text : toolTip);
        statusLabel.setForeground(error
            ? ConnectionDialog.getThemeColor("Chromite.status.error", "Component.error.focusedBorderColor", Color.RED) //$NON-NLS-1$ //$NON-NLS-2$
            : UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
        setStatusVisible(true);
    }

    private void hideStatus() {
        setStatusVisible(false);
    }

    private void setStatusVisible(boolean visible) {
        if (statusPanel.isVisible() == visible) {
            statusPanel.revalidate();
            return;
        }
        statusPanel.setVisible(visible);
        if (SwingUtilities.getWindowAncestor(statusPanel) instanceof ConnectionDialog dialog) {
            // The line under the field was shown or hidden
            dialog.fitToContent();
        }
    }

    /**
     * Reads the list in the test job's session (job thread).
     */
    private static final class ListReader {
        @Nullable
        private volatile List<String> names;
        @Nullable
        private volatile Throwable error;

        private void read(@NotNull DBCSession session) {
            try {
                names = ServerDatabaseNames.read(session);
            } catch (DBException e) {
                error = e;
            }
        }
    }

    /**
     * The combo edits the form's own field, so the form keeps reading, checking and saving that field.
     */
    private final class FieldEditor implements ComboBoxEditor {
        @Override
        public Component getEditorComponent() {
            return databaseField;
        }

        @Override
        public void setItem(Object item) {
            String text = item == null ? "" : item.toString(); //$NON-NLS-1$
            // Setting the same text would still fire document events (the dialog would see a change)
            if (!text.equals(databaseField.getText())) {
                databaseField.setText(text);
            }
        }

        @Override
        public Object getItem() {
            return databaseField.getText();
        }

        @Override
        public void selectAll() {
            databaseField.selectAll();
            databaseField.requestFocusInWindow();
        }

        @Override
        public void addActionListener(ActionListener listener) {
            databaseField.addActionListener(listener);
        }

        @Override
        public void removeActionListener(ActionListener listener) {
            databaseField.removeActionListener(listener);
        }
    }
}
