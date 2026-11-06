/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
import org.jkiss.code.NotNull;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDBinaryFormatter;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.virtual.DBVTransformSettings;
import com.seizia.chromite.model.virtual.DBVUtils;
import com.seizia.chromite.ui.controls.resultset.ResultSetViewer;

public class BinaryFormatAction extends AbstractResultSetViewerAction {
    private final String prefValue;
    private final DBDAttributeBinding attribute;

    public BinaryFormatAction(ResultSetViewer resultSetViewer, DBDBinaryFormatter formatter, DBDAttributeBinding attr) {
        super(resultSetViewer, formatter.getTitle(), IAction.AS_RADIO_BUTTON);
        this.prefValue = formatter.getTitle();
        this.attribute = attr;
    }

    @Override
    public boolean isChecked() {
        return getResultSetViewer().getPreferenceStore().getString(ModelPreferences.RESULT_SET_BINARY_PRESENTATION).equalsIgnoreCase(prefValue);
    }

    @Override
    public void run() {
        DBPDataSource dataSource = getResultSetViewer().getDataContainer().getDataSource();
        if (dataSource == null) {
            return;
        }
        DBPPreferenceStore preferenceStore = getActionPreferenceStore();
        String prefId = ModelPreferences.RESULT_SET_BINARY_PRESENTATION;
        preferenceStore.setValue(
            prefId,
            prefValue.toLowerCase());
        getTransformSettings().setCustomTransformer(prefId);
        dataSource.getContainer().persistConfiguration();
        getResultSetViewer().refreshData(null);
    }

    @NotNull
    DBVTransformSettings getTransformSettings() {
        final DBVTransformSettings settings = DBVUtils.getTransformSettings(attribute, true);
        if (settings == null) {
            throw new IllegalStateException("Can't get/create transformer settings for '" + attribute.getFullyQualifiedName(DBPEvaluationContext.UI) + "'");
        }
        return settings;
    }

    DBPPreferenceStore getActionPreferenceStore() {
        return getResultSetViewer().getPreferenceStore();
    }
}
