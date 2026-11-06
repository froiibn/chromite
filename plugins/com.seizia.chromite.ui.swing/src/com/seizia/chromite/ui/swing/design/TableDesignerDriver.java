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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.rdb.DBSForeignKeyModifyRule;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableForeignKey;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;

import java.util.Collection;
import java.util.List;

/**
 * 테이블 디자이너의 드라이버별 부분: 새 인덱스·외래 키·CHECK 제약에 열과 식을 넣는다.
 *
 * 설계: SWT에서는 이 일을 드라이버 UI 플러그인({@code ext.*.ui})의 {@code DBEObjectConfigurator}가 대화상자를 띄운 뒤 한다
 * (예: {@code PostgreIndexConfigurator}, {@code SQLServerForeignKeyConfigurator}, {@code MySQLConstraintConfigurator}).
 * 그 플러그인들은 SWT에 묶여 있어 Swing 제품에 넣을 수 없으므로, 대화상자는 Swing 디자이너가 공통으로 띄우고
 * 대화상자 결과를 모델 객체에 넣는 부분(설정기의 UI 아닌 부분)만 드라이버마다 이 인터페이스로 구현한다.
 * 열 객체 생성자({@code PostgreIndexColumn} 등)가 드라이버마다 달라 모델 계층에 공통 API가 없기 때문이다.
 * PK·UK는 공통 API({@code AbstractTableConstraint#addAttributeReference})가 있어 이 인터페이스 없이 만든다.
 * <p>
 * 구현은 테이블 클래스의 어댑터({@code org.eclipse.core.runtime.adapters}, plugin.xml)로 등록한다. 어댑터 관리자는 그 드라이버의
 * 테이블을 어댑트할 때만 구현 클래스를 읽으므로, 드라이버 번들은 선택 의존(resolution:=optional)으로 둔다.
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 */
public interface TableDesignerDriver {

    /**
     * A column of a new index.
     */
    record IndexColumn(@NotNull DBSEntityAttribute attribute, boolean ascending) {
    }

    /**
     * A column pair of a new foreign key: the column of the edited table and the column of the referenced key.
     */
    record ForeignKeyColumn(@NotNull DBSEntityAttribute ownColumn, @NotNull DBSEntityAttribute refColumn) {
    }

    /**
     * Class of the CHECK constraints the table manager creates, or null when the driver does not support them.
     */
    @Nullable
    Class<? extends DBSEntityConstraint> getCheckConstraintClass(@NotNull DBSEntity table);

    /**
     * Sets the expression of a new CHECK constraint (created from {@link #getCheckConstraintClass}).
     */
    void setCheckExpression(@NotNull DBSEntityConstraint constraint, @NotNull String expression) throws DBException;

    /**
     * Keys the table does not return from {@link DBSEntity#getConstraints} (e.g. SQL Server CHECK constraints).
     */
    @NotNull
    Collection<? extends DBSEntityConstraint> getExtraConstraints(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table)
        throws DBException;

    /**
     * Fills a new index (created by the index manager): uniqueness and columns, in order.
     */
    void configureIndex(@NotNull DBSTableIndex index, boolean unique, @NotNull List<IndexColumn> columns) throws DBException;

    /**
     * Fills a new foreign key (created by the foreign key manager): referenced key, rules and column pairs, in order.
     */
    void configureForeignKey(
        @NotNull DBSTableForeignKey foreignKey,
        @NotNull DBSEntityConstraint referencedKey,
        @NotNull DBSForeignKeyModifyRule deleteRule,
        @NotNull DBSForeignKeyModifyRule updateRule,
        @NotNull List<ForeignKeyColumn> columns
    ) throws DBException;
}
