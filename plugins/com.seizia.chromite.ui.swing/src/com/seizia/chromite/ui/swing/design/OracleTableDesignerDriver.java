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

import com.seizia.chromite.ext.oracle.model.OracleTableColumn;
import com.seizia.chromite.ext.oracle.model.OracleTableConstraint;
import com.seizia.chromite.ext.oracle.model.OracleTableForeignKey;
import com.seizia.chromite.ext.oracle.model.OracleTableForeignKeyColumn;
import com.seizia.chromite.ext.oracle.model.OracleTableIndex;
import com.seizia.chromite.ext.oracle.model.OracleTableIndexColumn;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;
import com.seizia.chromite.model.struct.rdb.DBSTableForeignKey;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;
import org.eclipse.core.runtime.IAdapterFactory;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Oracle 테이블 디자이너 드라이버({@link TableDesignerDriver}).
 *
 * 설계: SWT의 {@code OracleConstraintConfigurator}(CHECK 식은 {@code setSearchCondition}), {@code OracleIndexConfigurator}
 * ({@code OracleTableIndexColumn}, 식 없음), {@code OracleForeignKeyConfigurator}({@code OracleTableForeignKeyColumn})가
 * 대화상자 뒤에 하는 일을 그대로 한다. 테이블 클래스({@code OracleTableBase})의 어댑터로 등록한다(plugin.xml).
 * <ul>
 *     <li>CHECK는 {@code OracleTableBase.getSupportedConstraints}처럼 {@link OracleTableConstraint}이고, {@code OracleTable.getConstraints}가
 *     CHECK도 돌려주므로 따로 읽을 제약이 없다.</li>
 *     <li>외래 키 규칙: Oracle은 ON DELETE CASCADE·SET NULL만 받고 ON UPDATE 절이 없다({@code OracleTableForeignKey.setUpdateRule}는
 *     무시하고 {@code getUpdateRule}은 늘 NO ACTION). SWT 설정기는 다섯 규칙을 모두 보여 RESTRICT·SET DEFAULT를 고르면 서버가
 *     ORA-00905로 거절했다. 공통 대화상자의 규칙 목록은 그대로 두고, 받을 수 없는 규칙은 추가할 때 이유와 함께 거절한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: Oracle 인덱스·외래 키·CHECK (docs/chromite/swing-table-designer.md, swing-create-objects.md)
 */
public final class OracleTableDesignerDriver implements TableDesignerDriver, IAdapterFactory {

    /**
     * ON DELETE rules Oracle accepts (NO ACTION writes no clause).
     */
    private static final Set<DBSForeignKeyModifyRule> DELETE_RULES = Set.of(
        DBSForeignKeyModifyRule.NO_ACTION, DBSForeignKeyModifyRule.CASCADE, DBSForeignKeyModifyRule.SET_NULL);

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
        return OracleTableConstraint.class;
    }

    @Override
    public void setCheckExpression(@NotNull DBSEntityConstraint constraint, @NotNull String expression) {
        OracleTableConstraint checkConstraint = (OracleTableConstraint) constraint;
        checkConstraint.setConstraintType(DBSEntityConstraintType.CHECK);
        checkConstraint.setSearchCondition(expression);
    }

    @NotNull
    @Override
    public Collection<? extends DBSEntityConstraint> getExtraConstraints(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table) {
        return List.of();
    }

    @Override
    public void configureIndex(@NotNull DBSTableIndex index, boolean unique, @NotNull List<IndexColumn> columns) {
        OracleTableIndex oracleIndex = (OracleTableIndex) index;
        oracleIndex.setUnique(unique);
        int position = 1;
        for (IndexColumn column : columns) {
            oracleIndex.addColumn(new OracleTableIndexColumn(
                oracleIndex, (OracleTableColumn) column.attribute(), position++, column.ascending(), null));
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
        if (!DELETE_RULES.contains(deleteRule)) {
            throw new DBException("Oracle supports ON DELETE CASCADE or SET NULL only, not " + deleteRule.getName());
        }
        if (updateRule != DBSForeignKeyModifyRule.NO_ACTION) {
            throw new DBException("Oracle has no ON UPDATE rule; choose No Action");
        }
        OracleTableForeignKey oracleKey = (OracleTableForeignKey) foreignKey;
        oracleKey.setReferencedConstraint(referencedKey);
        oracleKey.setDeleteRule(deleteRule);
        int position = 1;
        // The referenced column of each pair comes from the referenced key's column at the same position
        // (OracleTableForeignKeyColumn.getReferencedColumn), so only the own column is set
        for (ForeignKeyColumn column : columns) {
            oracleKey.addColumn(new OracleTableForeignKeyColumn(oracleKey, (OracleTableColumn) column.ownColumn(), position++));
        }
    }
}
