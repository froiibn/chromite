/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.ui.UITextUtils;
import com.seizia.chromite.ui.controls.resultset.ResultSetViewer;

public class FilterByAttributeAction extends AbstractResultSetViewerAction {
    private final DBCLogicalOperator operator;
    private final FilterByAttributeType type;
    private final DBDAttributeBinding attribute;
    public FilterByAttributeAction(ResultSetViewer resultSetViewer, DBCLogicalOperator operator, FilterByAttributeType type, DBDAttributeBinding attribute)
    {
        super(resultSetViewer, attribute.getName() + " " + translateFilterPattern(resultSetViewer, operator, type, attribute));
        this.operator = operator;
        this.type = type;
        this.attribute = attribute;
    }

    @Override
    public void run()
    {
        Object value = type.getValue(getResultSetViewer(), attribute, operator, false);
        if (operator.getArgumentCount() != 0 && value == null) {
            return;
        }
        DBDDataFilter filter = new DBDDataFilter(getResultSetViewer().getModel().getDataFilter());
        DBDAttributeConstraint constraint = filter.getConstraint(attribute);
        if (constraint != null) {
            constraint.setOperator(operator);
            constraint.setValue(value);
            getResultSetViewer().setDataFilter(filter, true);
        }
    }

    @NotNull
    private static String translateFilterPattern(@NotNull ResultSetViewer viewer, @NotNull DBCLogicalOperator operator, @NotNull FilterByAttributeType type, @NotNull DBDAttributeBinding attribute) {
        Object value = type.getValue(viewer, attribute, operator, true);

        DBCExecutionContext executionContext = viewer.getExecutionContext();
        String strValue = executionContext == null ? String.valueOf(value) : attribute.getValueHandler().getValueDisplayString(attribute, value, DBDDisplayFormat.UI);
        strValue = strValue.replaceAll("\\s+", " ").replace("@", "^").trim();
        strValue = UITextUtils.getShortText(viewer.getControl(), strValue, 150);
        if (operator.getArgumentCount() == 0) {
            return operator.getExpression();
        } else {
            if (!ResultSetViewer.CUSTOM_FILTER_VALUE_STRING.equals(strValue)) {
                strValue = "'" + strValue + "'";
            }
            return operator.getExpression() + " " + strValue;
        }
    }
}
