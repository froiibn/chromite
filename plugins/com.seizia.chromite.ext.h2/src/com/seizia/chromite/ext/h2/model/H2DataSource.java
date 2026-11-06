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
package com.seizia.chromite.ext.h2.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.GenericExecutionContext;
import com.seizia.chromite.ext.h2.util.H2InitScriptValidator;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCRemoteInstance;
import com.seizia.chromite.model.impl.jdbc.exec.JDBCConnectionImpl;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceConnectionInit;
import com.seizia.chromite.utils.GeneralUtils;

import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * H2 datasource
 */
public class H2DataSource extends GenericDataSource {

    public static final String H2_URL_PREFIX_TCP = "jdbc:h2:tcp:";
    public static final String H2_URL_PREFIX_SSL = "jdbc:h2:ssl:";
    public static final String H2_URL_PREFIX = "jdbc:h2:";
    public static final String H2_DB_FILE_EXTENSION = ".mv.db";

    @Nullable
    private H2InitScriptValidator initScriptValidator;

    public H2DataSource(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container) throws DBException {
        super(monitor, container, new H2MetaModel(), new H2SQLDialect());
    }

    @Nullable
    @Override
    protected String getConnectionURL(@NotNull DBPConnectionConfiguration connectionInfo) throws DBException {
        String url = connectionInfo.getUrl();
        if (url == null || url.startsWith(H2_URL_PREFIX_TCP)) {
            return url;
        }
        if (url.startsWith(H2_URL_PREFIX)) {
            String filePath = url.substring(H2_URL_PREFIX.length());
            String params = null;
            int divPos = filePath.indexOf('?');
            if (divPos != -1) {
                params = filePath.substring(divPos);
                filePath = filePath.substring(0, divPos);
            }
            if (filePath.endsWith(H2_DB_FILE_EXTENSION)) {
                // Remove extension from database name
                url = H2_URL_PREFIX + filePath.substring(0, filePath.length() - H2_DB_FILE_EXTENSION.length());
                if (params != null) {
                    url += params;
                }
                return url;
            }
        }
        return super.getConnectionURL(connectionInfo);
    }

    @Override
    protected void validateConnectionParameters(
        @NotNull DBRProgressMonitor monitor,
        @Nullable String url,
        @NotNull Properties connectProps
    ) throws DBCException {
        var application = DBWorkbench.getPlatform().getApplication();
        Predicate<String> confirmation = script -> {
            if (application.isMultiuser()) {
                return false;
            }
            UIServiceConnectionInit service = DBWorkbench.getService(UIServiceConnectionInit.class);
            return service != null && service.confirmInitScript(getContainer(), url, script);
        };
        H2InitScriptValidator validator = getInitScriptValidator();
        validator.validate(url, connectProps, confirmation);
        validateBootstrapQueries(url, getContainer().getActualConnectionConfiguration().getBootstrap().getInitQueries().stream()
            .map(query -> GeneralUtils.replaceVariables(query, getContainer().getVariablesResolver(true)))
            .toList());
    }

    private void validateBootstrapQueries(@Nullable String url, @NotNull List<String> queries) throws DBCException {
        getInitScriptValidator().validateScripts(url, queries, scripts -> {
            if (DBWorkbench.getPlatform().getApplication().isMultiuser()) {
                return false;
            }
            UIServiceConnectionInit service = DBWorkbench.getService(UIServiceConnectionInit.class);
            return service != null && service.confirmInitScripts(getContainer(), url, scripts);
        });
    }

    @NotNull
    @Override
    protected JDBCExecutionContext createExecutionContext(@NotNull JDBCRemoteInstance instance, @NotNull String type) {
        return new GenericExecutionContext(instance, type) {
            @Override
            protected void validateBootstrapQueries(@NotNull List<String> queries) throws DBException {
                if (!queries.isEmpty()) {
                    String url = getConnectionURL(getContainer().getActualConnectionConfiguration());
                    H2DataSource.this.validateBootstrapQueries(url, queries);
                }
            }
        };
    }

    @NotNull
    private synchronized H2InitScriptValidator getInitScriptValidator() {
        // generic datasource opens its first connection from the superclass constructor
        if (initScriptValidator == null) {
            initScriptValidator = new H2InitScriptValidator();
        }
        return initScriptValidator;
    }

    @NotNull
    @Override
    protected JDBCConnectionImpl createConnection(
        @NotNull DBRProgressMonitor monitor,
        @NotNull JDBCExecutionContext context,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String taskTitle
    ) {
        String url = getContainer().getActualConnectionConfiguration().getUrl();
        if (url != null &&
            url.startsWith(H2_URL_PREFIX) &&
            !url.startsWith(H2_URL_PREFIX_TCP) &&
            !url.startsWith(H2_URL_PREFIX_SSL)
        ) {
            return new H2ConnectionImpl(context, monitor, purpose, taskTitle);
        }
        return super.createConnection(monitor, context, purpose, taskTitle);
    }
}
