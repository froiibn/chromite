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
package com.seizia.chromite.ext.mysql.ui.editors;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.ISharedImages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.mysql.model.session.MySQLSessionManager;
import com.seizia.chromite.ext.mysql.ui.internal.MySQLUIMessages;
import com.seizia.chromite.model.admin.sessions.DBAServerSession;
import com.seizia.chromite.model.admin.sessions.DBAServerSessionManager;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.ui.ActionUtils;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.views.session.AbstractSessionEditor;
import com.seizia.chromite.ui.views.session.SessionManagerViewer;
import org.jkiss.utils.CommonUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQLSessionEditor
 */
public class MySQLSessionEditor extends AbstractSessionEditor
{
    private static final Log log = Log.getLog(MySQLSessionEditor.class);

    private KillSessionAction killSessionAction;
    private KillSessionAction terminateQueryAction;

    @Override
    public void createEditorControl(Composite parent) {
        killSessionAction = new KillSessionAction(false);
        terminateQueryAction = new KillSessionAction(true);
        super.createEditorControl(parent);
    }

    @NotNull
    @Override
    protected SessionManagerViewer<?> createSessionViewer(@NotNull DBCExecutionContext executionContext, @NotNull Composite parent) {
        final MySQLDataSource dataSource = (MySQLDataSource) executionContext.getDataSource();
        return new SessionManagerViewer<>(this, parent, new MySQLSessionManager(dataSource)) {
            private boolean hideSleeping;
            private boolean showPerformance;

            @Override
            protected void contributeToToolbar(@NotNull DBAServerSessionManager<?> sessionManager, @NotNull IContributionManager contributionManager) {
                contributionManager.add(killSessionAction);
                contributionManager.add(terminateQueryAction);
                contributionManager.add(new Separator());

                contributionManager.add(ActionUtils.makeActionContribution(
                    new Action(MySQLUIMessages.editors_session_hide_sleeping_text, Action.AS_CHECK_BOX) {
                        {
                            setToolTipText(MySQLUIMessages.editors_session_hide_sleeping_tip);
                            setImageDescriptor(SwtIcons.getImageDescriptor(UIIcon.HIDE_ALL_DETAILS));
                            setChecked(hideSleeping);
                        }

                        @Override
                        public void run() {
                            hideSleeping = isChecked();
                            refreshPart(MySQLSessionEditor.this, true);
                        }
                    }, true));

                if (dataSource.supportsSysSchema()) {
                    contributionManager.add(ActionUtils.makeActionContribution(
                        new Action(MySQLUIMessages.editors_session_show_performance_text, Action.AS_CHECK_BOX) {
                            {
                                setToolTipText(MySQLUIMessages.editors_session_show_performance_tip);
                                setImageDescriptor(SwtIcons.getImageDescriptor(UIIcon.SHOW_ALL_DETAILS));
                                setChecked(showPerformance);
                            }

                            @Override
                            public void run() {
                                showPerformance = isChecked();
                                refreshPart(MySQLSessionEditor.this, true);
                            }
                        }, true));
                }

                contributionManager.add(new Separator());
            }

            @Override
            protected void onSessionSelect(@Nullable DBAServerSession session) {
                super.onSessionSelect(session);
                killSessionAction.setEnabled(session != null);
                terminateQueryAction.setEnabled(session != null && !CommonUtils.isEmpty(session.getActiveQuery()));
            }

            @Override
            public Map<String, Object> getSessionOptions() {
                Map<String, Object> options = new HashMap<>();
                if (hideSleeping) {
                    options.put(MySQLSessionManager.OPTION_HIDE_SLEEPING, true);
                }
                if (showPerformance) {
                    options.put(MySQLSessionManager.OPTION_SHOW_PERFORMANCE, true);
                }
                return options;
            }

            @Override
            protected void loadSettings(IDialogSettings settings) {
                hideSleeping = CommonUtils.toBoolean(settings.get("hideSleeping"));
                showPerformance = CommonUtils.toBoolean(settings.get("showPerformance"));
                super.loadSettings(settings);
            }

            @Override
            protected void saveSettings(IDialogSettings settings) {
                super.saveSettings(settings);
                settings.put("hideSleeping", hideSleeping);
                settings.put("showPerformance", showPerformance);
            }
        };
    }

    private class KillSessionAction extends Action {
        private boolean killQuery;
        public KillSessionAction(boolean killQuery)
        {
            super(
                killQuery ? MySQLUIMessages.editors_session_editor_action_terminate_Query : MySQLUIMessages.editors_session_editor_action_kill_Session,
                killQuery ?
                    UIUtils.getShardImageDescriptor(ISharedImages.IMG_ELCL_STOP) :
                    SwtIcons.getImageDescriptor(UIIcon.SQL_DISCONNECT));
            this.killQuery = killQuery;
        }

        @Override
        public void run()
        {
            final List<DBAServerSession> sessions = getSessionsViewer().getSelectedSessions();
            if (sessions != null && UIUtils.confirmAction(getSite().getShell(),
                this.getText(),
                NLS.bind(MySQLUIMessages.editors_session_editor_confirm, getText(), sessions)))
            {
                getSessionsViewer().alterSessions(
                    sessions,
                    Collections.singletonMap(MySQLSessionManager.PROP_KILL_QUERY, killQuery));
            }
        }
    }

}