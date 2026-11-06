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
package com.seizia.chromite.ext.yashandb.ui.editors;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.widgets.Composite;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.session.OracleServerSessionManager;
import com.seizia.chromite.ext.oracle.ui.editors.OracleSessionEditor;
import com.seizia.chromite.ext.yashandb.model.session.YashanDBServerSession;
import com.seizia.chromite.ext.yashandb.model.session.YashanDBServerSessionManager;
import com.seizia.chromite.ext.yashandb.ui.internal.YashanDBUIMessages;
import com.seizia.chromite.model.admin.sessions.DBAServerSession;
import com.seizia.chromite.model.admin.sessions.DBAServerSessionManager;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.ui.ActionUtils;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.views.session.SessionManagerViewer;
import org.jkiss.utils.CommonUtils;

import java.util.HashMap;
import java.util.Map;


/**
 * YashanDBSessionEditor
 */
public class YashanDBSessionEditor extends OracleSessionEditor {

    @Override
    public void createEditorControl(@NotNull Composite parent) {
        // YashanDB only support kill session
        killSessionAction = new DisconnectSessionAction(true);
        super.createEditorControl(parent);
    }

    @NotNull
    @Override
    protected SessionManagerViewer<?> createSessionViewer(@NotNull DBCExecutionContext executionContext, @NotNull Composite parent) {
        return new SessionManagerViewer<YashanDBServerSession>(this, parent,
            new YashanDBServerSessionManager((OracleDataSource) executionContext.getDataSource())) {

            private boolean showBackground;
            private boolean showInactive;

            @Override
            protected void contributeToToolbar(@NotNull DBAServerSessionManager<?> sessionManager,
                                               @NotNull IContributionManager contributionManager) {
                contributionManager.add(killSessionAction);
                contributionManager.add(new Separator());

                contributionManager.add(ActionUtils.makeActionContribution(new Action(
                    YashanDBUIMessages.views_session_manager_viewer_show_background, Action.AS_CHECK_BOX) {
                    {
                        setImageDescriptor(SwtIcons.getImageDescriptor(UIIcon.CONFIGURATION));
                        setToolTipText(YashanDBUIMessages.views_session_manager_viewer_show_background_tasks_tip);
                        setChecked(showBackground);
                    }

                    @Override
                    public void run() {
                        showBackground = isChecked();
                        refreshPart(YashanDBSessionEditor.this, true);
                    }
                }, true));

                contributionManager.add(ActionUtils.makeActionContribution(
                    new Action(YashanDBUIMessages.views_session_manager_viewer_show_inactive, Action.AS_CHECK_BOX) {
                        {
                            setImageDescriptor(SwtIcons.getImageDescriptor(UIIcon.CONFIGURATION));
                            setToolTipText(
                                YashanDBUIMessages.views_session_manager_viewer_show_inactive_sessions_tip);
                            setChecked(showInactive);
                        }

                        @Override
                        public void run() {
                            showInactive = isChecked();
                            refreshPart(YashanDBSessionEditor.this, true);
                        }
                    }, true));
            }

            @Override
            protected void onSessionSelect(@Nullable DBAServerSession session) {
                super.onSessionSelect(session);
                killSessionAction.setEnabled(session != null);
            }

            @Override
            protected void loadSettings(@NotNull IDialogSettings settings) {
                showBackground = CommonUtils.toBoolean(settings.get("showBackground"));
                showInactive = CommonUtils.toBoolean(settings.get("showInactive"));
                super.loadSettings(settings);
            }

            @Override
            protected void saveSettings(@NotNull IDialogSettings settings) {
                super.saveSettings(settings);
                settings.put("showBackground", showBackground);
                settings.put("showInactive", showInactive);
            }

            @NotNull
            @Override
            public Map<String, Object> getSessionOptions() {
                Map<String, Object> options = new HashMap<>();
                if (showBackground) {
                    options.put(OracleServerSessionManager.OPTION_SHOW_BACKGROUND, true);
                }
                if (showInactive) {
                    options.put(OracleServerSessionManager.OPTION_SHOW_INACTIVE, true);
                }
                return options;
            }

        };
    }
}
