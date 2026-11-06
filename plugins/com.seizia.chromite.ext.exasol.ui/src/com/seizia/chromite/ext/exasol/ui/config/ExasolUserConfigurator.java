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
package com.seizia.chromite.ext.exasol.ui.config;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.exasol.model.ExasolDataSource;
import com.seizia.chromite.ext.exasol.model.security.ExasolUser;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.UIUtils;

import java.util.Map;

public class ExasolUserConfigurator implements DBEObjectConfigurator<ExasolUser> {
    @NotNull
    @Override
    public ExasolUser configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull ExasolUser user, @NotNull Map<String, Object> options) {
        return new UITask<ExasolUser>() {
            @Override
            protected ExasolUser runTask() {
                ExasolUserDialog dialog = new ExasolUserDialog(UIUtils.getActiveWorkbenchShell(), (ExasolDataSource) container);
                if (dialog.open() != IDialogConstants.OK_ID) {
                    return null;
                }
                user.setName(dialog.getName());
                user.setDescription(dialog.getComment());
                switch (dialog.getUserType()) {
                    case KERBEROS:
                        user.setKerberosPrincipal(dialog.getKerberosPrincipal());
                        break;
                    case LDAP:
                        user.setDN(dialog.getLDAPDN());
                        break;
                    case LOCAL:
                        user.setPassword(dialog.getPassword());
                        break;
                }
                return user;
            }
        }.execute();
    }
}
