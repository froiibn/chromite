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
package com.seizia.chromite.ui.editors.sql.plan.simple;

import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbenchPart;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.ui.editors.sql.SQLPlanSaveProvider;

/**
 * SQLPlanViewProviderSimple
 */
public class SQLPlanViewProviderSimple extends SQLPlanSaveProvider {


    @NotNull
    @Override
    public Viewer createPlanViewer(@NotNull IWorkbenchPart workbenchPart, @NotNull Composite parent) {
        SQLPlanTreeViewer treeViewer = new SQLPlanTreeViewer(workbenchPart, parent);
        return treeViewer;
    }

    @Override
    public void visualizeQueryPlan(@NotNull Viewer viewer, @NotNull SQLQuery query, @NotNull DBCPlan plan) {
        fillPlan(query,plan);
        showPlan(viewer,query, plan);
    }

    @Override
    public void contributeActions(
        @NotNull Viewer viewer,
        @NotNull IContributionManager contributionManager,
        @NotNull SQLQuery lastQuery,
        @NotNull DBCPlan lastPlan
    ) {
        super.contributeActions(viewer, contributionManager, lastQuery, lastPlan);

        SQLPlanTreeViewer treeViewer = (SQLPlanTreeViewer) viewer;
        treeViewer.contributeActions(contributionManager, lastQuery, lastPlan);
    }

    @Override
    protected void showPlan(Viewer viewer, SQLQuery query, DBCPlan plan) {
        SQLPlanTreeViewer treeViewer = (SQLPlanTreeViewer) viewer;
        treeViewer.showPlan(query, plan);      
    }

}
