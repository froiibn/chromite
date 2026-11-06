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
package com.seizia.chromite.model.impl.jdbc;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.access.DBAAuthCredentials;
import com.seizia.chromite.model.access.DBAAuthModel;
import com.seizia.chromite.model.connection.DBPAuthModelDescriptor;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.Driver;
import java.util.Properties;

public class JDBCConnectionValidationTest {
    @Test
    public void rejectsBeforeDriverConnectAndFinishesAuthentication() throws Exception {
        DBPDataSourceContainer container = Mockito.mock(DBPDataSourceContainer.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(container.getDataSource()).thenReturn(null);
        Mockito.when(container.getDriverSubstitution()).thenReturn(null);
        DBPDriver driver = container.getDriver();
        DBPConnectionConfiguration configuration = new DBPConnectionConfiguration();
        configuration.setUrl("jdbc:h2:mem:test");
        DBPAuthModelDescriptor authDescriptor = Mockito.mock(DBPAuthModelDescriptor.class);
        Mockito.when(driver.getDataSourceProvider().detectConnectionAuthModel(driver, configuration)).thenReturn(authDescriptor);
        Mockito.when(authDescriptor.isApplicableTo(driver)).thenReturn(true);
        @SuppressWarnings("unchecked")
        DBAAuthModel<DBAAuthCredentials> authModel = Mockito.mock(DBAAuthModel.class);
        Mockito.when(authDescriptor.getInstance()).thenReturn(authModel);
        TestDataSource dataSource = Mockito.mock(TestDataSource.class, Mockito.withSettings()
            .useConstructor(container, Mockito.mock(SQLDialect.class)).defaultAnswer(Mockito.CALLS_REAL_METHODS));
        VoidProgressMonitor monitor = new VoidProgressMonitor();
        Driver jdbcDriver = Mockito.mock(Driver.class);
        Mockito.doReturn(jdbcDriver).when(dataSource).createDriverInstance(monitor, driver);
        Mockito.doAnswer(invocation -> {
            DBPConnectionConfiguration actualConfiguration = invocation.getArgument(3);
            actualConfiguration.setDatabaseName("changed");
            actualConfiguration.setUrl("jdbc:h2:mem:changed");
            Properties properties = invocation.getArgument(4);
            properties.setProperty("INIT", "CALL 2");
            return null;
        }).when(authModel).initAuthentication(Mockito.eq(monitor), Mockito.eq(dataSource), Mockito.any(),
            Mockito.eq(configuration), Mockito.any());
        Mockito.doAnswer(invocation -> {
            Properties properties = invocation.getArgument(3);
            Assertions.assertEquals("CALL 2", properties.getProperty("INIT"));
            properties.setProperty("INIT", "CALL 3");
            return null;
        }).when(dataSource).fixupConnectionProperties(Mockito.eq(monitor), Mockito.eq(configuration),
            Mockito.eq("jdbc:h2:mem:changed"), Mockito.any());
        Mockito.doAnswer(invocation -> {
            Properties properties = invocation.getArgument(1);
            Assertions.assertEquals("CALL 3", properties.getProperty("INIT"));
            properties.setProperty("INIT", "CALL 4");
            return null;
        }).when(dataSource).validateConnectionProperties(Mockito.eq("jdbc:h2:mem:changed"), Mockito.any());
        Mockito.doAnswer(invocation -> {
            Assertions.assertEquals("jdbc:h2:mem:changed", invocation.getArgument(1));
            Properties properties = invocation.getArgument(2);
            Assertions.assertEquals("CALL 4", properties.getProperty("INIT"));
            throw new DBCException("Not approved");
        }).when(dataSource).validateConnectionParameters(Mockito.eq(monitor), Mockito.anyString(), Mockito.any());

        DBCException exception = Assertions.assertThrows(DBCException.class,
            () -> dataSource.openConnection(monitor, null, configuration, "test"));

        Assertions.assertEquals("Not approved", exception.getMessage());
        Mockito.verify(dataSource, Mockito.never()).createDriverInstance(Mockito.any(), Mockito.any());
        Mockito.verify(jdbcDriver, Mockito.never()).connect(Mockito.anyString(), Mockito.any());
        Mockito.verify(authModel).endAuthentication(Mockito.eq(container), Mockito.eq(configuration), Mockito.any());
    }

    public abstract static class TestDataSource extends JDBCDataSource {
        public TestDataSource(@NotNull DBPDataSourceContainer container, @NotNull SQLDialect dialect) {
            super(container, dialect);
        }

        @Nullable
        @Override
        public Driver createDriverInstance(@NotNull DBRProgressMonitor monitor, @NotNull DBPDriver driver) throws DBCException {
            return super.createDriverInstance(monitor, driver);
        }

        @Override
        public void fixupConnectionProperties(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBPConnectionConfiguration connectionInfo,
            @NotNull String connectionUrl,
            @NotNull Properties connectionProperties
        ) throws DBException {
            super.fixupConnectionProperties(monitor, connectionInfo, connectionUrl, connectionProperties);
        }

        @Override
        public void validateConnectionProperties(@Nullable String url, @NotNull Properties connectProps) throws DBCException {
            super.validateConnectionProperties(url, connectProps);
        }

        @Override
        public void validateConnectionParameters(
            @NotNull DBRProgressMonitor monitor,
            @Nullable String url,
            @NotNull Properties connectProps
        ) throws DBCException {
            super.validateConnectionParameters(monitor, url, connectProps);
        }

        @NotNull
        @Override
        public Connection openConnection(
            @NotNull DBRProgressMonitor monitor,
            @Nullable JDBCExecutionContext context,
            @NotNull DBPConnectionConfiguration connectionInfo,
            @NotNull String purpose
        ) throws DBCException {
            return super.openConnection(monitor, context, connectionInfo, purpose);
        }
    }
}
