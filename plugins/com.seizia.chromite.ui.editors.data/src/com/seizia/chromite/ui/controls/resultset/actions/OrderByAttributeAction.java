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

import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.controls.resultset.IResultSetController;
import com.seizia.chromite.ui.controls.resultset.ResultSetViewer;

public class OrderByAttributeAction extends AbstractResultSetViewerAction {
    private final DBDAttributeBinding attribute;
    private final IResultSetController.ColumnOrder order;

    public OrderByAttributeAction(ResultSetViewer resultSetViewer, DBDAttributeBinding attribute, IResultSetController.ColumnOrder order) {
        super(
            resultSetViewer,
            order == IResultSetController.ColumnOrder.NONE ?
                "Disable order by " + attribute.getName() :
                "Order by " + attribute.getName() + " " + order.name(), AS_CHECK_BOX);
        this.attribute = attribute;
        this.order = order;
        if (order != IResultSetController.ColumnOrder.NONE) {
            setImageDescriptor(SwtIcons.getImageDescriptor(order != IResultSetController.ColumnOrder.ASC ?
                UIIcon.SORT_INCREASE : UIIcon.SORT_DECREASE));
        }
    }

    @Override
    public boolean isChecked() {
        if (order == IResultSetController.ColumnOrder.NONE) {
            return false;
        }
        DBDDataFilter dataFilter = getResultSetViewer().getModel().getDataFilter();
        DBDAttributeConstraint constraint = dataFilter.getConstraint(attribute);
        if (constraint == null || constraint.getOrderPosition() <= 0) {
            return false;
        }
        boolean forceAsc = order == IResultSetController.ColumnOrder.ASC;
        return constraint.isOrderDescending() != forceAsc;
    }

    @Override
    public void run() {
        getResultSetViewer().toggleSortOrder(attribute, order);
    }
}
