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
package com.seizia.chromite.ui.datadam.project.job;

import com.dbeaver.datadam.share.api.model.DDSharedProject;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.datadam.internal.DDTrackingUIMessages;
import com.seizia.chromite.ui.datadam.project.navigator.DDProjectSyncPropertyTester;

/** Downloads the remote project selected in Preferences into the local workspace. */
public class DDProjectImportJob extends DDProjectOperationJob<DBPProject> {
    @NotNull
    private final DDSharedProject remoteProject;

    public DDProjectImportJob(@NotNull DDSharedProject remoteProject) {
        super(DDTrackingUIMessages.project_sync_import_job, DDTrackingUIMessages.project_sync_import_failed);
        this.remoteProject = remoteProject;
    }

    @Override
    @NotNull
    protected DBPProject perform(@NotNull DBRProgressMonitor monitor) throws DBException {
        DBPProject imported = uiManager.getProjectManager().importProject(remoteProject);
        DDProjectSyncPropertyTester.firePropertyChange();
        return imported;
    }
}
