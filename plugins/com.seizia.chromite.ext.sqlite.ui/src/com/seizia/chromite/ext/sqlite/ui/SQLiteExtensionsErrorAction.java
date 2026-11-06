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
package com.seizia.chromite.ext.sqlite.ui;

import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.core.runtime.IStatus;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionConfirmation;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionLoading;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionLoadingException;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionPermissionStore;
import com.seizia.chromite.ext.sqlite.model.SQLiteDataSource;
import com.seizia.chromite.ext.sqlite.ui.internal.SQLiteUIMessages;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.IErrorAction;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.actions.datasource.DataSourceHandler;
import com.seizia.chromite.ui.controls.resultset.IResultSetContainer;
import com.seizia.chromite.ui.controls.resultset.IResultSetErrorAction;

public class SQLiteExtensionsErrorAction implements IResultSetErrorAction, IAdapterFactory {
    @Override
    public boolean isVisible(@NotNull IResultSetContainer container, @NotNull IStatus status) {
        DBCExecutionContext context = container.getExecutionContext();
        if (context == null || !(context.getDataSource() instanceof SQLiteDataSource)) {
            return false;
        }
        return SQLiteExtensionLoading.isLoadingDenied(status.getException());
    }

    @Override
    public void perform(@NotNull IResultSetContainer container, @NotNull IStatus status) {
        DBCExecutionContext context = container.getExecutionContext();
        if (context != null) {
            request(context.getDataSource().getContainer());
        }
    }

    @Nullable
    @Override
    public <TYPE> TYPE getAdapter(@NotNull Object adaptableObject, @NotNull Class<TYPE> adapterType) {
        if (adapterType == IErrorAction.class && adaptableObject instanceof SQLiteExtensionLoadingException error
            && error.getExecutionContext() != null) {
            DBPDataSourceContainer container = error.getExecutionContext().getDataSource().getContainer();
            if (!container.isTemporary()) {
                return adapterType.cast(new ExtensionApprovalAction(container));
            }
        }
        return null;
    }

    @NotNull
    @Override
    public Class<?>[] getAdapterList() {
        return new Class<?>[] {IErrorAction.class};
    }

    private record ExtensionApprovalAction(@NotNull DBPDataSourceContainer container) implements IErrorAction {
        @NotNull
        @Override
        public String getLabel() {
            return SQLiteUIMessages.extension_settings_open;
        }

        @NotNull
        @Override
        public String getDescription() {
            return SQLiteUIMessages.extension_settings_loading_disabled;
        }

        @Override
        public void perform() {
            request(container);
        }
    }

    private static void request(@NotNull DBPDataSourceContainer container) {
        UIUtils.asyncExec(() -> {
            var application = DBWorkbench.getPlatform().getApplication();
            if (application.isHeadlessMode() || application.isMultiuser()) {
                return;
            }
            SQLiteExtensionConfirmation confirmation = DBWorkbench.getService(SQLiteExtensionConfirmation.class);
            if (confirmation == null) {
                return;
            }
            try {
                if (SQLiteExtensionLoading.requestEnable(container, SQLiteExtensionPermissionStore.getInstance(),
                    settings -> confirmation.confirmExtensionLoading(settings, false))) {
                    if (container.isConnected()) {
                        DataSourceHandler.reconnectDataSource(null, container);
                    } else {
                        DataSourceHandler.connectToDataSource(null, container, null);
                    }
                }
            } catch (DBException exception) {
                DBWorkbench.getPlatformUI().showError(SQLiteUIMessages.extension_confirmation_title,
                    SQLiteUIMessages.extension_settings_error, exception);
            }
        });
    }
}
