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

import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.impl.PropertyDescriptor;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLPragmaHandler;
import com.seizia.chromite.model.sql.data.SQLQueryDataContainer;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.tools.transfer.DTConstants;
import com.seizia.chromite.tools.transfer.registry.DataTransferNodeDescriptor;
import com.seizia.chromite.tools.transfer.registry.DataTransferProcessorDescriptor;
import com.seizia.chromite.tools.transfer.registry.DataTransferRegistry;
import com.seizia.chromite.tools.transfer.stream.StreamTransferConsumer;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Handler of the {@code @export} script command in query windows: exports the result of the next query through the
 * data transfer engine, with the command's format and options ({@code plugin.xml}, extension point
 * {@code com.seizia.chromite.sqlCommand}, pragma {@code export}).
 *
 * <pre>
 * &#64;export {"type": "csv", "processor": {"delimiter": ";"}, "consumer": {"outputFolder": "D:/out"}}
 * SELECT * FROM orders;
 * </pre>
 *
 * 설계 (SWT {@code SQLPragmaExport}의 동작, 코드는 옮기지 않음):
 * <ul>
 *     <li>모델의 {@code SQLCommandExport}가 명령을 읽어 스크립트 문맥에 pragma로 두고, 실행 엔진이 다음 쿼리에서 이 처리기를 부른다
 *     ({@code SQLQueryExecuteJob.executeSingleQuery}). 처리기는 그 쿼리를 쿼리 창에서 실행하지 않고(쿼리 소비) 내보내기 마법사를 연다.
 *     SWT도 실행하지 않고 데이터 전송 마법사를 열었다. 처리기는 원래 삭제된 SWT 번들({@code data.transfer.ui})에 있어서 Swing에서는 명령이
 *     오류 없이 무시되고 다음 쿼리가 그냥 실행되었다(SWT 대조 B-S12).</li>
 *     <li>마법사는 {@link DataExportWizard#openForExportCommand}다. 형식({@code type}), 형식 속성({@code processor}), 출력({@code consumer}),
 *     원본({@code producer})을 미리 채운다. 사용자는 단계를 보고 Finish로 내보낸다(내보내기는 데이터 전송 엔진이 자기 연결에서 쿼리를 실행한다).</li>
 *     <li>형식 찾기: {@code stream.<type>} 처리기, {@code "searchSimilar": true}이면 AI 형식 이름·짧은 이름·파일 확장자도 본다(SWT와 같음).</li>
 *     <li>모르는 형식, 모르는 형식 속성, 마법사가 쓰지 않는 출력·원본 옵션은 오류로 멈춘다. SWT는 모르는 속성을 로그만 남기고 건너뛰었지만,
 *     그러면 사용자가 준 옵션이 조용히 무시된다. {@code includePipesConfiguration}(SWT 마법사의 파이프 설정 페이지)도 Swing 마법사에 그 페이지가 없어 거절한다.</li>
 *     <li>다음 쿼리는 다시 실행해도 되는 SELECT 하나여야 한다({@link DataExportWizard#canExportQuery}). 내보내기는 수동 커밋 쿼리 창과 다른
 *     연결에서 실행될 수 있어(새 연결이 기본) 데이터를 바꾸는 문장이 그 연결에서 커밋되면 안 되기 때문이다.</li>
 *     <li>검사는 실행 작업 스레드에서 하고 오류는 {@link DBException}으로 돌려 쿼리 창의 Messages에 보인다. 마법사는 EDT에서 연다.
 *     연 뒤에는 Messages에 "다음 쿼리를 내보내기 마법사로 보냈다"는 줄을 쓴다. 엔진은 소비한 쿼리 뒤에서 스크립트를 멈춘다(SWT와 같음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: {@code @export}가 조용히 무시되고 다음 쿼리가 실행되던 것 (docs/chromite/swing-data-transfer.md "@export 명령")
 */
public class SqlExportPragma implements SQLPragmaHandler {

    private static final String PARAM_TYPE = "type"; //$NON-NLS-1$
    private static final String PARAM_SEARCH_SIMILAR = "searchSimilar"; //$NON-NLS-1$
    private static final String PROCESSOR_ID_PREFIX = "stream."; //$NON-NLS-1$
    private static final Set<String> COMMAND_PARAMETERS = Set.of(PARAM_TYPE, PARAM_SEARCH_SIMILAR,
        DTConstants.PROP_PROCESSOR_TYPE, DTConstants.PROP_CONSUMER_TYPE, DTConstants.PROP_PRODUCER_TYPE);

    @Override
    public int processPragma(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSDataContainer container,
        @NotNull Map<String, Object> parameters
    ) throws DBException {
        checkKnown("@export", parameters.keySet(), COMMAND_PARAMETERS); //$NON-NLS-1$
        String type = JSONUtils.getString(parameters, PARAM_TYPE);
        if (CommonUtils.isEmpty(type)) {
            throw new DBException("@export needs a format: {\"type\": \"csv\"}. Formats: " + String.join(", ", getFormatNames())); //$NON-NLS-1$
        }
        DataTransferNodeDescriptor consumerNode = DataTransferRegistry.getInstance().getNodeByType(StreamTransferConsumer.class);
        DataTransferProcessorDescriptor processor = consumerNode == null ? null
            : findProcessor(consumerNode, type, JSONUtils.getBoolean(parameters, PARAM_SEARCH_SIMILAR, false));
        if (processor == null) {
            throw new DBException("@export: unknown format '" + type + "'. Formats: " + String.join(", ", getFormatNames())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!DataExportWizard.canExportQuery(container)) {
            throw new DBException("@export exports the result of the next statement, which must be one SELECT statement that does not change data"); //$NON-NLS-1$
        }
        Map<String, Object> consumer = getOptions(parameters, DTConstants.PROP_CONSUMER_TYPE);
        Map<String, Object> producer = getOptions(parameters, DTConstants.PROP_PRODUCER_TYPE);
        checkKnown("@export consumer", consumer.keySet(), DataExportWizard.EXPORT_CONSUMER_OPTIONS); //$NON-NLS-1$
        checkKnown("@export producer", producer.keySet(), DataExportWizard.EXPORT_PRODUCER_OPTIONS); //$NON-NLS-1$
        DataExportWizard.ExportCommand command = new DataExportWizard.ExportCommand(processor,
            convertProcessorProperties(processor, getOptions(parameters, DTConstants.PROP_PROCESSOR_TYPE)), consumer, producer);

        SwingUtilities.invokeLater(() -> DataExportWizard.openForExportCommand(findMainFrame(), container, command));
        if (container instanceof SQLQueryDataContainer queryContainer) {
            // The Messages tab of the query window (its script context writes there)
            queryContainer.getScriptContext().getOutputWriter().println(null, NLS.bind(EditorMessages.editor_sql_export_command, processor.getName()));
        }
        return RESULT_CONSUME_PRAGMA | RESULT_CONSUME_QUERY;
    }

    /**
     * The processor {@code stream.<type>}; with {@code searchSimilar} also by AI format name, short name or file
     * extension, ignoring case (the order of SWT's search).
     */
    @Nullable
    private static DataTransferProcessorDescriptor findProcessor(@NotNull DataTransferNodeDescriptor node, @NotNull String type, boolean searchSimilar) {
        DataTransferProcessorDescriptor processor = node.getProcessor(PROCESSOR_ID_PREFIX + type);
        if (!searchSimilar) {
            return processor;
        }
        for (DataTransferProcessorDescriptor descriptor : node.getProcessors()) {
            if (type.equalsIgnoreCase(descriptor.getAIFormatId())) {
                return descriptor;
            }
        }
        if (processor != null) {
            return processor;
        }
        for (DataTransferProcessorDescriptor descriptor : node.getProcessors()) {
            if (type.equalsIgnoreCase(descriptor.getShortId()) || type.equalsIgnoreCase(descriptor.getProcessorFileExtension())) {
                return descriptor;
            }
        }
        return null;
    }

    /**
     * The format's properties given in the command, converted to their types; an unknown property is an error.
     */
    @NotNull
    private static Map<String, Object> convertProcessorProperties(
        @NotNull DataTransferProcessorDescriptor processor,
        @NotNull Map<String, Object> values
    ) throws DBException {
        Map<String, Object> properties = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            DBPPropertyDescriptor descriptor = processor.getProperty(entry.getKey());
            if (descriptor == null) {
                unknown.add(entry.getKey());
            } else {
                properties.put(entry.getKey(), PropertyDescriptor.convertString(CommonUtils.toString(entry.getValue()), descriptor.getDataType()));
            }
        }
        if (!unknown.isEmpty()) {
            Set<String> known = new TreeSet<>();
            for (DBPPropertyDescriptor descriptor : processor.getProperties()) {
                known.add(descriptor.getId());
            }
            throw new DBException("@export processor: unknown option(s) " + String.join(", ", unknown) + " for " + processor.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Options: " + String.join(", ", known)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return properties;
    }

    @NotNull
    private static Map<String, Object> getOptions(@NotNull Map<String, Object> parameters, @NotNull String name) throws DBException {
        Object value = parameters.get(name);
        if (value != null && !(value instanceof Map)) {
            throw new DBException("@export: \"" + name + "\" must be an object: {\"" + name + "\": {...}}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return JSONUtils.getObject(parameters, name);
    }

    private static void checkKnown(@NotNull String what, @NotNull Collection<String> given, @NotNull Set<String> known) throws DBException {
        List<String> unknown = new ArrayList<>();
        for (String key : given) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new DBException(what + ": option(s) not supported in this version: " + String.join(", ", unknown) //$NON-NLS-1$ //$NON-NLS-2$
                + ". Supported: " + String.join(", ", new TreeSet<>(known))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The {@code type} names of the file formats ({@code stream.<type>}).
     */
    @NotNull
    private static Set<String> getFormatNames() {
        Set<String> names = new TreeSet<>();
        DataTransferNodeDescriptor node = DataTransferRegistry.getInstance().getNodeByType(StreamTransferConsumer.class);
        if (node != null) {
            for (DataTransferProcessorDescriptor descriptor : node.getProcessors()) {
                if (descriptor.getId().startsWith(PROCESSOR_ID_PREFIX)) {
                    names.add(descriptor.getId().substring(PROCESSOR_ID_PREFIX.length()));
                }
            }
        }
        return names;
    }

    @Nullable
    private static Frame findMainFrame() {
        for (Frame frame : Frame.getFrames()) {
            if (frame instanceof MainFrame mainFrame && mainFrame.isDisplayable()) {
                return mainFrame;
            }
        }
        return null;
    }
}
