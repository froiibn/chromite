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

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.resource.ImageDescriptor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.controls.resultset.ResultSetRow;
import com.seizia.chromite.ui.controls.resultset.ResultSetUtils;
import com.seizia.chromite.ui.controls.resultset.ResultSetViewer;
import com.seizia.chromite.ui.controls.resultset.internal.ResultSetMessages;
import com.seizia.chromite.ui.controls.resultset.valuefilter.FilterValueEditDialog;

import java.util.Collection;

public enum FilterByAttributeType {
    VALUE(ResultSetMessages.controls_resultset_viewer_action_filter_value, UIIcon.FILTER_VALUE) {
        @Override
        public Object getValue(@NotNull ResultSetViewer viewer, @NotNull DBDAttributeBinding attribute, @NotNull DBCLogicalOperator operator, boolean useDefault)
        {
            final ResultSetRow row = viewer.getCurrentRow();
            if (attribute == null || row == null) {
                return null;
            }
            Object cellValue = viewer.getModel().getCellValue(attribute, row);
            if (operator.isWildcardOperator() && cellValue != null) {
                cellValue = "%" + cellValue + "%";
            }
            return cellValue;
        }
    },
    INPUT(ResultSetMessages.controls_resultset_viewer_action_filter_input, UIIcon.FILTER_INPUT) {
        @Override
        public Object getValue(@NotNull ResultSetViewer viewer, @NotNull DBDAttributeBinding attribute, @NotNull DBCLogicalOperator operator, boolean useDefault)
        {
            if (useDefault) {
                return ResultSetViewer.CUSTOM_FILTER_VALUE_STRING;
            } else {
                ResultSetRow[] rows = null;
                if (operator.getArgumentCount() < 0) {
                    Collection<ResultSetRow> selectedRows = viewer.getSelection().getSelectedRows();
                    rows = selectedRows.toArray(new ResultSetRow[0]);
                } else {
                    ResultSetRow focusRow = viewer.getCurrentRow();
                    if (focusRow != null) {
                        rows = new ResultSetRow[] { focusRow };
                    }
                }
                if (rows == null || rows.length == 0) {
                    return null;
                }
                FilterValueEditDialog dialog = new FilterValueEditDialog(viewer, attribute, rows, operator);
                if (dialog.open() == IDialogConstants.OK_ID) {
                    Object value = dialog.getValue();
                    if (operator.isWildcardOperator() && value instanceof String && ((String) value).indexOf('%') < 0) {
                        return "%" + value + "%";
                    }
                    return value;
                } else {
                    return null;
                }
            }
        }
    },
    CLIPBOARD(ResultSetMessages.controls_resultset_viewer_action_filter_clipboard, UIIcon.FILTER_CLIPBOARD) {
        @Override
        public Object getValue(@NotNull ResultSetViewer viewer, @NotNull DBDAttributeBinding attribute, @NotNull DBCLogicalOperator operator, boolean useDefault)
        {
            try {
                Object value = ResultSetUtils.getAttributeValueFromClipboard(attribute);
                if (operator.isWildcardOperator() && value instanceof String && ((String) value).indexOf('%') < 0) {
                    return "%" + value + "%";
                }
                return value;
            } catch (Exception e) {
                log.debug("Error copying from clipboard", e);
                return null;
            }
        }
    },
    NONE("None", UIIcon.FILTER_VALUE) {
        @Override
        public Object getValue(@NotNull ResultSetViewer viewer, @NotNull DBDAttributeBinding attribute, @NotNull DBCLogicalOperator operator, boolean useDefault)
        {
            return null;
        }
    };

    final String title;
    final ImageDescriptor icon;

    FilterByAttributeType(String title, DBPImage icon) {
        this.title = title;
        this.icon = SwtIcons.getImageDescriptor(icon);
    }

    public String getTitle() {
        return title;
    }

    @Nullable
    public abstract Object getValue(@NotNull ResultSetViewer viewer, @NotNull DBDAttributeBinding attribute, @NotNull DBCLogicalOperator operator, boolean useDefault);

    private static final Log log = Log.getLog(ResultSetViewer.class);

}
