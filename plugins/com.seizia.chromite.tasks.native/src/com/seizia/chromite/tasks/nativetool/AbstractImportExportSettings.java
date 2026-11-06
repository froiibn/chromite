/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.tasks.nativetool;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.DBSStructContainer;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


public abstract class AbstractImportExportSettings<BASE_OBJECT extends DBSObject> extends AbstractNativeToolSettings<BASE_OBJECT> {
    private static final Log log = Log.getLog(AbstractImportExportSettings.class);

    private String outputFolderPattern;
    private String outputFilePattern;

    public AbstractImportExportSettings() {
        super();
    }

    public AbstractImportExportSettings(@NotNull DBPProject project) {
        super(project);
    }

    public String getOutputFolderPattern() {
        return outputFolderPattern;
    }

    public void setOutputFolderPattern(String outputFolderPattern) {
        this.outputFolderPattern = outputFolderPattern;
    }

    public String getOutputFilePattern() {
        return outputFilePattern;
    }

    public void setOutputFilePattern(String outputFilePattern) {
        this.outputFilePattern = outputFilePattern;
    }
    
    @Override
    public void loadSettingsFromInput(@NotNull List<BASE_OBJECT> inputObjects, @NotNull Map<String, Object> options) {
        super.loadSettingsFromInput(inputObjects, options);
        this.fillExportObjectsFromInput();
    }

    public void fillExportObjectsFromInput() {

    }

    @Override
    public void loadSettings(DBRRunnableContext runnableContext, DBPPreferenceStore store) throws DBException {
        this.outputFilePattern = store.getString("export.outputFilePattern");
        if (CommonUtils.isEmpty(this.outputFilePattern)) {
            this.outputFilePattern = "dump-${database}-${timestamp}.sql";
        }
        this.outputFolderPattern = CommonUtils.toString(store.getString("export.outputFolder"));
        if (CommonUtils.isEmpty(this.outputFolderPattern)) {
            this.outputFolderPattern = RuntimeUtils.getUserHomeDir().getAbsolutePath();
        }
        super.loadSettings(runnableContext, store);
    }

    @Override
    public void saveSettings(DBRRunnableContext runnableContext, DBPPreferenceStore preferenceStore) {
        super.saveSettings(runnableContext, preferenceStore);
        preferenceStore.setValue("export.outputFilePattern", this.outputFilePattern);
        preferenceStore.setValue("export.outputFolder", this.outputFolderPattern);
    }

    protected String resolveVars(@NotNull DBSObjectContainer container, Collection<? extends DBSSchema> schemas, Collection<? extends DBSTable> tables, String pattern) {
        return GeneralUtils.replaceVariables(pattern, name -> {
            switch (name) {
                case NativeToolUtils.VARIABLE_DATABASE:
                    return container.getName();
                case NativeToolUtils.VARIABLE_HOST:
                    return container.getDataSource().getContainer().getConnectionConfiguration().getHostName();
                case NativeToolUtils.VARIABLE_CONN_TYPE:
                    return container.getDataSource().getContainer().getConnectionConfiguration().getConnectionType().getId();
                case NativeToolUtils.VARIABLE_SCHEMA: {
                    final Iterator<? extends DBSSchema> iterator = schemas == null ? null : schemas.iterator();
                    if (iterator != null && iterator.hasNext()) {
                        return iterator.next().getName();
                    } else {
                        return container instanceof DBSStructContainer ? container.getName() : "null";
                    }
                }
                case NativeToolUtils.VARIABLE_TABLE: {
                    final Iterator<? extends DBSTable> iterator = tables == null ? null : tables.iterator();
                    if (iterator != null && iterator.hasNext()) {
                        return iterator.next().getName();
                    } else {
                        return "null";
                    }
                }
                case NativeToolUtils.VARIABLE_TIMESTAMP:
                    return RuntimeUtils.getCurrentTimeStamp();
                case NativeToolUtils.VARIABLE_DATE:
                    return RuntimeUtils.getCurrentDate();
                default:
                    return NativeToolUtils.replaceVariables(name);
            }
        });
    }

}
