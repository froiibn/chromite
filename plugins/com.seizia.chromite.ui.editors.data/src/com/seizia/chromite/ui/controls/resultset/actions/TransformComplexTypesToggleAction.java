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

import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.ui.controls.resultset.ResultSetViewer;
import com.seizia.chromite.ui.controls.resultset.internal.ResultSetMessages;

public class TransformComplexTypesToggleAction extends AbstractResultSetViewerAction {
    public TransformComplexTypesToggleAction(ResultSetViewer resultSetViewer) {
        super(resultSetViewer, ResultSetMessages.actions_name_structurize_complex_types, AS_CHECK_BOX);
        setToolTipText("Visualize complex types (arrays, structures, maps) in results grid as separate columns");
    }

    @Override
    public boolean isChecked() {
        DBPDataSource dataSource = getResultSetViewer().getDataContainer().getDataSource();
        return dataSource != null &&
               dataSource.getContainer().getPreferenceStore().getBoolean(ModelPreferences.RESULT_TRANSFORM_COMPLEX_TYPES);
    }

    @Override
    public void run() {
        DBPDataSource dataSource = getResultSetViewer().getDataContainer().getDataSource();
        if (dataSource == null) {
            return;
        }
        DBPPreferenceStore preferenceStore = dataSource.getContainer().getPreferenceStore();
        boolean curValue = preferenceStore.getBoolean(ModelPreferences.RESULT_TRANSFORM_COMPLEX_TYPES);
        preferenceStore.setValue(ModelPreferences.RESULT_TRANSFORM_COMPLEX_TYPES, !curValue);
        dataSource.getContainer().persistConfiguration();
        getResultSetViewer().refreshData(null);
    }

}
