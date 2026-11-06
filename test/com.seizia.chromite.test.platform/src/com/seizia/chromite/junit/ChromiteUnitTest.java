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
package com.seizia.chromite.junit;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.junit.osgi.annotation.RunWithProduct;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mock;


@RunWithProduct("ChromiteUnitTest.product")
public abstract class ChromiteUnitTest extends ApplicationUnitTest {

    @Mock
    protected DBRProgressMonitor monitor;

    protected final String lineBreak = System.lineSeparator();

    @NotNull
    protected DBPDataSourceContainer configureTestContainer(@NotNull String driverID) {
        DBPDriver driver = DBWorkbench.getPlatform().getDataSourceProviderRegistry().findDriver(driverID);
        Assertions.assertNotNull(driver);
        DBPProject mockProject = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        Assertions.assertNotNull(mockProject);

        DBPConnectionConfiguration connectionConfiguration = new DBPConnectionConfiguration();
        DBPDataSourceContainer dataSourceContainer = new DataSourceDescriptor(
            mockProject.getDataSourceRegistry(),
            "test-datasource",
            driver,
            connectionConfiguration);
        dataSourceContainer.setName("Test DS");


        return dataSourceContainer;
    }

}
