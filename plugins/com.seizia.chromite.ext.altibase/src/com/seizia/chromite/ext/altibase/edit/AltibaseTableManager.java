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
package com.seizia.chromite.ext.altibase.edit;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.altibase.extension.AltibaseTableTtl;
import com.seizia.chromite.ext.altibase.model.AltibaseConstraint;
import com.seizia.chromite.ext.altibase.model.AltibaseTable;
import com.seizia.chromite.ext.altibase.model.AltibaseTableColumn;
import com.seizia.chromite.ext.altibase.model.AltibaseTableIndex;
import com.seizia.chromite.ext.generic.edit.GenericTableManager;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableForeignKey;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

/**
 * Altibase table manager.
 *
 * 설계: 새 테이블의 TTL({@link AltibaseTable#getNewTableTtl})은 CREATE TABLE 문 끝의 절로 붙인다. 문장은 연결의 서버 확장이 주는 중립
 * 능력({@link AltibaseTableTtl#getCreateClause})이 만들고, 그 능력이 없는 연결(Altibase, 확장 번들을 뺀 제품)에서는 아무것도 붙이지 않는다.
 * 두 번째 문장(ALTER TABLE)이 아니라 같은 문장인 것은 서버가 절을 거절하면 테이블도 만들어지지 않아서다. 디자이너는 열 목록 뒤에
 * 다른 절(테이블스페이스·파티션)을 쓰지 않으므로 끝에 붙이면 서버가 받는 자리다(서버는 그 절들 뒤에만 받음,
 * docs/chromite/altibase.md "테이블 TTL 자리").
 *
 * 변경 이력:
 * - 2026-11-21 새 테이블의 TTL 절(앱 변경 #155, docs/chromite/altibase.md "테이블 TTL 자리")
 */
public class AltibaseTableManager extends GenericTableManager {
    private static final Class<? extends DBSObject>[] CHILD_TYPES = CommonUtils.array(
            AltibaseTableColumn.class,
            AltibaseConstraint.class,
            GenericTableForeignKey.class,
            AltibaseTableIndex.class
            );

    @NotNull
    @Override
    public Class<? extends DBSObject>[] getChildTypes() {
        return CHILD_TYPES;
    }

    @Override
    protected void appendTableModifiers(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GenericTableBase table,
        @NotNull NestedObjectCommand tableProps,
        @NotNull StringBuilder ddl,
        boolean alter,
        @NotNull Map<String, Object> options
    ) throws DBException {
        super.appendTableModifiers(monitor, table, tableProps, ddl, alter, options);
        if (alter || !(table instanceof AltibaseTable altibaseTable) || altibaseTable.getNewTableTtl() == null) {
            return;
        }
        AltibaseTableTtl.Change ttl = altibaseTable.getNewTableTtl();
        AltibaseTableTtl capability = AltibaseTableTtl.forNewTable(altibaseTable);
        if (capability == null) {
            return;
        }
        // The designer clears a removed column; a column that is no longer the table's must not reach the server
        if (ttl.column() == null || !CommonUtils.safeCollection(table.getAttributes(monitor)).contains(ttl.column())) {
            throw new DBException("TTL column is not a column of table '" + table.getName() + "'");
        }
        ddl.append(capability.getCreateClause(ttl));
    }
}
