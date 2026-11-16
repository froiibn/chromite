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
import com.seizia.chromite.ext.mssql.model.SQLServerTable;
import com.seizia.chromite.ext.mssql.model.SQLServerTableCheckConstraint;
import com.seizia.chromite.ext.mssql.model.SQLServerTableColumn;
import com.seizia.chromite.ext.mssql.model.SQLServerTableForeignKey;
import com.seizia.chromite.ext.mssql.model.SQLServerTableForeignKeyColumn;
import com.seizia.chromite.ext.mssql.model.SQLServerTableIndex;
import com.seizia.chromite.ext.mssql.model.SQLServerTableIndexColumn;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;
import com.seizia.chromite.model.struct.rdb.DBSTableForeignKey;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;

import java.util.Collection;
import java.util.List;

/**
 * SQL Server 테이블 디자이너 드라이버({@link TableDesignerDriver}).
 *
 * 설계: SWT의 {@code SQLServerCheckConstraintConfigurator}({@code setCheckConstraintDefinition}), {@code SQLServerIndexConfigurator}
 * ({@code SQLServerTableIndexColumn}, 포함 열 아님), {@code SQLServerForeignKeyConfigurator}({@code SQLServerTableForeignKeyColumn})가
 * 대화상자 뒤에 하는 일을 그대로 한다. SQL Server의 CHECK 제약은 고유 키와 다른 클래스·캐시라
 * {@code getConstraints}에 없으므로 {@link #getExtraConstraints}로 따로 돌려준다. 테이블 클래스({@code SQLServerTable})의 어댑터로 등록한다.
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 */
public final class SQLServerTableDesignerDriver implements TableDesignerDriver, IAdapterFactory {

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
        return SQLServerTableCheckConstraint.class;
    }

    @Override
    public void setCheckExpression(@NotNull DBSEntityConstraint constraint, @NotNull String expression) {
        ((SQLServerTableCheckConstraint) constraint).setCheckConstraintDefinition(expression);
    }

    @NotNull
    @Override
    public Collection<? extends DBSEntityConstraint> getExtraConstraints(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table)
        throws DBException {
        if (table instanceof SQLServerTable serverTable) {
            Collection<SQLServerTableCheckConstraint> checks = serverTable.getCheckConstraints(monitor);
            return checks == null ? List.of() : checks;
        }
        return List.of();
    }

    @Override
    public void configureIndex(@NotNull DBSTableIndex index, boolean unique, @NotNull List<IndexColumn> columns) {
        SQLServerTableIndex serverIndex = (SQLServerTableIndex) index;
        serverIndex.setUnique(unique);
        int position = 1;
        for (IndexColumn column : columns) {
            serverIndex.addColumn(new SQLServerTableIndexColumn(
                serverIndex, 0, (SQLServerTableColumn) column.attribute(), position++, column.ascending(), false));
        }
    }

    @Override
    public void configureForeignKey(
        @NotNull DBSTableForeignKey foreignKey,
        @NotNull DBSEntityConstraint referencedKey,
        @NotNull DBSForeignKeyModifyRule deleteRule,
        @NotNull DBSForeignKeyModifyRule updateRule,
        @NotNull List<ForeignKeyColumn> columns
    ) {
        SQLServerTableForeignKey serverKey = (SQLServerTableForeignKey) foreignKey;
        serverKey.setReferencedConstraint(referencedKey);
        serverKey.setDeleteRule(deleteRule);
        serverKey.setUpdateRule(updateRule);
        int position = 1;
        for (ForeignKeyColumn column : columns) {
            serverKey.addColumn(new SQLServerTableForeignKeyColumn(
                serverKey, (SQLServerTableColumn) column.ownColumn(), position++, (SQLServerTableColumn) column.refColumn()));
        }
    }
}
