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

import com.seizia.chromite.tools.transfer.DataTransferSettings;
import com.seizia.chromite.tools.transfer.IDataTransferProducer;
import com.seizia.chromite.tools.transfer.database.DatabaseProducerSettings;
import com.seizia.chromite.ui.swing.internal.TransferOptionsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.text.ParseException;
import java.util.Map;

/**
 * 데이터 내보내기·테이블 복사의 "원본 읽기 설정" 단계: 별도 연결, 행 수 세기, 가져오기 크기, 구간으로 나눠 읽기
 * (SWT {@code DatabaseProducerPageExtractSettings}).
 *
 * 설계:
 * <ul>
 *     <li>값은 모델의 {@link DatabaseProducerSettings}에 넣는다. 그래서 작업으로 저장하면 그 설정의 {@code saveSettings}로 함께 저장된다.
 *     화면 기본값은 그 클래스의 필드 기본값이다(새 객체를 만들어 읽음, 상수를 따로 두지 않음).</li>
 *     <li>파일 내보내기와 테이블 복사가 같은 원본(producer)을 쓰므로 한 페이지를 두 경로가 함께 쓴다({@link DataExportWizard}).</li>
 *     <li>SWT의 "동시 추출 수"(여러 Job)는 두지 않았다. 진행 페이지가 파이프를 차례로 가져간다고 보고 앞 파이프를 성공으로 표시하기 때문이다
 *     ({@link DataTransferWizard}의 {@code acquireDataPipe}). 결과 표의 "가져온 행만/선택한 행만"은 표가 값을 표시 문자열로만 들고 있어 두지 않았다
 *     (docs/chromite/swing-data-transfer.md "남긴 것").</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-data-transfer.md "고급 옵션")
 */
final class DataTransferExtractPage implements DataTransferWizard.Page {

    // The setting names of DatabaseProducerSettings (its loadSettings), used by @export
    static final String OPTION_FETCH_SIZE = "fetchSize"; //$NON-NLS-1$
    static final String OPTION_OPEN_NEW_CONNECTIONS = "openNewConnections"; //$NON-NLS-1$
    static final String OPTION_QUERY_ROW_COUNT = "queryRowCount"; //$NON-NLS-1$
    static final String OPTION_EXTRACT_TYPE = "extractType"; //$NON-NLS-1$
    static final String OPTION_SEGMENT_SIZE = "segmentSize"; //$NON-NLS-1$

    private final JPanel panel = new JPanel(new BorderLayout());
    private final JCheckBox newConnectionCheck = new JCheckBox(TransferOptionsMessages.extract_new_connection);
    private final JCheckBox rowCountCheck = new JCheckBox(TransferOptionsMessages.extract_row_count);
    private final JSpinner fetchSizeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1000));
    private final JCheckBox segmentsCheck = new JCheckBox(TransferOptionsMessages.extract_segments);
    private final JSpinner segmentSizeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 10000));

    DataTransferExtractPage() {
        DatabaseProducerSettings defaults = new DatabaseProducerSettings();
        newConnectionCheck.setSelected(defaults.isOpenNewConnections());
        rowCountCheck.setSelected(defaults.isQueryRowCount());
        fetchSizeSpinner.setValue(defaults.getFetchSize());
        segmentsCheck.setSelected(defaults.getExtractType() == DatabaseProducerSettings.ExtractType.SEGMENTS);
        segmentSizeSpinner.setValue(defaults.getSegmentSize());
        segmentsCheck.addActionListener(e -> updateEnablement());

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = DataTransferObjects.addRow(form, row, null, newConnectionCheck, null);
        row = DataTransferObjects.addRow(form, row, null, rowCountCheck, null);
        row = DataTransferObjects.addRow(form, row, TransferOptionsMessages.extract_fetch_size, fetchSizeSpinner,
            TransferOptionsMessages.extract_fetch_size_note);
        row = DataTransferObjects.addRow(form, row, null, segmentsCheck, null);
        DataTransferObjects.addRow(form, row, TransferOptionsMessages.extract_segment_size, segmentSizeSpinner, null);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.add(form, BorderLayout.NORTH);
        content.add(DataTransferObjects.createNote(TransferOptionsMessages.extract_note), BorderLayout.CENTER);
        panel.add(content, BorderLayout.NORTH);
        updateEnablement();
    }

    private void updateEnablement() {
        segmentSizeSpinner.setEnabled(segmentsCheck.isSelected());
    }

    /**
     * Puts the source options of an {@code @export} command into the fields, so the user sees them.
     */
    void applyCommandOptions(@NotNull Map<String, Object> options) {
        if (options.containsKey(OPTION_FETCH_SIZE)) {
            fetchSizeSpinner.setValue(Math.max(1, CommonUtils.toInt(options.get(OPTION_FETCH_SIZE), getInt(fetchSizeSpinner))));
        }
        if (options.containsKey(OPTION_OPEN_NEW_CONNECTIONS)) {
            newConnectionCheck.setSelected(CommonUtils.toBoolean(options.get(OPTION_OPEN_NEW_CONNECTIONS)));
        }
        if (options.containsKey(OPTION_QUERY_ROW_COUNT)) {
            rowCountCheck.setSelected(CommonUtils.toBoolean(options.get(OPTION_QUERY_ROW_COUNT)));
        }
        if (options.containsKey(OPTION_EXTRACT_TYPE)) {
            segmentsCheck.setSelected(CommonUtils.valueOf(DatabaseProducerSettings.ExtractType.class,
                CommonUtils.toString(options.get(OPTION_EXTRACT_TYPE)), DatabaseProducerSettings.ExtractType.SINGLE_QUERY)
                == DatabaseProducerSettings.ExtractType.SEGMENTS);
        }
        if (options.containsKey(OPTION_SEGMENT_SIZE)) {
            segmentSizeSpinner.setValue(Math.max(1, CommonUtils.toInt(options.get(OPTION_SEGMENT_SIZE), getInt(segmentSizeSpinner))));
        }
        updateEnablement();
    }

    /**
     * Puts the options into the source settings of a prepared transfer.
     */
    void apply(@NotNull DataTransferSettings settings) throws DBException {
        IDataTransferProducer<?>[] producers = settings.getInitProducers();
        if (producers == null || producers.length == 0
            || !(settings.getNodeSettings(producers[0]) instanceof DatabaseProducerSettings producerSettings)) {
            return;
        }
        producerSettings.setOpenNewConnections(newConnectionCheck.isSelected());
        producerSettings.setQueryRowCount(rowCountCheck.isSelected());
        producerSettings.setFetchSize(getInt(fetchSizeSpinner));
        producerSettings.setExtractType(segmentsCheck.isSelected()
            ? DatabaseProducerSettings.ExtractType.SEGMENTS : DatabaseProducerSettings.ExtractType.SINGLE_QUERY);
        producerSettings.setSegmentSize(getInt(segmentSizeSpinner));
    }

    /**
     * The options that differ from the usual reading, for the wizard summary.
     */
    void appendSummary(@NotNull StringBuilder summary) {
        if (!newConnectionCheck.isSelected()) {
            summary.append(TransferOptionsMessages.summary_extract_main_session).append('\n');
        }
        if (!rowCountCheck.isSelected()) {
            summary.append(TransferOptionsMessages.summary_extract_no_count).append('\n');
        }
        summary.append(NLS.bind(TransferOptionsMessages.summary_extract_fetch_size, getInt(fetchSizeSpinner))).append('\n');
        if (segmentsCheck.isSelected()) {
            summary.append(NLS.bind(TransferOptionsMessages.summary_extract_segments, getInt(segmentSizeSpinner))).append('\n');
        }
    }

    private static int getInt(@NotNull JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    @NotNull
    @Override
    public String getTitle() {
        return TransferOptionsMessages.extract_title;
    }

    @NotNull
    @Override
    public String getDescription() {
        return TransferOptionsMessages.extract_description;
    }

    @NotNull
    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void activate() {
        updateEnablement();
    }

    @Nullable
    @Override
    public String leave() {
        for (JSpinner spinner : new JSpinner[]{fetchSizeSpinner, segmentSizeSpinner}) {
            try {
                spinner.commitEdit();
            } catch (ParseException e) {
                // The spinner keeps its last valid value
            }
        }
        return null;
    }
}
