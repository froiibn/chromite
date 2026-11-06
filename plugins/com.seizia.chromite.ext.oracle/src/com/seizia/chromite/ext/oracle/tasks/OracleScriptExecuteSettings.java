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
package com.seizia.chromite.ext.oracle.tasks;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.oracle.oci.OCIUtils;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPNativeClientLocation;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.tasks.nativetool.AbstractScriptExecuteSettings;

public class OracleScriptExecuteSettings extends AbstractScriptExecuteSettings<DBSObject> {

    private static final Log log = Log.getLog(OracleScriptExecuteSettings.class);

    public OracleScriptExecuteSettings() {
    }

    public OracleScriptExecuteSettings(@NotNull DBPProject project) {
        super(project);
    }

    @Override
    public DBPNativeClientLocation findNativeClientHome(String clientHomeId) {
        return OCIUtils.getOraHome(clientHomeId);
    }

    @Override
    public void loadSettings(DBRRunnableContext runnableContext, DBPPreferenceStore store) throws DBException {
        super.loadSettings(runnableContext, store);
    }

    @Override
    public void saveSettings(DBRRunnableContext runnableContext, DBPPreferenceStore store) {
        super.saveSettings(runnableContext, store);
    }
}
