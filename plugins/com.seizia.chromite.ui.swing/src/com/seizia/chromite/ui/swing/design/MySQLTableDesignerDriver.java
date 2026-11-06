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
import org.jkiss.dbeaver.ext.mysql.model.MySQLTable;
import org.jkiss.dbeaver.ext.mysql.model.MySQLTableColumn;
import org.jkiss.dbeaver.ext.mysql.model.MySQLTableConstraint;
import org.jkiss.dbeaver.ext.mysql.model.MySQLTableForeignKey;
import org.jkiss.dbeaver.ext.mysql.model.MySQLTableForeignKeyColumn;
import org.jkiss.dbeaver.ext.mysql.model.MySQLTableIndex;
import org.jkiss.dbeaver.ext.mysql.model.MySQLTableIndexColumn;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintType;
import org.jkiss.dbeaver.model.struct.rdb.DBSForeignKeyModifyRule;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableForeignKey;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;

import java.util.Collection;
import java.util.List;

/**
 * MySQL·MariaDB 테이블 디자이너 드라이버({@link TableDesignerDriver}).
 *
 * 설계: SWT의 {@code MySQLConstraintConfigurator}(CHECK 식은 {@code setCheckClause}, 서버가 CHECK 제약을 지원할 때만),
 * {@code MySQLIndexConfigurator}({@code MySQLTableIndexColumn}, 접두 길이 없음), {@code MySQLForeignKeyConfigurator}
 * ({@code MySQLTableForeignKeyColumn})가 대화상자 뒤에 하는 일을 그대로 한다. 테이블 클래스({@code MySQLTable})의 어댑터로 등록한다.
 * 새 인덱스의 종류는 SWT 대화상자({@code EditIndexPage})가 처음 고르는 값, 곧 데이터 소스가 지원하는 첫 종류(BTREE)다.
 * 인덱스 관리자가 만든 기본값({@code DBSIndexType.OTHER})을 그대로 두면 {@code MySQLIndexManager}가 {@code USING OTHER}를 써서 DDL이 틀린다.
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 새 인덱스에 SWT 대화상자의 기본 종류(첫 지원 종류, BTREE)를 넣음: 관리자 기본값 OTHER로 "USING OTHER" DDL이 나가던 것
 *   (docs/chromite/swing-table-designer.md)
 */
public final class MySQLTableDesignerDriver implements TableDesignerDriver, IAdapterFactory {

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
        // MySQLTable.getSupportedConstraints offers CHECK only when the server supports it
        return table instanceof MySQLTable mySQLTable && mySQLTable.getDataSource().supportsCheckConstraints()
            ? MySQLTableConstraint.class : null;
    }

    @Override
    public void setCheckExpression(@NotNull DBSEntityConstraint constraint, @NotNull String expression) {
        MySQLTableConstraint checkConstraint = (MySQLTableConstraint) constraint;
        checkConstraint.setConstraintType(DBSEntityConstraintType.CHECK);
        checkConstraint.setCheckClause(expression);
    }

    @NotNull
    @Override
    public Collection<? extends DBSEntityConstraint> getExtraConstraints(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table) {
        // MySQLTable.getConstraints already returns the CHECK constraints
        return List.of();
    }

    @Override
    public void configureIndex(@NotNull DBSTableIndex index, boolean unique, @NotNull List<IndexColumn> columns) {
        MySQLTableIndex mySQLIndex = (MySQLTableIndex) index;
        // The index manager creates the index with DBSIndexType.OTHER and writes "USING <type>" for every type but
        // FULLTEXT; the SWT dialog preselects the first supported type (BTREE), so the same default is set here
        mySQLIndex.setIndexType(mySQLIndex.getDataSource().supportedIndexTypes().get(0));
        mySQLIndex.setUnique(unique);
        int position = 1;
        for (IndexColumn column : columns) {
            mySQLIndex.addColumn(new MySQLTableIndexColumn(
                mySQLIndex, (MySQLTableColumn) column.attribute(), position++, column.ascending(), false, null));
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
        MySQLTableForeignKey mySQLKey = (MySQLTableForeignKey) foreignKey;
        mySQLKey.setReferencedConstraint(referencedKey);
        mySQLKey.setDeleteRule(deleteRule);
        mySQLKey.setUpdateRule(updateRule);
        int position = 1;
        for (ForeignKeyColumn column : columns) {
            mySQLKey.addColumn(new MySQLTableForeignKeyColumn(
                mySQLKey, (MySQLTableColumn) column.ownColumn(), position++, (MySQLTableColumn) column.refColumn()));
        }
    }
}
