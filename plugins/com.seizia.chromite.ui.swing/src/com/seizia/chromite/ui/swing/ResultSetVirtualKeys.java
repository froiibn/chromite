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

import com.seizia.chromite.ui.swing.internal.GridNavMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDRowIdentifier;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.virtual.DBVEntity;
import com.seizia.chromite.model.virtual.DBVEntityConstraint;
import com.seizia.chromite.model.virtual.DBVEntityForeignKey;
import com.seizia.chromite.model.virtual.DBVEntityForeignKeyColumn;
import com.seizia.chromite.model.virtual.DBVUtils;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.design.TableDesignerDriver;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 가상 키(SWT {@code controls/resultset/virtual}): 기본 키·고유 키가 없는 테이블의 가상 고유 키(행을 가려낼 열)와, DB에 없는 외래 키(가상 외래 키).
 *
 * 설계:
 * <ul>
 *     <li>저장은 모델이 하던 대로 한다: 연결의 가상 모델({@link DBVEntity}: {@link DBVEntityConstraint} {@code VIRTUAL_KEY},
 *     {@link DBVEntityForeignKey})에 넣고 {@code DBPDataSourceContainer.persistConfiguration()}으로 연결 설정 파일(data-sources.json의
 *     "virtual-meta-data")에 쓴다(SWT {@code ResultSetViewer.persistConfig}). DB는 바꾸지 않는다. 그래서 SWT로 정한 가상 키도 그대로 읽힌다.</li>
 *     <li>가상 고유 키는 결과를 다시 읽을 때 모델이 행 식별자로 쓴다({@code DBExecUtils.getBestIdentifier}: 실제 키·고유 인덱스·ROWID가 없을 때만).
 *     그래서 저장 뒤 결과 뷰어가 다시 읽으면 편집할 수 있게 된다. SWT {@code ValidateUniqueKeyUsageDialog}의 "모든 열 쓰기"도 고를 수 있다
 *     (모든 열로 WHERE를 만든다, 같은 행이 둘이면 둘 다 바뀔 수 있다는 것을 대화상자에 알린다).</li>
 *     <li>가상 외래 키 대화상자는 테이블 디자이너의 외래 키 대화상자({@link DesignerDialogs#editForeignKey}, 가상 모드: 이름·규칙 없음)를 쓴다.
 *     참조할 테이블은 같은 스키마의 테이블과 뷰, 참조할 키는 그 테이블의 실제 고유 키와 가상 고유 키다. 대상 키를 고르려면 참조 노드가 있어야 하므로
 *     ({@link DBVEntityForeignKey#setReferencedConstraint}는 탐색기 모델의 노드 경로를 남긴다) 저장은 진행 대화상자에서 한다.</li>
 *     <li>지우기는 확인한 뒤 한다(기본 단추 Cancel). 바뀌면 {@code DBUtils.fireObjectUpdate}로 알리고, 결과 뷰어가 결과를 다시 읽는다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다(DB를 읽는 일은 진행 대화상자에서).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-grid-navigation.md "가상 키")
 */
final class ResultSetVirtualKeys {

    private static final Log log = Log.getLog(ResultSetVirtualKeys.class);

    private ResultSetVirtualKeys() {
    }

    /**
     * Whether the result identifies the rows of its table with a virtual key or with none: the table has no primary
     * key, unique key or row id, so a virtual unique key applies.
     */
    static boolean usesVirtualKey(@NotNull DBSEntity entity, @NotNull DBDAttributeBinding[] bindings) {
        for (DBDAttributeBinding binding : bindings) {
            DBDRowIdentifier identifier = binding.getRowIdentifier();
            if (identifier != null && identifier.getEntity() == entity) {
                return identifier.getUniqueKey() instanceof DBVEntityConstraint || identifier.isIncomplete();
            }
        }
        return false;
    }

    /**
     * The columns of the table that the result shows, in result order.
     */
    @NotNull
    private static List<DBSEntityAttribute> getResultColumns(@NotNull DBSEntity entity, @NotNull DBDAttributeBinding[] bindings) {
        List<DBSEntityAttribute> columns = new ArrayList<>();
        for (DBDAttributeBinding binding : bindings) {
            DBSEntityAttribute attribute = binding.getEntityAttribute();
            if (attribute != null && attribute.getParentObject() == entity && !binding.isPseudoAttribute() && !columns.contains(attribute)) {
                columns.add(attribute);
            }
        }
        return columns;
    }

    /**
     * Define Virtual Unique Key: the columns that identify a row (or all columns), stored in the connection's virtual model.
     *
     * @return whether the key was saved or removed (the result should be read again)
     */
    static boolean editUniqueKey(@NotNull Component parent, @NotNull DBSEntity entity, @NotNull DBDAttributeBinding[] bindings) {
        List<DBSEntityAttribute> columns = getResultColumns(entity, bindings);
        if (columns.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(GridNavMessages.gridnav_vkey_title, GridNavMessages.gridnav_vkey_no_columns, true);
            return false;
        }
        DBVEntity existingEntity = DBVUtils.getVirtualEntity(entity, false);
        DBVEntityConstraint existing = existingEntity == null ? null : findVirtualKey(existingEntity);
        Set<String> chosen = new HashSet<>();
        if (existing != null) {
            existing.getAttributes().forEach(column -> chosen.add(column.getAttributeName()));
        }

        JRadioButton allButton = new JRadioButton(GridNavMessages.gridnav_vkey_all_columns);
        JRadioButton someButton = new JRadioButton(GridNavMessages.gridnav_vkey_some_columns);
        ButtonGroup group = new ButtonGroup();
        group.add(allButton);
        group.add(someButton);
        JPanel checks = new JPanel(new GridLayout(0, 1));
        Map<DBSEntityAttribute, JCheckBox> boxes = new LinkedHashMap<>();
        for (DBSEntityAttribute column : columns) {
            JCheckBox box = new JCheckBox(column.getName() + "  " + column.getFullTypeName(), chosen.contains(column.getName())); //$NON-NLS-1$
            boxes.put(column, box);
            checks.add(box);
        }
        boolean useAll = existing != null && existing.isUseAllColumns();
        allButton.setSelected(useAll);
        someButton.setSelected(!useAll);
        Runnable enable = () -> boxes.values().forEach(box -> box.setEnabled(someButton.isSelected()));
        allButton.addActionListener(e -> enable.run());
        someButton.addActionListener(e -> enable.run());
        enable.run();

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JLabel intro = new JLabel(NLS.bind(GridNavMessages.gridnav_vkey_intro, escape(DBUtils.getObjectFullName(entity, DBPEvaluationContext.UI))));
        panel.add(intro, BorderLayout.NORTH);
        JPanel choices = new JPanel(new BorderLayout(0, 4));
        choices.add(allButton, BorderLayout.NORTH);
        JPanel some = new JPanel(new BorderLayout(0, 2));
        some.add(someButton, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(checks);
        scroll.setPreferredSize(new Dimension(420, Math.min(260, 26 * columns.size() + 8)));
        some.add(scroll, BorderLayout.CENTER);
        choices.add(some, BorderLayout.CENTER);
        panel.add(choices, BorderLayout.CENTER);

        List<String> options = new ArrayList<>(List.of(ToolsMessages.button_ok));
        if (existing != null && existing.hasAttributes()) {
            options.add(GridNavMessages.gridnav_vkey_remove);
        }
        options.add(ToolsMessages.button_cancel);
        while (true) {
            // Cancel is the default button: the dialog changes the connection's settings
            int answer = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(parent), panel, GridNavMessages.gridnav_vkey_title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options.toArray(), options.getLast());
            if (answer < 0 || answer == options.size() - 1) {
                return false;
            }
            DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, true);
            if (vEntity == null) {
                return false;
            }
            DBVEntityConstraint key = vEntity.getBestIdentifier();
            if (GridNavMessages.gridnav_vkey_remove.equals(options.get(answer))) {
                // SWT ResultSetViewer.clearEntityIdentifier
                key.setUseAllColumns(false);
                key.setAttributes(List.of());
            } else {
                List<DBSEntityAttribute> keyColumns = new ArrayList<>();
                if (allButton.isSelected()) {
                    keyColumns.addAll(columns);
                } else {
                    boxes.forEach((column, box) -> {
                        if (box.isSelected()) {
                            keyColumns.add(column);
                        }
                    });
                }
                if (keyColumns.isEmpty()) {
                    DBWorkbench.getPlatformUI().showMessageBox(GridNavMessages.gridnav_vkey_title, GridNavMessages.gridnav_vkey_choose_columns, true);
                    continue;
                }
                key.setAttributes(keyColumns);
                key.setUseAllColumns(allButton.isSelected());
            }
            persist(entity, vEntity, key);
            return true;
        }
    }

    /**
     * The table's virtual unique key that has columns, or null.
     */
    @Nullable
    private static DBVEntityConstraint findVirtualKey(@NotNull DBVEntity vEntity) {
        for (DBVEntityConstraint constraint : vEntity.getConstraints()) {
            if (constraint.getConstraintType().isUnique() && constraint.hasAttributes()) {
                return constraint;
            }
        }
        return null;
    }

    /**
     * Add Virtual Foreign Key: the columns of this table that reference a key of another table of the same schema.
     *
     * @return whether a key was added (the result should be read again)
     */
    static boolean addForeignKey(@NotNull Component parent, @NotNull DBSEntity entity, @NotNull DBDAttributeBinding[] bindings) {
        List<DBSEntityAttribute> columns = getResultColumns(entity, bindings);
        if (columns.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(GridNavMessages.gridnav_vfk_title, GridNavMessages.gridnav_vkey_no_columns, true);
            return false;
        }
        List<DBSEntity> tables;
        try {
            tables = ProgressDialog.runTask(GridNavMessages.gridnav_vfk_reading_tables, monitor -> {
                List<DBSEntity> result = new ArrayList<>();
                if (entity.getParentObject() instanceof DBSObjectContainer container) {
                    for (DBSObject child : CommonUtils.safeCollection(container.getChildren(monitor))) {
                        // Tables and views: a virtual key may point at a view too (SWT allows any entity)
                        if (child instanceof DBSEntity table && child instanceof DBSDataContainer && child.isPersisted()) {
                            result.add(table);
                        }
                    }
                }
                if (!result.contains(entity)) {
                    result.add(entity);
                }
                return result;
            });
        } catch (DBInterruptedException e) {
            return false;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(GridNavMessages.gridnav_vfk_title, GridNavMessages.gridnav_vfk_reading_tables, e);
            return false;
        }
        DesignerDialogs.ForeignKeySpec spec = DesignerDialogs.editForeignKey(parent, GridNavMessages.gridnav_vfk_title, entity, columns, tables, true);
        if (spec == null) {
            return false;
        }
        try {
            ProgressDialog.runTask(GridNavMessages.gridnav_vfk_title, monitor -> {
                DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, true);
                if (vEntity == null) {
                    throw new DBException("No virtual model for " + entity.getName());
                }
                DBVEntityForeignKey foreignKey = new DBVEntityForeignKey(vEntity);
                // Keeps the referenced table's navigator path and the key name (SWT EditForeignKeyPage.createVirtualForeignKey)
                foreignKey.setReferencedConstraint(monitor, spec.referencedKey());
                if (foreignKey.getRefEntityId() == null) {
                    throw new DBException("The referenced table is not in the object explorer");
                }
                List<DBVEntityForeignKeyColumn> keyColumns = new ArrayList<>();
                for (TableDesignerDriver.ForeignKeyColumn pair : spec.columns()) {
                    keyColumns.add(new DBVEntityForeignKeyColumn(foreignKey, pair.ownColumn().getName(), pair.refColumn().getName()));
                }
                foreignKey.setAttributes(keyColumns);
                vEntity.addForeignKey(foreignKey);
                return null;
            });
        } catch (DBInterruptedException e) {
            return false;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(GridNavMessages.gridnav_vfk_title, GridNavMessages.gridnav_vfk_save_failed, e);
            return false;
        }
        entity.getDataSource().getContainer().persistConfiguration();
        return true;
    }

    /**
     * The virtual foreign keys of a table (none: an empty list).
     */
    @NotNull
    static List<DBVEntityForeignKey> getForeignKeys(@NotNull DBSEntity entity) {
        DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, false);
        return vEntity == null ? List.of() : List.copyOf(vEntity.getForeignKeys());
    }

    /**
     * "column → table.column" of a virtual foreign key, for menus.
     */
    @NotNull
    static String describe(@NotNull DBVEntityForeignKey foreignKey) {
        List<String> own = new ArrayList<>();
        List<String> referenced = new ArrayList<>();
        for (DBVEntityForeignKeyColumn column : foreignKey.getAttributes()) {
            own.add(column.getAttributeName());
            referenced.add(column.getRefAttributeName());
        }
        // The referenced table as the UI names it (the key keeps a navigator path, whose last segment may carry an object id)
        DBSEntity targetEntity = foreignKey.getAssociatedEntity();
        String target = targetEntity != null ? DBUtils.getObjectFullName(targetEntity, DBPEvaluationContext.UI)
            : foreignKey.getRefEntityId() == null ? "?" : foreignKey.getRefEntityId().substring(foreignKey.getRefEntityId().lastIndexOf('/') + 1); //$NON-NLS-1$
        return String.join(", ", own) + " → " + target + " (" + String.join(", ", referenced) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /**
     * Removes a virtual foreign key after a confirmation (Cancel is the default).
     *
     * @return whether it was removed
     */
    static boolean removeForeignKey(@NotNull Component parent, @NotNull DBSEntity entity, @NotNull DBVEntityForeignKey foreignKey) {
        String[] options = {GridNavMessages.gridnav_vfk_remove_button, ToolsMessages.button_cancel};
        int answer = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(parent),
            NLS.bind(GridNavMessages.gridnav_vfk_remove_confirm, describe(foreignKey)), GridNavMessages.gridnav_vfk_remove_title,
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (answer != 0) {
            return false;
        }
        DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, false);
        if (vEntity == null) {
            return false;
        }
        vEntity.removeForeignKey(foreignKey);
        entity.getDataSource().getContainer().persistConfiguration();
        return true;
    }

    private static void persist(@NotNull DBSEntity entity, @NotNull DBVEntity vEntity, @NotNull DBVEntityConstraint key) {
        if (!entity.getDataSource().getContainer().persistConfiguration()) {
            log.warn("Can't save the virtual key of " + entity.getName());
        }
        DBUtils.fireObjectUpdate(vEntity, key);
    }

    @NotNull
    private static String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
}
