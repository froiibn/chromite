/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ui.controls.resultset.actions;

import org.eclipse.jface.action.IAction;
import org.eclipse.swt.widgets.Event;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.hints.DBDValueHintContext;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.ui.controls.resultset.ResultSetViewer;

public class HintConfigurationLevelAction extends AbstractResultSetViewerAction {

    @NotNull
    private final DBDValueHintContext.HintConfigurationLevel configurationLevel;

    public HintConfigurationLevelAction(
        @NotNull ResultSetViewer resultSetViewer,
        @NotNull DBDValueHintContext.HintConfigurationLevel cl
    ) {
        super(resultSetViewer, getLevelTitle(resultSetViewer, cl), IAction.AS_RADIO_BUTTON);
        this.configurationLevel = cl;
        setChecked(configurationLevel == getResultSetViewer().getHintContext().getConfigurationLevel());
    }

    @Override
    public void setChecked(boolean checked) {
        super.setChecked(checked);
    }

    @Override
    public void runWithEvent(Event event) {
        if (isChecked()) {
            DBDValueHintContext hintContext = getResultSetViewer().getHintContext();
            hintContext.setConfigurationLevel(configurationLevel);
            getResultSetViewer().refreshData(null);
        }
    }


    private static String getLevelTitle(ResultSetViewer viewer, DBDValueHintContext.HintConfigurationLevel level) {
        return switch (level) {
            case GLOBAL -> "Global";
            case DATASOURCE -> {
                DBPDataSource dataSource = viewer.getDataSource();
                yield dataSource == null ? "N/A" : "Datasource (" + dataSource.getName() + ")";
            }
            case ENTITY -> {
                DBSEntity singleSource = viewer.getModel().getSingleSource();
                yield singleSource == null ? "N/A" : "Entity (" +
                    DBUtils.getObjectFullName(singleSource, DBPEvaluationContext.UI) + ")";
            }
        };
    }

}
