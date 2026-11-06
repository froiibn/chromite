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

package com.seizia.chromite.ui.controls.resultset;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.ResultSetValuePath;

import java.util.List;

/**
 * Result set selection
 */
public interface IResultSetSelection extends IStructuredSelection {

    @NotNull
    IResultSetController getController();

    @NotNull
    List<DBDAttributeBinding> getSelectedAttributes();

    @NotNull
    List<ResultSetRow> getSelectedRows();

    DBDAttributeBinding getElementAttribute(Object element);

    ResultSetRow getElementRow(Object element);

    default int[] getElementRowIndexes(Object element) {
        return null;
    }

    @Nullable
    default ResultSetValuePath getElementValuePath(@NotNull Object element) {
        return null;
    }
}
