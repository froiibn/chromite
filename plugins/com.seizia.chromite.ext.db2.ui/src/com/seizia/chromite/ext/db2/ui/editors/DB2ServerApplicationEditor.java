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
package com.seizia.chromite.ext.db2.ui.editors;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Composite;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.db2.model.DB2DataSource;
import com.seizia.chromite.ext.db2.model.app.DB2ServerApplication;
import com.seizia.chromite.ext.db2.model.app.DB2ServerApplicationManager;
import com.seizia.chromite.ext.db2.ui.internal.DB2Messages;
import com.seizia.chromite.model.admin.sessions.DBAServerSession;
import com.seizia.chromite.model.admin.sessions.DBAServerSessionManager;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.views.session.AbstractSessionEditor;
import com.seizia.chromite.ui.views.session.SessionManagerViewer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DB2 Application Editor
 * 
 * @author Denis Forveille
 */
public class DB2ServerApplicationEditor extends AbstractSessionEditor {
    private ForceApplicationAction forceApplicationAction;

    @Override
    public void createEditorControl(Composite parent)
    {
        forceApplicationAction = new ForceApplicationAction();
        super.createEditorControl(parent);
    }

    @NotNull
    @Override
    protected SessionManagerViewer<?> createSessionViewer(@NotNull DBCExecutionContext executionContext, @NotNull Composite parent) {
        return new SessionManagerViewer<DB2ServerApplication>(this, parent, new DB2ServerApplicationManager((DB2DataSource) executionContext.getDataSource())) {

            @Override
            @SuppressWarnings("rawtypes")
            protected void contributeToToolbar(@NotNull DBAServerSessionManager<?> sessionManager, @NotNull IContributionManager contributionManager)
            {
                contributionManager.add(forceApplicationAction);
                contributionManager.add(new Separator());
            }

            @Override
            protected void onSessionSelect(@Nullable DBAServerSession session)
            {
                super.onSessionSelect(session);
                forceApplicationAction.setEnabled(session != null);
            }
        };
    }

    private class ForceApplicationAction extends Action {

        public ForceApplicationAction()
        {
            super(DB2Messages.editors_db2_application_editor_title_force_application, SwtIcons.getImageDescriptor(UIIcon.REJECT));
        }

        @Override
        public void run()
        {
            final List<DBAServerSession> sessions = getSessionsViewer().getSelectedSessions();
            final String action = DB2Messages.editors_db2_application_editor_action_force;
            if (UIUtils.confirmAction(getSite().getShell(), "Confirm force application",
                NLS.bind(DB2Messages.editors_db2_application_editor_confirm_action, action.toLowerCase(), sessions))) {
                Map<String, Object> options = new HashMap<>();
                getSessionsViewer().alterSessions(sessions, options);
            }
        }
    }
}