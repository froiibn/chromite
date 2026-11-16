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
package com.seizia.chromite.ext.db2.i.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.GeneralUtils;

import java.util.HashMap;
import java.util.Map;


public class DB2IDataSource extends GenericDataSource {

    private static final Log log = Log.getLog(DB2IDataSource.class);
    
    private static final String APPLICATION_NAME_PROP = "clientProgramName";

    public DB2IDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container, GenericMetaModel metaModel)
        throws DBException
    {
        super(monitor, container, metaModel, new DB2ISQLDialect());
    }
    
    @NotNull
    @Override
    protected Map<String, String> getInternalConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @Nullable JDBCExecutionContext context,
        @NotNull String purpose,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) {
        Map<String, String> props = new HashMap<>();
        if (!getContainer().getPreferenceStore().getBoolean(ModelPreferences.META_CLIENT_NAME_DISABLE)) {
            props.put(APPLICATION_NAME_PROP, GeneralUtils.getProductName());
        }
        // https://stackoverflow.com/questions/26032662/jt400-jar-disable-login-screen
        String promptKey = "prompt";
        if (connectionInfo.getProperties().get(promptKey) == null && DBWorkbench.getPlatform().getApplication().isHeadlessMode()) {
            props.put(promptKey, "false");
        }
        return props;
    }

}
