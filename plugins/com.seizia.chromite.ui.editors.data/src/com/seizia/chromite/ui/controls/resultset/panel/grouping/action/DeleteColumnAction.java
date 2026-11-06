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
package com.seizia.chromite.ui.controls.resultset.panel.grouping.action;

import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.controls.resultset.IResultSetController;
import com.seizia.chromite.ui.controls.resultset.IResultSetSelection;
import com.seizia.chromite.ui.controls.resultset.ResultSetListenerAdapter;
import com.seizia.chromite.ui.controls.resultset.internal.ResultSetMessages;
import com.seizia.chromite.ui.controls.resultset.panel.grouping.GroupingColumnsContainer;
import com.seizia.chromite.ui.controls.resultset.panel.grouping.GroupingResultsContainer;
import org.jkiss.utils.ArrayUtils;

import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class DeleteColumnAction extends GroupingAction {

    // Indexes to remove must keep desc order in all cases, to correctly be removed from collection
    @NotNull
    private final SortedSet<Integer> readyToRemoveIndexes = new TreeSet<>(Comparator.reverseOrder());

    public DeleteColumnAction(@NotNull GroupingResultsContainer resultsContainer) {
        super(resultsContainer, ResultSetMessages.controls_resultset_grouping_remove_column, SwtIcons.getImageDescriptor(UIIcon.CLOSE));
        IResultSetController resultSetController = groupingResultsContainer.getResultSetController();
        resultSetController.addListener(new ResultSetListenerAdapter() {
            @Override
            public void handleResultSetSelectionChange(SelectionChangedEvent event) {
                if (event.getSelection() instanceof IResultSetSelection resultSetSelection) {
                    updateIndexesAndIsEnabled(resultSetSelection.getSelectedAttributes());
                }
            }
        });
        updateIndexesAndIsEnabled(resultSetController.getSelection().getSelectedAttributes());
    }

    @Override
    public boolean isEnabled() {
        return !readyToRemoveIndexes.isEmpty();
    }

    @Override
    public void run() {
        boolean removed = groupingResultsContainer.getColumnsContainer().removeColumnsByIndexesSortedDesc(readyToRemoveIndexes);
        if (removed) {
            try {
                groupingResultsContainer.rebuildGrouping();
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError(
                    ResultSetMessages.grouping_panel_error_title,
                    ResultSetMessages.grouping_panel_error_change_grouping_query_message, e
                );
            }
        }
    }

    private void updateIndexesAndIsEnabled(@NotNull List<DBDAttributeBinding> selectedAttributes) {
        defineIndexesReadyToBeRemoved(selectedAttributes);
        setEnabled(isEnabled());
    }

    private void defineIndexesReadyToBeRemoved(@NotNull List<DBDAttributeBinding> selectedAttributes) {
        DBDAttributeBinding[] existingBindings = groupingResultsContainer.getResultSetController().getModel().getAttributes();
        GroupingColumnsContainer columnsContainer = groupingResultsContainer.getColumnsContainer();
        readyToRemoveIndexes.clear();
        selectedAttributes
            .stream()
            .map(currentBinding -> ArrayUtils.indexOf(existingBindings, currentBinding))
            .filter(i -> canColumnBeRemoved(i, columnsContainer))
            .forEach(readyToRemoveIndexes::add);
    }

    private boolean canColumnBeRemoved(int indexOfAttr, @NotNull GroupingColumnsContainer columnsContainer) {
        return indexOfAttr >= 0 && columnsContainer.canColumnBeRemoved(indexOfAttr);
    }
}