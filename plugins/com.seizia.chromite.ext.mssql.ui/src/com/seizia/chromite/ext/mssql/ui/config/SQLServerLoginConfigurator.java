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
package com.seizia.chromite.ext.mssql.ui.config;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.model.SQLServerLogin;
import com.seizia.chromite.ext.mssql.ui.SQLServerUIMessages;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dialogs.EditUserDialog;

import java.util.Map;

public class SQLServerLoginConfigurator implements DBEObjectConfigurator<SQLServerLogin> {

    @NotNull
    @Override
    public SQLServerLogin configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull SQLServerLogin login, @NotNull Map<String, Object> options) {
        return new UITask<SQLServerLogin>() {
            @Override
            protected SQLServerLogin runTask() {
                EditUserDialog dialog = new EditUserDialog(UIUtils.getActiveWorkbenchShell(), SQLServerUIMessages.dialog_create_login_shell_title);
                if (dialog.open() != IDialogConstants.OK_ID) {
                    return null;
                }
                login.setLoginName(dialog.getName());
                login.setPassword(dialog.getPassword());
                return login;
            }
        }.execute();
    }
}
