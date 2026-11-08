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
package com.seizia.chromite.tools.transfer.task;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.MonitorRunnableContext;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskManager;
import com.seizia.chromite.model.task.DBTTaskType;
import com.seizia.chromite.tools.sql.SQLScriptExecuteSettings;
import com.seizia.chromite.tools.sql.SQLTaskConstants;
import com.seizia.chromite.tools.transfer.DTConstants;
import com.seizia.chromite.tools.transfer.DataTransferPipe;
import com.seizia.chromite.tools.transfer.DataTransferSettings;
import com.seizia.chromite.tools.transfer.DataTransferState;
import com.seizia.chromite.tools.transfer.IDataTransferNode;
import com.seizia.chromite.tools.transfer.IDataTransferSettings;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingType;
import com.seizia.chromite.tools.transfer.database.DatabaseTransferConsumer;
import com.seizia.chromite.tools.transfer.registry.DataTransferNodeDescriptor;
import com.seizia.chromite.tools.transfer.registry.DataTransferProcessorDescriptor;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved data transfer tasks (Export Data, Import Data, Copy Tables) and SQL script tasks for a front end without the SWT
 * task wizards: save, read back for editing, and what the task list shows without connecting.
 *
 * 설계: 저장 형식은 SWT 마법사({@code DataTransferWizard.saveTaskState}·{@code saveConfiguration},
 * {@code TaskConfigurationWizard.saveConfigurationToTask})와 같다: {@code producers}·{@code consumers}는 노드 직렬화기의 위치,
 * {@code configuration}은 노드 설정 절(노드 클래스의 단순 이름), 노드·처리기 ID, 고른 처리기의 속성이다. 그래서 SWT에서 저장한 작업과
 * Swing에서 저장한 작업이 같은 처리기({@link DTTaskHandlerTransfer})로 돌고 서로 열린다. 다른 점 하나: SWT는 마법사가 대화상자 설정에서
 * 읽은 노드만 저장하지만({@code DataTransferSettings.saveNodeSettings}는 읽지 않은 노드의 원래 절을 복사함), Swing 마법사는 노드 설정을
 * 대화상자 설정에서 읽지 않고 화면 값을 직접 넣으므로 고른 producer·consumer 노드의 설정 객체를 그대로 저장한다.
 * 다시 읽기는 처리기와 같은 생성자({@code DataTransferSettings(monitor, task, …)})와 {@code loadNodeSettings}다(원본·대상 객체를 찾으므로 연결한다).
 * 목록 화면은 연결하지 않아야 하므로 연결과 대상 변경(비우기, 다시 만들기)은 저장된 JSON에서 바로 읽는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-database-tasks.md "데이터 전송 작업", "SQL 스크립트 작업")
 */
public final class DTSavedTasks {

    private static final Log log = Log.getLog(DTSavedTasks.class);

    // Keys of the saved task state (SWT DataTransferWizard.saveTaskState, DataTransferSettings.collectTaskInfo)
    private static final String PROP_PRODUCERS = "producers"; //$NON-NLS-1$
    private static final String PROP_CONSUMERS = "consumers"; //$NON-NLS-1$
    private static final String PROP_CONFIGURATION = "configuration"; //$NON-NLS-1$
    private static final String PROP_LOCATION = "location"; //$NON-NLS-1$
    private static final String PROP_ENTITY_ID = "entityId"; //$NON-NLS-1$
    private static final String PROP_DATA_SOURCE = "dataSource"; //$NON-NLS-1$
    private static final String PROP_MAPPINGS = "mappings"; //$NON-NLS-1$
    private static final String PROP_MAPPING_TYPE = "mappingType"; //$NON-NLS-1$
    private static final String PROP_TARGET_NAME = "targetName"; //$NON-NLS-1$
    private static final String PROP_TRUNCATE = "truncateBeforeLoad"; //$NON-NLS-1$
    // SQLScriptExecuteSettings.saveConfiguration / loadConfiguration (also the legacy single connection)
    private static final String PROP_DATA_SOURCES = "dataSources"; //$NON-NLS-1$
    private static final String PROP_LEGACY_DATA_SOURCE = "dataSourceContainer"; //$NON-NLS-1$

    /**
     * What a saved transfer does to existing target tables, read from its settings without connecting.
     *
     * @param truncate  the target tables are emptied before the load
     * @param recreated the target tables that are dropped and created again
     */
    public record TargetChanges(boolean truncate, @NotNull List<String> recreated) {
        public boolean isEmpty() {
            return !truncate && recreated.isEmpty();
        }
    }

    private DTSavedTasks() {
    }

    public static boolean isTransferTask(@NotNull DBTTaskType type) {
        return DTConstants.TASK_EXPORT.equals(type.getId()) || DTConstants.TASK_IMPORT.equals(type.getId());
    }

    public static boolean isScriptTask(@NotNull DBTTaskType type) {
        return SQLTaskConstants.TASK_SCRIPT_EXECUTE.equals(type.getId());
    }

    //////////////////////////////////////////////////////////
    // Data transfer tasks

    /**
     * Saves a transfer as a task: a new task with the name, or the settings (and the name) of an existing task. The task type
     * is that of a temporary task of the same transfer: import when the producer was chosen for given targets, export otherwise.
     *
     * @throws DBException another task has the name, or a node can't be saved (e.g. an import without a file)
     */
    @NotNull
    public static DBTTask saveTransferTask(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DataTransferSettings settings,
        @NotNull String name,
        @Nullable DBTTask existing
    ) throws DBException {
        DBTTaskManager taskManager = settings.getProject().getTaskManager();
        DBTTask task = existing;
        if (task == null) {
            String typeId = settings.isProducerOptional() ? DTConstants.TASK_IMPORT : DTConstants.TASK_EXPORT;
            task = taskManager.createTask(getTaskType(taskManager, typeId), name, null, null, new LinkedHashMap<>());
        }
        Map<String, Object> state = new LinkedHashMap<>();
        List<IDataTransferNode<?>> producers = new ArrayList<>();
        List<IDataTransferNode<?>> consumers = new ArrayList<>();
        for (DataTransferPipe pipe : settings.getDataPipes()) {
            if (pipe.getProducer() != null) {
                producers.add(pipe.getProducer());
            }
            if (pipe.getConsumer() != null) {
                consumers.add(pipe.getConsumer());
            }
        }
        MonitorRunnableContext context = new MonitorRunnableContext(monitor);
        DataTransferSettings.saveNodesLocation(context, task, state, producers, PROP_PRODUCERS);
        DataTransferSettings.saveNodesLocation(context, task, state, consumers, PROP_CONSUMERS);
        state.put(PROP_CONFIGURATION, saveConfiguration(settings));
        return storeTask(taskManager, task, name, existing, state);
    }

    /**
     * The configuration section of a transfer task (SWT {@code DataTransferWizard.saveConfiguration} for a task: only the
     * chosen nodes and processor).
     */
    @NotNull
    private static Map<String, Object> saveConfiguration(@NotNull DataTransferSettings settings) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxJobCount", settings.getMaxJobCount()); //$NON-NLS-1$
        config.put("showFinalMessage", settings.isShowFinalMessage()); //$NON-NLS-1$
        for (DataTransferNodeDescriptor node : new DataTransferNodeDescriptor[]{settings.getProducer(), settings.getConsumer()}) {
            IDataTransferSettings nodeSettings = node == null ? null : settings.getNodeSettings(node);
            if (nodeSettings != null) {
                Map<String, Object> section = new LinkedHashMap<>();
                nodeSettings.saveSettings(section);
                config.put(node.getNodeClass().getSimpleName(), section);
            }
        }
        if (settings.getProducer() != null) {
            config.put(DTConstants.PROP_PRODUCER_TYPE, settings.getProducer().getId());
        }
        if (settings.getConsumer() != null) {
            config.put(DTConstants.PROP_CONSUMER_TYPE, settings.getConsumer().getId());
        }
        DataTransferProcessorDescriptor processor = settings.getProcessor();
        Map<String, Object> processors = new LinkedHashMap<>();
        if (processor != null) {
            config.put(DTConstants.PROP_PROCESSOR_TYPE, processor.getId());
            Map<String, Object> processorSection = new LinkedHashMap<>();
            Map<String, Object> properties = settings.getProcessorPropsHistory().get(processor);
            if (!CommonUtils.isEmpty(properties)) {
                processorSection.put(DTConstants.PROP_NAME, String.join(",", properties.keySet()) + ","); //$NON-NLS-1$ //$NON-NLS-2$
                for (Map.Entry<String, Object> property : properties.entrySet()) {
                    processorSection.put(property.getKey(), CommonUtils.toString(property.getValue()));
                }
            }
            processors.put(processor.getFullId(), processorSection);
        }
        config.put(DTConstants.PROP_PROCESSORS_LIST, processors);
        return config;
    }

    /**
     * Creates the settings of a saved task: the {@link DataTransferSettings} task constructor, or that of a subclass.
     */
    @FunctionalInterface
    public interface SettingsFactory {
        @NotNull
        DataTransferSettings create(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBTTask task,
            @NotNull Log taskLog,
            @NotNull Map<String, Object> configuration,
            @NotNull DataTransferState state,
            boolean isTaskRunning);
    }

    /**
     * Reads a saved transfer as its handler does, for editing: finds the source and target objects (this connects) and
     * loads the node settings and mappings.
     *
     * @param factory creates the settings (a front end may run them with its own progress reporting)
     * @throws DBException the settings can't be read, or a source or target of the task is gone
     */
    @NotNull
    public static DataTransferSettings loadTransferTask(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBTTask task,
        @NotNull SettingsFactory factory
    ) throws DBException {
        DataTransferState state = new DataTransferState();
        DataTransferSettings settings;
        try {
            settings = factory.create(monitor, task, log, new HashMap<>(), state, false);
            settings.loadNodeSettings(monitor);
        } catch (RuntimeException e) {
            // DataTransferSettings reports a broken configuration (e.g. sources and targets that don't match) this way
            throw new DBException("Can't read the settings of task '" + task.getName() + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (state.hasErrors()) {
            Throwable first = state.getLoadErrors().getFirst();
            throw new DBException("Can't read the settings of task '" + task.getName() + "'", first); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (settings.getDataPipes().isEmpty() || settings.getProducer() == null || settings.getConsumer() == null) {
            throw new DBException("Task '" + task.getName() + "' has no source or destination"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return settings;
    }

    /**
     * What a saved transfer does to existing target tables (truncate, recreate), from its settings without connecting.
     */
    @NotNull
    public static TargetChanges getTargetChanges(@NotNull DBTTask task) {
        Map<String, Object> consumer = JSONUtils.getObject(
            JSONUtils.getObject(task.getProperties(), PROP_CONFIGURATION), DatabaseTransferConsumer.class.getSimpleName());
        List<String> recreated = new ArrayList<>();
        for (Object mapping : JSONUtils.getObject(consumer, PROP_MAPPINGS).values()) {
            if (mapping instanceof Map<?, ?> map && DatabaseMappingType.recreate.name().equals(map.get(PROP_MAPPING_TYPE))) {
                recreated.add(CommonUtils.toString(map.get(PROP_TARGET_NAME)));
            }
        }
        return new TargetChanges(CommonUtils.toBoolean(consumer.get(PROP_TRUNCATE)), recreated);
    }

    //////////////////////////////////////////////////////////
    // SQL script tasks

    /**
     * Reads the settings of a saved SQL script task (connections and script files).
     */
    @NotNull
    public static SQLScriptExecuteSettings loadScriptTask(@NotNull DBTTask task) throws DBException {
        SQLScriptExecuteSettings settings = new SQLScriptExecuteSettings();
        settings.loadConfiguration(task);
        return settings;
    }

    /**
     * Saves the settings as an SQL script task: a new task with the name, or the settings (and the name) of an existing task.
     *
     * @throws DBException another task has the name
     */
    @NotNull
    public static DBTTask saveScriptTask(
        @NotNull DBPProject project,
        @NotNull SQLScriptExecuteSettings settings,
        @NotNull String name,
        @Nullable DBTTask existing
    ) throws DBException {
        DBTTaskManager taskManager = project.getTaskManager();
        DBTTask task = existing != null ? existing
            : taskManager.createTask(getTaskType(taskManager, SQLTaskConstants.TASK_SCRIPT_EXECUTE), name, null, null, new LinkedHashMap<>());
        Map<String, Object> state = new LinkedHashMap<>();
        settings.saveConfiguration(state);
        return storeTask(taskManager, task, name, existing, state);
    }

    //////////////////////////////////////////////////////////
    // Common

    /**
     * The connection a saved transfer or script task works on, from its settings (no connecting): the source of an export or
     * copy, the target of an import, the first connection of a script task.
     */
    @Nullable
    public static DBPDataSourceContainer findTaskConnection(@NotNull DBTTask task) {
        Map<String, Object> properties = task.getProperties();
        DBPProject project = task.getProject();
        if (isScriptTask(task.getType())) {
            List<Map<String, Object>> dataSources = JSONUtils.getObjectList(properties, PROP_DATA_SOURCES);
            String id = dataSources.isEmpty() ? JSONUtils.getString(properties, PROP_LEGACY_DATA_SOURCE)
                : JSONUtils.getString(dataSources.getFirst(), PROP_DATA_SOURCE);
            return id == null ? null : project.getDataSourceRegistry().getDataSource(id);
        }
        if (!isTransferTask(task.getType())) {
            return null;
        }
        for (Map<String, Object> producer : JSONUtils.getObjectList(properties, PROP_PRODUCERS)) {
            Map<String, Object> location = JSONUtils.getObject(producer, PROP_LOCATION);
            String entityId = JSONUtils.getString(location, PROP_ENTITY_ID);
            if (entityId != null) {
                return DBUtils.findDataSourceByObjectId(project, entityId);
            }
            String dataSourceId = JSONUtils.getString(location, PROP_DATA_SOURCE);
            if (dataSourceId != null) {
                return project.getDataSourceRegistry().getDataSource(dataSourceId);
            }
        }
        Map<String, Object> consumer = JSONUtils.getObject(
            JSONUtils.getObject(properties, PROP_CONFIGURATION), DatabaseTransferConsumer.class.getSimpleName());
        String entityId = JSONUtils.getString(consumer, PROP_ENTITY_ID);
        return entityId == null ? null : DBUtils.findDataSourceByObjectId(project, entityId);
    }

    @NotNull
    private static DBTTaskType getTaskType(@NotNull DBTTaskManager taskManager, @NotNull String typeId) throws DBException {
        DBTTaskType type = taskManager.getRegistry().getTaskType(typeId);
        if (type == null) {
            throw new DBException("Task type " + typeId + " is not registered"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return type;
    }

    /**
     * Writes the state into the task (renaming an existing one) and adds a new task to the project's task list.
     */
    @NotNull
    private static DBTTask storeTask(
        @NotNull DBTTaskManager taskManager,
        @NotNull DBTTask task,
        @NotNull String name,
        @Nullable DBTTask existing,
        @NotNull Map<String, Object> state
    ) throws DBException {
        DBTTask other = taskManager.getTaskByName(name);
        if (other != null && other != existing) {
            throw new DBException("A task named '" + name + "' already exists"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (existing instanceof DBPNamedObject2 named) {
            named.setName(name);
        }
        task.setProperties(state);
        // Adds a new task to the project's task list and writes the tasks file
        taskManager.updateTaskConfiguration(task);
        return task;
    }
}
