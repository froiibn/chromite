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
package com.seizia.chromite.ui.controls.resultset.panel.grouping.column.impl;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.data.DBDAttributeTransformer;
import com.seizia.chromite.ui.controls.resultset.panel.grouping.GroupingResultsContainer;
import com.seizia.chromite.ui.controls.resultset.panel.grouping.column.UniqueGroupingColumn;
import com.seizia.chromite.utils.PrefUtils;

public abstract class TransformerGroupingFunctionColumn extends BasicGroupingFunctionColumn
    implements UniqueGroupingColumn {

    private final String preferenceKey;

    public TransformerGroupingFunctionColumn(
        @NotNull DBPDataSource dataSource,
        @NotNull GroupingResultsContainer groupingResultsContainer,
        @NotNull String preferenceKey
    ) {
        super(dataSource, groupingResultsContainer);
        this.preferenceKey = preferenceKey;
        PrefUtils.setDefaultPreferenceValue(dataSource.getContainer().getPreferenceStore(), preferenceKey, defaultPreferenceValue());
    }

    @NotNull
    public abstract DBDAttributeTransformer getTransformer();

    @NotNull
    @Override
    public String getId() {
        return preferenceKey;
    }

    @Override
    public boolean isShowToUser() {
        return false;
    }

    public boolean isAddToColumns() {
        return dataSource.getContainer().getPreferenceStore().getBoolean(preferenceKey);
    }

    @Override
    public boolean afterDeleteAction() {
        dataSource.getContainer().getPreferenceStore().setValue(preferenceKey, false);
        return super.afterDeleteAction();
    }

    protected boolean defaultPreferenceValue() {
        return false;
    }
}
