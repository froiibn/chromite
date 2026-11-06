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
package com.seizia.chromite.ui.datadam;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.datadam.DDProductConfig;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.IWorkbenchWindowInitializer;
import com.seizia.chromite.ui.datadam.internal.DDUIMessages;
import com.seizia.chromite.ui.datadam.project.DDProjectUIManager;

import java.util.concurrent.atomic.AtomicBoolean;

public class DDDesktopInitializer implements IWorkbenchWindowInitializer {

    private static final Log log = Log.getLog(DDDesktopInitializer.class);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    @Override
    public void initializeWorkbenchWindow(@NotNull IWorkbenchWindowConfigurer configurer) {
        if (!DDProductConfig.isIntegrationEnabled() || !INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        AbstractJob startupJob = new AbstractJob(DDUIMessages.datadam_desktop_initialize_job) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    boolean tokenReady = DDDesktopTokenUIManager.getInstance().ensureTokenReady(monitor);
                    DDProjectUIManager.getInstance();
                    return tokenReady ? Status.OK_STATUS : Status.CANCEL_STATUS;
                } catch (DBException e) {
                    log.debug("DataDam Desktop initialization failed", e);
                    return Status.CANCEL_STATUS;
                }
            }
        };
        startupJob.setSystem(true);
        startupJob.schedule();
    }
}
