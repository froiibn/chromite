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
package com.seizia.chromite.ui.swing.design;

import org.eclipse.core.runtime.IAdapterFactory;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.postgresql.model.PostgreAttribute;
import com.seizia.chromite.ext.postgresql.model.PostgreIndex;
import com.seizia.chromite.ext.postgresql.model.PostgreIndexColumn;
import com.seizia.chromite.ext.postgresql.model.PostgreTableConstraint;
import com.seizia.chromite.ext.postgresql.model.PostgreTableForeignKey;
import com.seizia.chromite.ext.postgresql.model.PostgreTableForeignKeyColumn;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;
import com.seizia.chromite.model.struct.rdb.DBSTableForeignKey;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;

import java.util.Collection;
import java.util.List;

/**
 * PostgreSQL 테이블 디자이너 드라이버({@link TableDesignerDriver}).
 *
 * 설계: SWT의 {@code PostgreConstraintConfigurator}(CHECK 식은 {@code setSource}), {@code PostgreIndexConfigurator}
 * ({@code PostgreIndexColumn}, 연산자 클래스 없음 -1), {@code PostgreForeignKeyConfigurator}({@code PostgreTableForeignKeyColumn})가
 * 대화상자 뒤에 하는 일을 그대로 한다. 테이블 클래스({@code PostgreTableBase})의 어댑터로 등록한다(plugin.xml).
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 */
public final class PostgreTableDesignerDriver implements TableDesignerDriver, IAdapterFactory {

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        return adapterType == TableDesignerDriver.class ? adapterType.cast(this) : null;
    }

    @Override
    public Class<?>[] getAdapterList() {
        return new Class<?>[]{TableDesignerDriver.class};
    }

    @Nullable
    @Override
    public Class<? extends DBSEntityConstraint> getCheckConstraintClass(@NotNull DBSEntity table) {
        // PostgreTable.getSupportedConstraints: CHECK is a PostgreTableConstraint
        return PostgreTableConstraint.class;
    }

    @Override
    public void setCheckExpression(@NotNull DBSEntityConstraint constraint, @NotNull String expression) {
        PostgreTableConstraint checkConstraint = (PostgreTableConstraint) constraint;
        checkConstraint.setConstraintType(DBSEntityConstraintType.CHECK);
        checkConstraint.setSource(expression);
    }

    @NotNull
    @Override
    public Collection<? extends DBSEntityConstraint> getExtraConstraints(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table) {
        return List.of();
    }

    @Override
    public void configureIndex(@NotNull DBSTableIndex index, boolean unique, @NotNull List<IndexColumn> columns) {
        PostgreIndex postgreIndex = (PostgreIndex) index;
        postgreIndex.setUnique(unique);
        int position = 1;
        for (IndexColumn column : columns) {
            postgreIndex.addColumn(new PostgreIndexColumn(
                postgreIndex, (PostgreAttribute) column.attribute(), null, position++, column.ascending(), -1, false));
        }
    }

    @Override
    public void configureForeignKey(
        @NotNull DBSTableForeignKey foreignKey,
        @NotNull DBSEntityConstraint referencedKey,
        @NotNull DBSForeignKeyModifyRule deleteRule,
        @NotNull DBSForeignKeyModifyRule updateRule,
        @NotNull List<ForeignKeyColumn> columns
    ) throws DBException {
        PostgreTableForeignKey postgreKey = (PostgreTableForeignKey) foreignKey;
        postgreKey.setReferencedConstraint(referencedKey);
        postgreKey.setDeleteRule(deleteRule);
        postgreKey.setUpdateRule(updateRule);
        int position = 1;
        for (ForeignKeyColumn column : columns) {
            postgreKey.addColumn(new PostgreTableForeignKeyColumn(
                postgreKey, (PostgreAttribute) column.ownColumn(), position++, (PostgreAttribute) column.refColumn()));
        }
    }
}
