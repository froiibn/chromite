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
package com.seizia.chromite.ext.postgresql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBStructUtils;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSTablePartition;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL table partition
 *
 * 설계: 파티션 범위(Partition expression, {@code FOR VALUES …})는 CREATE TABLE … PARTITION OF에서만 정한다. 저장된 파티션의
 * 범위를 바꾸려면 DETACH·ATTACH PARTITION이 필요해 속성 편집으로는 바꾸지 않는다.
 *
 * 변경 이력:
 * - 2026-11-08 Partition expression을 저장된 파티션에서 읽기 전용으로 함(전에는 고칠 수 있다고 보였지만 문장이 만들어지지 않음)
 *   (docs/chromite/model-fixes.md "PostgreSQL Partition by")
 */
public class PostgreTablePartition extends PostgreTable implements DBSTablePartition {

    public static final String CAT_PARTITIONING = "Partitioning";

    private String partitionExpression;
    private PostgreTable partitionOf;

    public PostgreTablePartition(PostgreTable container) {
        super(container);
        this.partitionExpression = "FOR VALUES ";
        this.setPartition(true);
        this.setName("newpartition");
        this.partitionOf = container;
    }

    public PostgreTablePartition(PostgreTableContainer container, ResultSet dbResult) {
        super(container, dbResult);
        this.setPartition(true);
        this.partitionExpression = JDBCUtils.safeGetString(dbResult, "partition_expr");
    }

    @NotNull
    @Override
    public DBSTable getParentTable() {
        return partitionOf;
    }

    @Override
    public boolean needFullPath() {
        // Postgres tables can be queried directly without a parent table.
        return false;
    }

    // Set by CREATE TABLE … PARTITION OF only: PostgreSQL changes the bounds of an existing partition only by detaching
    // and attaching it again, which the property command does not generate
    @Property(viewable = true, editable = true, order = 60)
    @Nullable
    public String getPartitionExpression() {
        return partitionExpression;
    }

    public void setPartitionExpression(String expr) {
        this.partitionExpression = expr;
    }


    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        options = new HashMap<>(options);
        options.put(DBPScriptObject.OPTION_DDL_SKIP_FOREIGN_KEYS, true);
        options.put(OPTION_DDL_SEPARATE_FOREIGN_KEYS_STATEMENTS, false);
        options.put(OPTION_INCLUDE_NESTED_OBJECTS, false);
        options.put(OPTION_INCLUDE_PERMISSIONS, false);
        return DBStructUtils.generateTableDDL(monitor, this, options, false);
    }

    public PostgreTable getPartitionOf() {
        return partitionOf;
    }

    @Override
    public boolean isSubPartition() {
        return partitionOf instanceof PostgreTablePartition;
    }

    @Nullable
    @Override
    public DBSTablePartition getPartitionParent() {
        if (partitionOf instanceof PostgreTablePartition) {
            return (PostgreTablePartition) partitionOf;
        }
        return null;
    }
}
