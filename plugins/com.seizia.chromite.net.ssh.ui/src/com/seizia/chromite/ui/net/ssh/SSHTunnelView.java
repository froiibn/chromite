/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ui.net.ssh;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.net.ssh.SSHSession;
import com.seizia.chromite.model.net.ssh.SSHSessionController;
import com.seizia.chromite.model.net.ssh.SSHTunnelImpl;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.LoadingJob;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.controls.ListContentProvider;
import com.seizia.chromite.ui.navigator.itemlist.ObjectListControl;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class SSHTunnelView extends ViewPart {
    private ObjectListControl<SSHSession> viewer;
    private AbstractJob updateJob;

    @Override
    public void createPartControl(Composite parent) {
        viewer = new ObjectListControl<>(parent, SWT.SHEET, new ListContentProvider()) {
            @NotNull
            @Override
            protected String getListConfigId(List<Class<?>> classList) {
                return getClass().getName();
            }

            @Override
            protected LoadingJob<Collection<SSHSession>> createLoadService(boolean forUpdate) {
                return null;
            }
        };
        viewer.appendListData(List.of());
        viewer.getControl().addPaintListener(e -> {
            if (viewer.getListData().isEmpty()) {
                UIUtils.drawMessageOverControl(viewer.getControl(), e, "No active SSH tunnels", 0);
            }
        });

        updateJob = new AbstractJob("Refresh SSH tunnels") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                refreshTunnels();
                schedule(1000);
                return Status.OK_STATUS;
            }
        };
        updateJob.setUser(false);
        updateJob.setSystem(true);
        updateJob.schedule();
    }

    @Override
    public void setFocus() {
        viewer.setFocus();
    }

    @Override
    public void dispose() {
        if (updateJob != null) {
            updateJob.cancel();
            updateJob = null;
        }

        super.dispose();
    }

    private void refreshTunnels() {
        final DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();

        if (project == null) {
            UIUtils.asyncExec(() -> viewer.clearListData());
            return;
        }

        final List<SSHSession> sessions = project.getDataSourceRegistry().getDataSources().stream()
            .map(DBPDataSourceContainer::getActiveNetworkHandlers).flatMap(Stream::of)
            .filter(handler -> handler instanceof SSHTunnelImpl)
            .map(handler -> ((SSHTunnelImpl) handler).getController())
            .filter(Objects::nonNull)
            .distinct()
            .map(SSHSessionController::getSessions).flatMap(Stream::of)
            .toList();

        UIUtils.asyncExec(() -> {
            if (viewer.getControl().isDisposed()) {
                return;
            }

            viewer.getControl().setRedraw(false);
            viewer.clearListData();
            viewer.appendListData(sessions);
            viewer.getControl().setRedraw(true);
        });
    }
}
