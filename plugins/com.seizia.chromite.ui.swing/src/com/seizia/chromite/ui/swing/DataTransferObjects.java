/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.ui.swing;

import com.formdev.flatlaf.FlatClientProperties;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.DBValueFormatting;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSFolder;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 데이터 내보내기·가져오기 마법사가 함께 쓰는 것: 원본 객체 찾기, 양식 줄, 안내 문구, 객체 칸 렌더러.
 *
 * 설계: 원본 목록은 SWT {@code DataExportHandler}·{@code DataImportHandler}처럼 모델 객체의 형식으로 판단한다
 * (테이블·뷰 = {@link DBSDataContainer}이면서 {@link DBSEntity}, 그 부모 = 주 자식 형식이 {@link DBSDataContainer}인
 * {@link DBSObjectContainer}, 탐색기 폴더 = 자식 형식이 그런 {@link DBSFolder}). 양식 줄 모양은 {@link OptionsDialog}와 같다
 * (이름표에 쌍점 없음, 간격 6, 보조 설명은 text.secondary).
 *
 * 변경 이력:
 * - 2026-11-06 데이터 전송: 최초 작성 (docs/chromite/swing-data-transfer.md)
 */
final class DataTransferObjects {

    private static final Log log = Log.getLog(DataTransferObjects.class);

    private DataTransferObjects() {
    }

    /**
     * A schema, catalog or Object Explorer folder whose children are tables or views. Called on the EDT: it only
     * looks at types (as the SWT {@code DataImportHandler.isObjectContainerSupportsImport}).
     */
    static boolean isDataContainerParent(@Nullable DBSObject object) {
        try {
            if (object instanceof DBNDatabaseFolder folder) {
                Class<? extends DBSObject> childrenClass = folder.getChildrenClass();
                return childrenClass != null && DBSDataContainer.class.isAssignableFrom(childrenClass);
            }
            if (object instanceof DBSObjectContainer container) {
                Class<? extends DBSObject> childType = container.getPrimaryChildType(null);
                return childType != null && DBSDataContainer.class.isAssignableFrom(childType);
            }
        } catch (DBException e) {
            log.debug("Can't get the child type of " + object.getName(), e);
        }
        return false;
    }

    /**
     * Tables and views of a parent (a container's children or a folder's), in its order (reads metadata).
     *
     * @param parent a {@link DBSObjectContainer} or a {@link DBSFolder}
     */
    @NotNull
    static List<DBSDataContainer> readDataContainers(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject parent) throws DBException {
        Collection<? extends DBSObject> children = null;
        if (parent instanceof DBSFolder folder) {
            children = folder.getChildrenObjects(monitor);
        } else if (parent instanceof DBSObjectContainer container) {
            children = container.getChildren(monitor);
        }
        List<DBSDataContainer> result = new ArrayList<>();
        if (children != null) {
            for (DBSObject child : children) {
                if (child instanceof DBSDataContainer dataContainer && child instanceof DBSEntity && !DBUtils.isHiddenObject(child)) {
                    result.add(dataContainer);
                }
            }
        }
        return result;
    }

    /**
     * Label, control and optional hint in one form row (as {@link OptionsDialog}).
     */
    static int addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control, @Nullable String hint) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 6);
        if (label != null) {
            c.gridx = 0;
            form.add(new JLabel(label), c);
        }
        c.gridx = 1;
        // Fields and combos take the width; check boxes and spinners keep their size, and their hint takes the rest
        boolean fixedSize = control instanceof JSpinner || control instanceof AbstractButton;
        if (control instanceof JSpinner spinner) {
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(8);
        }
        c.fill = fixedSize ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        c.weightx = fixedSize ? 0 : 1;
        if (hint == null) {
            c.gridwidth = 2;
        }
        form.add(control, c);
        if (hint != null) {
            JLabel hintLabel = new JLabel(hint);
            hintLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            c.gridx = 2;
            c.fill = GridBagConstraints.NONE;
            c.weightx = fixedSize ? 1 : 0;
            form.add(hintLabel, c);
        }
        return row + 1;
    }

    /**
     * Explanatory text in the secondary color, wrapped.
     */
    @NotNull
    static JLabel createNote(@NotNull String text) {
        JLabel label = new JLabel("<html>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        return label;
    }

    /**
     * Database objects with their icon and full name.
     */
    static final class ObjectRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof DBSObject object) {
                setText(DBUtils.getObjectFullName(object, DBPEvaluationContext.UI));
                setIcon(NodeIcons.getIcon(DBValueFormatting.getObjectImage(object)));
            } else {
                setIcon(null);
            }
            return this;
        }
    }
}
