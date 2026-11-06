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
package com.seizia.chromite.ext.altibase.ui.editors;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Composite;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.altibase.model.session.AltibaseServerSession;
import com.seizia.chromite.ext.altibase.model.session.AltibaseServerSessionManager;
import com.seizia.chromite.ext.altibase.ui.internal.AltibaseUIMessages;
import com.seizia.chromite.model.admin.sessions.DBAServerSession;
import com.seizia.chromite.model.admin.sessions.DBAServerSessionManager;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.dialogs.ConfirmationDialog;
import com.seizia.chromite.ui.views.session.AbstractSessionEditor;
import com.seizia.chromite.ui.views.session.SessionManagerViewer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AltibaseSessionEditor
 */
public class AltibaseSessionEditor extends AbstractSessionEditor {
    private DisconnectSessionAction disconnectSessionAction;

    public AltibaseSessionEditor() {
    }

    @Override
    public void createEditorControl(Composite parent) {
        disconnectSessionAction = new DisconnectSessionAction();
        super.createEditorControl(parent);
    }

    @NotNull
    @Override
    protected SessionManagerViewer<?> createSessionViewer(@NotNull DBCExecutionContext executionContext, @NotNull Composite parent) {
        return new SessionManagerViewer<AltibaseServerSession>(this, parent, 
                new AltibaseServerSessionManager((AltibaseDataSource) executionContext.getDataSource())) {

            @Override
            protected void contributeToToolbar(@NotNull DBAServerSessionManager<?> sessionManager, @NotNull IContributionManager contributionManager) {
                contributionManager.add(disconnectSessionAction);
                contributionManager.add(new Separator());
            }
            
            @Override
            protected void onSessionSelect(@Nullable DBAServerSession session) {
                super.onSessionSelect(session);
                disconnectSessionAction.setEnabled(session != null);
            }
        };
    }

    private class DisconnectSessionAction extends Action {
        DisconnectSessionAction() {
            super(AltibaseUIMessages.editors_altibase_session_editor_action_disconnect_session, 
                 SwtIcons.getImageDescriptor(UIIcon.SQL_DISCONNECT));
        }

        @Override
        public void run() {
            final List<DBAServerSession> sessions = getSessionsViewer().getSelectedSessions();
            final String action = AltibaseUIMessages.editors_altibase_session_editor_action_disconnect_session;
            ConfirmationDialog dialog = new ConfirmationDialog(
                    getSite().getShell(),
                    action,
                    null,
                    NLS.bind(AltibaseUIMessages.editors_altibase_session_editor_confirm_action, action.toLowerCase(), sessions),
                    MessageDialog.CONFIRM,
                    new String[] { IDialogConstants.YES_LABEL, IDialogConstants.NO_LABEL },
                    0,
                    null,
                    false);

            if (dialog.open() == IDialogConstants.YES_ID) {
                Map<String, Object> options = new HashMap<>();
                getSessionsViewer().alterSessions(sessions, options);
            }
            
            refreshPart(AltibaseSessionEditor.this, true);

        }
    }

}