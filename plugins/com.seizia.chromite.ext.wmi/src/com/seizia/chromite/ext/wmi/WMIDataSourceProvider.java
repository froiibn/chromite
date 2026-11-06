/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.wmi;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.wmi.model.WMIDataSource;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceProvider;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPDriverLibrary;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.impl.AbstractDataSourceProvider;
import org.jkiss.wmi.service.WMIService;

import java.nio.file.Path;

public class WMIDataSourceProvider extends AbstractDataSourceProvider<WMIDataSource> {


    private boolean libLoaded = false;

    public WMIDataSourceProvider() {
        super(WMIDataSource.class);
    }

    @Override
    public long getFeatures()
    {
        return FEATURE_SCHEMAS;
    }

    @Override
    public String getConnectionURL(DBPDriver driver, DBPConnectionConfiguration connectionInfo) {
        return
            "wmi://" + connectionInfo.getServerName() +
                "/" + connectionInfo.getHostName() +
                "/" + connectionInfo.getDatabaseName();
    }

    @NotNull
    @Override
    public WMIDataSource openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        if (!libLoaded) {
            DBPDriver driver = container.getDriver();
            driver.getDriverLoader(container).loadDriver(monitor);
            loadNativeLib(driver);
            libLoaded = true;
        }
        return new WMIDataSource(container);
    }

    private void loadNativeLib(DBPDriver driver) throws DBException {
        for (DBPDriverLibrary libFile : driver.getDriverLibraries()) {
            if (libFile.matchesCurrentPlatform() && libFile.getType() == DBPDriverLibrary.FileType.lib) {
                Path localFile = libFile.getLocalFile();
                try {
                    if (localFile != null) {
                        WMIService.linkNative(localFile.toAbsolutePath().toString());
                    } else {
                        // Load dll from any accessible location
                        WMIService.linkNative();
                    }
                } catch (UnsatisfiedLinkError e) {
                    throw new DBException("Can't load native library '" + libFile.getDisplayName() + "'", e);
                }
            }
        }
    }

}
