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
import com.seizia.chromite.ui.swing.internal.GridNavMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.data.DBDReferenceNavigation;
import com.seizia.chromite.model.data.DBDResultSetModel;
import com.seizia.chromite.model.data.DBDValueRow;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.runtime.jobs.DataSourceJob;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * 참조 패널(SWT {@code ReferencesPanel}): 결과 표에서 고른 행과 외래 키로 이어진 다른 테이블의 행(이 행이 가리키는 행, 이 행을 가리키는 행)을
 * 결과 옆에 보인다.
 *
 * 설계:
 * <ul>
 *     <li>관계 목록은 결과 뷰어가 한 번 읽어 둔 것({@link ResultSetReferences#readLinks})을 쓴다. 위 콤보에서 관계를 고르고, 고른 관계는 이 뷰어가
 *     열려 있는 동안 기억한다(SWT는 가상 모델 속성에 남기지만, 설정 파일을 바꾸지 않으려고 메모리에만).</li>
 *     <li>표의 선택이 바뀌면 0.3초 뒤에 그 행들의 키 값으로 대상 테이블을 읽는다(모델의 {@code DBDReferenceUtils} 필터: 한 행이면 =, 여러 행이면 IN).
 *     키 값이 너무 많은 IN 목록이 되지 않게 {@link #MAX_ROWS}행까지만 쓴다(Oracle IN 목록 한도 1000).</li>
 *     <li>대상 행은 결과 뷰어({@link ResultSetViewerPanel}) 하나로 보인다(정렬·필터·다음 페이지·값 패널을 그대로 씀). 읽기 전용이다: 고칠 때는
 *     "열기"로 결과 뷰어 자체를 그 테이블로 옮긴다(외래 키 따라가기와 같고 뒤로 가기로 돌아옴). 참조 패널의 저장하지 않은 변경이 선택이 바뀔 때
 *     묻지 않고 사라지지 않게 하려는 것이다.</li>
 *     <li>읽기는 결과의 실행 문맥에서 백그라운드 Job으로 한다. 새 읽기가 시작되면 앞의 결과는 버린다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-grid-navigation.md "참조 패널")
 */
final class ResultSetReferencesPanel extends JPanel {

    private static final Log log = Log.getLog(ResultSetReferencesPanel.class);
    static final int MAX_ROWS = 1000;

    /**
     * The selected rows of the main grid, with the model that gives their key values.
     */
    record SelectedRows(@NotNull DBDResultSetModel model, @NotNull List<? extends DBDValueRow> rows) {
    }

    /**
     * The result viewer side.
     */
    interface Source {
        @Nullable
        DBCExecutionContext getContext();

        /**
         * The relations of the result, read once (the callback runs in the EDT, maybe at once).
         */
        void requestLinks(@NotNull Consumer<List<ResultSetReferences.Link>> callback);

        /**
         * The selected rows (at most {@link #MAX_ROWS}), or null when no row is selected.
         */
        @Nullable
        SelectedRows getSelectedRows();

        long getSegmentSize();

        @NotNull
        ResultSetGrid.Host getHost();

        /**
         * "Open": the result viewer itself goes to the related rows (with history).
         */
        void open(@NotNull ResultSetReferences.Link link);
    }

    private final Source source;
    private final JComboBox<ResultSetReferences.Link> linkCombo = new JComboBox<>();
    private final JButton openButton = new JButton(GridNavMessages.gridnav_refs_open);
    private final JPanel content = new JPanel(new BorderLayout());
    private final JLabel messageLabel = new JLabel("", SwingConstants.CENTER);
    private final Timer refreshTimer;
    @Nullable
    private ResultSetViewerPanel viewer;
    @Nullable
    private String chosenLabel;
    private int readNumber;
    private boolean linksRequested;

    ResultSetReferencesPanel(@NotNull Source source) {
        super(new BorderLayout());
        this.source = source;
        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        top.add(new JLabel(GridNavMessages.gridnav_refs_key), BorderLayout.WEST);
        linkCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                String text = value instanceof ResultSetReferences.Link link
                    ? NLS.bind(link.referencing() ? GridNavMessages.gridnav_refs_referencing : GridNavMessages.gridnav_refs_referenced, link.label())
                    : GridNavMessages.gridnav_refs_none;
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            }
        });
        linkCombo.addActionListener(e -> {
            if (linkCombo.getSelectedItem() instanceof ResultSetReferences.Link link) {
                chosenLabel = link.label();
                scheduleRefresh();
            }
        });
        top.add(linkCombo, BorderLayout.CENTER);
        openButton.setToolTipText(GridNavMessages.gridnav_refs_open_tip);
        openButton.addActionListener(e -> {
            if (linkCombo.getSelectedItem() instanceof ResultSetReferences.Link link) {
                source.open(link);
            }
        });
        top.add(openButton, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        messageLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        content.add(messageLabel, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);
        refreshTimer = new Timer(300, e -> refresh());
        refreshTimer.setRepeats(false);
        openButton.setEnabled(false);
    }

    /**
     * The result changed (another fetch, navigation): the relations are read again when the panel is shown.
     */
    void resultChanged() {
        linksRequested = false;
        linkCombo.removeAllItems();
        scheduleRefresh();
    }

    /**
     * The selection of the main grid changed, or the panel was shown.
     */
    void scheduleRefresh() {
        refreshTimer.restart();
    }

    void dispose() {
        refreshTimer.stop();
        readNumber++;
        disposeViewer();
    }

    private void disposeViewer() {
        if (viewer != null) {
            viewer.dispose();
            viewer = null;
        }
    }

    private void showMessage(@NotNull String text) {
        disposeViewer();
        content.removeAll();
        messageLabel.setText(text);
        content.add(messageLabel, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
    }

    private void refresh() {
        if (!isShowing()) {
            return;
        }
        if (!linksRequested) {
            linksRequested = true;
            showMessage(GridNavMessages.gridnav_refs_reading_keys);
            source.requestLinks(this::setLinks);
            return;
        }
        if (!(linkCombo.getSelectedItem() instanceof ResultSetReferences.Link link)) {
            openButton.setEnabled(false);
            showMessage(linkCombo.getItemCount() == 0 ? GridNavMessages.gridnav_refs_no_keys : GridNavMessages.gridnav_refs_choose_key);
            return;
        }
        SelectedRows selected = source.getSelectedRows();
        DBCExecutionContext context = source.getContext();
        openButton.setEnabled(selected != null && context != null);
        if (selected == null || context == null) {
            showMessage(GridNavMessages.gridnav_refs_select_rows);
            return;
        }
        int number = ++readNumber;
        showMessage(NLS.bind(GridNavMessages.gridnav_refs_reading, link.label()));
        long segmentSize = source.getSegmentSize();
        new DataSourceJob("Read references", context) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                ResultSetGrid.Fetched[] result = new ResultSetGrid.Fetched[1];
                Throwable error = null;
                try {
                    DBDReferenceNavigation navigation = ResultSetReferences.resolve(monitor, link.association(), link.referencing(),
                        selected.model(), selected.rows());
                    DBSDataContainer target = (DBSDataContainer) navigation.getTargetEntity();
                    ResultSetGridReceiver receiver = new ResultSetGridReceiver(target, fetched -> result[0] = fetched);
                    ResultSetViewerPanel.readData(monitor, context, target, receiver, navigation.getTargetFilter(), 0,
                        segmentSize > 0 ? segmentSize : -1, this);
                } catch (Throwable e) {
                    error = e;
                }
                Throwable readError = error;
                SwingUtilities.invokeLater(() -> showResult(number, result[0], readError));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void setLinks(@NotNull List<ResultSetReferences.Link> links) {
        linkCombo.removeAllItems();
        ResultSetReferences.Link chosen = null;
        for (ResultSetReferences.Link link : links) {
            linkCombo.addItem(link);
            if (link.label().equals(chosenLabel)) {
                chosen = link;
            }
        }
        if (chosen != null) {
            linkCombo.setSelectedItem(chosen);
        }
        scheduleRefresh();
    }

    private void showResult(int number, @Nullable ResultSetGrid.Fetched fetched, @Nullable Throwable error) {
        if (number != readNumber || !isDisplayable()) {
            if (fetched != null) {
                ResultSetViewerPanel.releaseContents(fetched.source().cellValues());
            }
            return;
        }
        if (error != null || fetched == null) {
            log.debug("Can't read the referenced rows", error);
            showMessage(NLS.bind(GridNavMessages.gridnav_refs_failed, error == null ? "" : ErrorText.describeLine(error))); //$NON-NLS-1$
            return;
        }
        // Read-only: the main viewer edits the rows after "Open"
        ResultSetGrid.Fetched readOnly = new ResultSetGrid.Fetched(fetched.data(), null,
            fetched.editModel() != null ? GridNavMessages.gridnav_refs_read_only : fetched.readOnlyReason(), fetched.source());
        disposeViewer();
        content.removeAll();
        viewer = new ResultSetViewerPanel(readOnly, source.getHost());
        content.add(viewer, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
    }
}
