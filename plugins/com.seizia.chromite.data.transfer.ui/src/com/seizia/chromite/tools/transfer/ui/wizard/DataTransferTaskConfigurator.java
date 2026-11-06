/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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

package com.seizia.chromite.tools.transfer.ui.wizard;

import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.impl.DataSourceContextProvider;
import com.seizia.chromite.model.navigator.*;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLScriptContext;
import com.seizia.chromite.model.sql.data.SQLQueryDataContainer;
import com.seizia.chromite.model.struct.*;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskType;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceSQL;
import com.seizia.chromite.tasks.ui.DBTTaskConfigPanel;
import com.seizia.chromite.tasks.ui.DBTTaskConfigPanelProvider;
import com.seizia.chromite.tasks.ui.DBTTaskConfigurator;
import com.seizia.chromite.tasks.ui.wizard.TaskConfigurationWizard;
import com.seizia.chromite.tools.transfer.*;
import com.seizia.chromite.tools.transfer.database.DatabaseTransferConsumer;
import com.seizia.chromite.tools.transfer.database.DatabaseTransferProducer;
import com.seizia.chromite.tools.transfer.internal.DTMessages;
import com.seizia.chromite.tools.transfer.ui.handlers.DataImportPropertyTester;
import com.seizia.chromite.tools.transfer.ui.internal.DTUIMessages;
import com.seizia.chromite.ui.BaseThemeSettings;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.UIWidgets;
import com.seizia.chromite.ui.navigator.NavigatorUtils;
import com.seizia.chromite.ui.navigator.dialogs.ObjectBrowserDialog;
import org.jkiss.utils.CommonUtils;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * Data transfer task configurator
 */
public class DataTransferTaskConfigurator implements DBTTaskConfigurator, DBTTaskConfigPanelProvider {

    private static final Log log = Log.getLog(DataTransferTaskConfigurator.class);

    @Override
    public ConfigPanel createInputConfigurator(DBRRunnableContext runnableContext, @NotNull DBTTaskType taskType) {
        return new ConfigPanel(runnableContext, taskType);
    }

    @Override
    public TaskConfigurationWizard<?> createTaskConfigWizard(@NotNull DBTTask taskConfiguration) {
        return DataTransferWizard.openWizard(taskConfiguration);
    }

    private static class ConfigPanel implements DBTTaskConfigPanel {

        private DBRRunnableContext runnableContext;
        private DBTTaskType taskType;
        private Table objectsTable;
        private DBPProject currentProject;
        private DataTransferWizard dtWizard;

        ConfigPanel(DBRRunnableContext runnableContext, DBTTaskType taskType) {
            this.runnableContext = runnableContext;
            this.taskType = taskType;
            this.currentProject = NavigatorUtils.getSelectedProject();
        }

        public DBPDataSource getLastDataSource() {
            int itemCount = objectsTable.getItemCount();
            if (itemCount <= 0) return null;
            DataTransferPipe pipe = (DataTransferPipe) objectsTable.getItem(itemCount - 1).getData();
            DBSObject databaseObject = getTableNode(pipe).getDatabaseObject();
            return databaseObject == null ? null : databaseObject.getDataSource();
        }

        @Override
        public void createControl(Composite parent, TaskConfigurationWizard wizard, Runnable propertyChangeListener) {
            dtWizard = (DataTransferWizard) wizard;
            boolean isExport = isExport();

            Composite group = UIUtils.createComposite(parent, 1);
            UIUtils.createControlLabel(
                group,
                DTConstants.TASK_EXPORT.equals(taskType.getId()) ?
                    DTUIMessages.data_transfer_task_configurator_group_label_export_tables :
                    DTUIMessages.data_transfer_task_configurator_group_label_import_into
            );
            objectsTable = new Table(group, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
            objectsTable.setLayoutData(new GridData(GridData.FILL_BOTH));
            objectsTable.setHeaderVisible(true);
            UIUtils.createTableColumn(objectsTable, SWT.NONE, DTUIMessages.data_transfer_task_configurator_table_column_text_object);
            UIUtils.createTableColumn(objectsTable, SWT.NONE, DTUIMessages.data_transfer_task_configurator_table_column_text_data_source);
            UIWidgets.createTableContextMenu(objectsTable, null);

            Composite buttonsPanel = UIUtils.createComposite(group, isExport ? 4 : 3);
            UIUtils.createDialogButton(
                buttonsPanel,
                DTUIMessages.data_transfer_task_configurator_dialog_button_label_add_table,
                SelectionListener.widgetSelectedAdapter(e -> {
                    Class<?> tableClass = isExport ? DBSDataContainer.class : DBSDataManipulator.class;
                    DBNProjectDatabases rootNode = currentProject.getNavigatorModel().getRoot()
                        .getProjectNode(currentProject).getDatabases();
                    DBNNode selNode = null;
                    if (objectsTable.getItemCount() > 0) {
                        DBPDataSource lastDataSource = getLastDataSource();
                        if (lastDataSource != null) {
                            selNode = rootNode.getDataSource(lastDataSource.getContainer().getId());
                        }
                    }
                    List<DBNNode> tables = ObjectBrowserDialog.selectObjects(
                        group.getShell(),
                        isExport ?
                            DTUIMessages.data_transfer_task_configurator_tables_title_choose_source :
                            DTUIMessages.data_transfer_task_configurator_tables_title_choose_target,
                        rootNode,
                        CommonUtils.singletonOrEmpty(selNode),
                        new Class[]{DBSInstance.class, DBSObjectContainer.class, tableClass},
                        new Class[]{tableClass},
                        null,
                        isExport ? null : node -> !(node instanceof DBNDatabaseNode databaseNode)
                            || DataImportPropertyTester.supportsImport(databaseNode.getDataSource()));
                    if (tables != null) {
                        for (DBNNode node : tables) {
                            if (node instanceof DBNDatabaseNode dbNode) {
                                DBSObject object = dbNode.getObject();
                                DataTransferPipe pipe = new DataTransferPipe(
                                    isExport ? new DatabaseTransferProducer((DBSDataContainer) object) : null,
                                    isExport ? null : new DatabaseTransferConsumer((DBSDataManipulator) object));
                                addPipeToTable(pipe);
                            }
                        }
                        updateSettings(propertyChangeListener);
                    }
                }
            ));
            if (isExport) {
                UIUtils.createDialogButton(buttonsPanel, DTUIMessages.data_transfer_task_configurator_dialog_button_label_add_query, SelectionListener.widgetSelectedAdapter(e -> {
                        DBSObject dataSourceObject = null;
                        DBPDataSource dataSource = null;

                        DBNProjectDatabases rootNode = currentProject.getNavigatorModel().getRoot().getProjectNode(currentProject).getDatabases();
                        DBNNode selNode = null;
                        if (objectsTable.getItemCount() > 0) {
                            DBPDataSource lastDataSource = getLastDataSource();
                            if (lastDataSource != null) {
                                selNode = rootNode.getDataSource(lastDataSource.getContainer().getId());
                            }
                        }
                        DBNNode node = ObjectBrowserDialog.selectObject(
                            group.getShell(),
                            DTUIMessages.data_transfer_task_configurator_tables_title_choose_source,
                            rootNode,
                            selNode,
                            new Class[]{DBSInstance.class, DBSObjectContainer.class},
                            new Class[]{DBPDataSource.class, DBSCatalog.class, DBSSchema.class},
                            null);

                        if (node != null) {
                            if (node instanceof DBNDataSource) {
                                dataSourceObject = ((DBNDataSource) node).getDataSource();
                                dataSource = ((DBNDataSource) node).getDataSource();
                            } else if (node instanceof DBNDatabaseItem) {
                                dataSourceObject = ((DBNDatabaseItem) node).getObject();
                                dataSource = dataSourceObject.getDataSource();
                            } else {
                                log.debug("Unhandled node type: " + node);
                                return;
                            }
                        }

                        if (dataSource != null) {
                            DBPDataSourceContainer dataSourceContainer = DBUtils.getContainer(dataSource);

                            if (dataSourceContainer != null && !dataSourceContainer.isConnected()) {
                                try {
                                    runnableContext.run(true, true, monitor -> {
                                        try {
                                            dataSourceContainer.connect(monitor, true, true);
                                        } catch (DBException ex) {
                                            throw new InvocationTargetException(ex);
                                        }
                                    });
                                } catch (InvocationTargetException ex) {
                                    DBWorkbench.getPlatformUI().showError(DTUIMessages.data_transfer_task_configurator_title_error_opening_data_source,
                                            DTUIMessages.data_transfer_task_configurator_message_error_while_opening_data_source, ex);
                                    return;
                                } catch (InterruptedException ex) {
                                    return;
                                }
                            }

                            String newInstanceName;
                            String newObjectName;

                            if (dataSourceObject instanceof DBSCatalog) {
                                newInstanceName = dataSourceObject.getName();
                                newObjectName = null;
                            } else if (dataSourceObject instanceof DBSSchema) {
                                DBSObject parentObject = dataSourceObject.getParentObject();
                                newInstanceName = parentObject instanceof DBSCatalog ? parentObject.getName() : null;
                                newObjectName = dataSourceObject.getName();
                            } else {
                                // Use default database and schema
                                newInstanceName = null;
                                newObjectName = null;
                            }

                            DataSourceContextProvider contextProvider = new DataSourceContextProvider(dataSourceObject);
                            DBCExecutionContext executionContext = contextProvider.getExecutionContext();

                            String oldInstanceName = null;
                            String oldObjectName = null;

                            if (executionContext instanceof DBCExecutionContextDefaults) {
                                DBCExecutionContextDefaults<?, ?> contextDefaults = ((DBCExecutionContextDefaults<?, ?>) executionContext);
                                DBSCatalog defaultCatalog = contextDefaults.getDefaultCatalog();
                                if (defaultCatalog != null) {
                                    oldInstanceName = defaultCatalog.getName();
                                }
                                DBSSchema defaultSchema = contextDefaults.getDefaultSchema();
                                if (defaultSchema != null) {
                                    oldObjectName = defaultSchema.getName();
                                }
                            }

                            try {
                                DBExecUtils.setExecutionContextDefaults(new VoidProgressMonitor(), dataSource, executionContext, newInstanceName, null, newObjectName);
                            } catch (DBException ex) {
                                log.error("Error setting context defaults", ex);
                                return;
                            }

                            UIServiceSQL serviceSQL = DBWorkbench.getService(UIServiceSQL.class);
                            if (serviceSQL != null) {
                                String query = serviceSQL.openSQLEditor(contextProvider, DTUIMessages.data_transfer_task_configurator_sql_query_title, DBIcon.TREE_SCRIPT, "");
                                if (query != null) {
                                    SQLScriptContext scriptContext = new SQLScriptContext(null, contextProvider, null, new PrintWriter(System.err, true), null);
                                    SQLQueryDataContainer container = new SQLQueryDataContainer(contextProvider, new SQLQuery(dataSource, query), scriptContext, log);
                                    DatabaseTransferProducer producer = new DatabaseTransferProducer(container);
                                    producer.setDefaultCatalog(newInstanceName);
                                    producer.setDefaultSchema(newObjectName);
                                    DataTransferPipe pipe = new DataTransferPipe(producer, null);
                                    addPipeToTable(pipe);
                                    updateSettings(propertyChangeListener);
                                }
                            }

                            try {
                                DBExecUtils.setExecutionContextDefaults(new VoidProgressMonitor(), dataSource, executionContext, oldInstanceName, null, oldObjectName);
                            } catch (DBException ex) {
                                log.error("Error setting context defaults", ex);
                            }
                        }
                    }));
            }
            Button editButton = UIUtils.createDialogButton(buttonsPanel, DTMessages.data_transfer_wizard_settings_button_edit, SelectionListener.widgetSelectedAdapter(e -> {
                    TableItem item = objectsTable.getItem(objectsTable.getSelectionIndex());
                    DataTransferPipe pipe = (DataTransferPipe) item.getData();
                    IDataTransferProducer<?> producer = pipe.getProducer();
                    if (producer instanceof DatabaseTransferProducer && producer.getDatabaseObject() instanceof SQLQueryDataContainer) {
                        DBPDataSource dataSource = producer.getDatabaseObject().getDataSource();
                        DataSourceContextProvider contextProvider = new DataSourceContextProvider(producer.getDatabaseObject());

                        UIServiceSQL serviceSQL = DBWorkbench.getService(UIServiceSQL.class);
                        if (serviceSQL != null) {

                            String query = serviceSQL.openSQLEditor(
                                contextProvider,
                                DTUIMessages.data_transfer_task_configurator_sql_query_title,
                                DBIcon.TREE_SCRIPT,
                                ((SQLQueryDataContainer) producer.getDatabaseObject()).getQuery().getText());
                            if (query != null) {
                                SQLQueryDataContainer container = (SQLQueryDataContainer)producer.getDatabaseObject();
                                container.setQuery(new SQLQuery(dataSource, query));
                                IDataTransferNode node = getTableNode(pipe);
                                item.setText(node.getObjectName());
                            }
                        }
                    }
                }));
            Button removeButton = UIUtils.createDialogButton(buttonsPanel, DTUIMessages.data_transfer_task_configurator_dialog_button_label_remove, SelectionListener.widgetSelectedAdapter(e -> {
                    DataTransferPipe object = (DataTransferPipe) objectsTable.getItem(objectsTable.getSelectionIndex()).getData();
                    if (UIUtils.confirmAction(
                        DTUIMessages.data_transfer_task_configurator_confirm_action_title,
                        NLS.bind(DTUIMessages.data_transfer_task_configurator_confirm_action_question,
                        CommonUtils.truncateString(getTableNode(object).getObjectName(), 255))))
                    {
                        objectsTable.remove(objectsTable.getSelectionIndex());
                        updateSettings(propertyChangeListener);
                    }
                }));
            editButton.setEnabled(false);
            removeButton.setEnabled(false);

            objectsTable.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
                    int selectionIndex = objectsTable.getSelectionIndex();
                    DataTransferPipe pipe = (selectionIndex >= 0) ?
                        (DataTransferPipe) objectsTable.getItem(selectionIndex).getData() : null;
                    editButton.setEnabled(
                        pipe != null &&
                        pipe.getProducer() instanceof DatabaseTransferProducer &&
                        pipe.getProducer().getDatabaseObject() instanceof SQLQueryDataContainer);

                    removeButton.setEnabled(pipe != null);
                }));
        }

        private void updateSettings(Runnable propertyChangeListener) {
            saveSettings();
            propertyChangeListener.run();
            UIUtils.asyncExec(() -> UIUtils.packColumns(objectsTable, true));
        }

        private boolean isExport() {
            return taskType.getId().equals(DTConstants.TASK_EXPORT);
        }

        @Override
        public void loadSettings() {
            dtWizard.loadNodeSettings();

            DataTransferSettings settings = dtWizard.getSettings();

            for (DataTransferPipe pipe : settings.getDataPipes()) {
                addPipeToTable(pipe);
            }
            UIUtils.asyncExec(() -> UIUtils.packColumns(objectsTable, true));
        }

        private void addPipeToTable(DataTransferPipe pipe) {
            IDataTransferNode node = getTableNode(pipe);
            TableItem item = new TableItem(objectsTable, SWT.NONE);
            item.setData(pipe);
            item.setImage(0, SwtIcons.getImage(node.getObjectIcon()));
            item.setText(0, CommonUtils.getSingleLineString(CommonUtils.toString(node.getObjectName(), "?")));

            if (node.getDataSourceContainer() != null) {
                item.setText(1, node.getDataSourceContainer().getName());
                DBPImage dsIcon = node.getObjectContainerIcon();
                if (dsIcon != null) {
                    item.setImage(1, SwtIcons.getImage(dsIcon));
                }
            }
            if (node.getDatabaseObject() == null) {
                item.setBackground(BaseThemeSettings.instance.colorError);
            }
        }

        private IDataTransferNode getTableNode(DataTransferPipe pipe) {
            return isExport() ? pipe.getProducer() : pipe.getConsumer();
        }

        @Override
        public void saveSettings() {
            if (objectsTable == null) {
                return;
            }

            DataTransferSettings settings = dtWizard.getSettings();

            // Save from config table
            List<DataTransferPipe> dataPipes = new ArrayList<>();
            for (TableItem item : objectsTable.getItems()) {
                dataPipes.add((DataTransferPipe) item.getData());
            }
            settings.setDataPipes(dataPipes, isExport());
            dtWizard.loadSettings();
        }

        @Override
        public boolean isComplete() {
            if (objectsTable.getItemCount() == 0) {
                return false;
            }
            for (DataTransferPipe pipe : dtWizard.getSettings().getDataPipes()) {
                if (!dtWizard.getSettings().isProducerOptional()) {
                    if (pipe.getProducer() == null || !pipe.getProducer().isConfigurationComplete()) {
                        return false;
                    }
                }
                if (!dtWizard.getSettings().isConsumerOptional()) {
                    if (pipe.getConsumer() == null || !pipe.getConsumer().isConfigurationComplete()) {
                        return false;
                    }
                }
            }

            return objectsTable.getItemCount() > 0;
        }

        @Override
        public String getErrorMessage() {
            if (objectsTable.getItemCount() == 0) {
                return DTUIMessages.data_transfer_error_no_objects_selected;
            }
            for (DataTransferPipe pipe : dtWizard.getSettings().getDataPipes()) {
                if (!dtWizard.getSettings().isProducerOptional()) {
                    if (pipe.getProducer() == null || !pipe.getProducer().isConfigurationComplete()) {
                        return NLS.bind(DTUIMessages.data_transfer_error_source_not_specified,
                            (pipe.getConsumer() == null ? "?" : pipe.getConsumer().getObjectName()));
                    }
                }
                if (!dtWizard.getSettings().isConsumerOptional()) {
                    if (pipe.getConsumer() == null || !pipe.getConsumer().isConfigurationComplete()) {
                        return NLS.bind(DTUIMessages.data_transfer_error_target_not_specified,
                            (pipe.getProducer() == null ? "?" : pipe.getProducer().getObjectName()));
                    }
                }
            }
            return null;
        }
    }

}
