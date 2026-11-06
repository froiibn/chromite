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
package com.seizia.chromite.ui.services;

import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.core.CoreMessages;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.preferences.ConfirmedActionsStore;
import com.seizia.chromite.model.runtime.RunnableWithResult;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceConnectionInit;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dialogs.ExecutionConfirmationDialog;
import org.jkiss.utils.SecurityUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UIServiceConnectionInitImpl implements UIServiceConnectionInit {
    private static final String APPROVALS_FILE_NAME = "confirmed_connection_init_scripts.json";
    private static final Object APPROVALS_LOCK = new Object();

    @Override
    public boolean confirmInitScript(@NotNull DBPDataSourceContainer container, @Nullable String url, @NotNull String script) {
        return confirmInitScripts(container, url, List.of(script));
    }

    @Override
    public boolean confirmInitScripts(
        @NotNull DBPDataSourceContainer container,
        @Nullable String url,
        @NotNull List<String> scripts
    ) {
        List<String> approvedScripts = List.copyOf(scripts);
        if (DBWorkbench.getPlatform().getApplication().isHeadlessMode()) {
            return false;
        }
        ConfirmedActionsStore approvals = new ConfirmedActionsStore(
            DBWorkbench.getPlatform().getGlobalConfigurationFile(APPROVALS_FILE_NAME)
        );
        String key = approvalKey(container, url, approvedScripts);
        try {
            synchronized (APPROVALS_LOCK) {
                if (approvals.contains(key)) {
                    return true;
                }
            }
        } catch (DBException exception) {
            Log.getLog(UIServiceConnectionInitImpl.class).error("Cannot read connection initialization approvals", exception);
            return false;
        }
        boolean approved = Boolean.TRUE.equals(UIUtils.syncExec(new RunnableWithResult<Boolean>() {
            @NotNull
            @Override
            public Boolean runWithResult() {
                Shell shell = UIUtils.getActiveWorkbenchShell();
                if (shell == null || shell.isDisposed()) {
                    return false;
                }
                Map<String, String> execution = new LinkedHashMap<>();
                for (int index = 0; index < approvedScripts.size(); index++) {
                    String label = CoreMessages.connection_init_confirmation_script;
                    if (approvedScripts.size() > 1) {
                        label += " " + (index + 1);
                    }
                    execution.put(label, approvedScripts.get(index));
                }
                ExecutionConfirmationDialog dialog = new ExecutionConfirmationDialog(
                    shell,
                    CoreMessages.connection_init_confirmation_title,
                    CoreMessages.connection_init_confirmation_message,
                    CoreMessages.connection_init_confirmation_allow,
                    Map.of(CoreMessages.connection_init_confirmation_connection, container.getName()),
                    execution
                );
                if (url != null) {
                    dialog.setAdditionalDetails(Map.of(CoreMessages.connection_init_confirmation_url, url));
                }
                return dialog.open() == Window.OK;
            }
        }));
        if (approved) {
            try {
                synchronized (APPROVALS_LOCK) {
                    approvals.add(key);
                }
            } catch (DBException exception) {
                Log.getLog(UIServiceConnectionInitImpl.class).error("Cannot save connection initialization approval", exception);
                return false;
            }
        }
        return approved;
    }

    @NotNull
    private static String approvalKey(@NotNull DBPDataSourceContainer container, @Nullable String url, @NotNull List<String> scripts) {
        return SecurityUtils.sha256Hex(JSONUtils.GSON.toJson(new Object[] {
            container.getProject().getAbsolutePath().toAbsolutePath().normalize().toString(),
            container.getId(),
            container.getDriver().getFullId(),
            url,
            scripts
        }));
    }
}
