/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.model.sql.generator.resultset;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.*;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.generator.SQLGeneratorBase;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import org.jkiss.utils.ArrayUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Generators of statements from the selected rows of a result set (Generate SQL in the result grid).
 *
 * 설계: 문장을 만들 뿐 실행하지 않는다. 키가 없는 결과에서 쓸 수 없는 문장(WHERE 없는 UPDATE처럼 테이블 전체를 바꾸게 되는 문장)은
 * 생성기가 {@link #isUniqueKeyRequired}로 알리고 화면이 그 항목을 끈다.
 *
 * 변경 이력:
 * - 2026-11-08 Chromite: {@link #isUniqueKeyRequired}, 결과의 원본 테이블 열만 고르는 {@link #getTableColumns}
 *   (MERGE·INSERT 대체 방식 생성기용) (docs/chromite/swing-result-generate-sql.md)
 */
public abstract class SQLGeneratorResultSet extends SQLGeneratorBase<DBDResultSetDataProvider> {

    @NotNull
    public DBDResultSetDataProvider getDataProvider() {
        return objects.getFirst();
    }

    /**
     * Whether the statement needs a unique key of the result's table ({@link DBDResultSetDataProvider#getDefaultRowIdentifier}):
     * without one it would change other rows than the selected ones, or would not be valid SQL.
     */
    public boolean isUniqueKeyRequired() {
        return false;
    }

    /**
     * The result columns that are columns of the table: an expression or a column of another table has no place in a
     * statement on the table.
     */
    @NotNull
    protected List<DBDAttributeBinding> getTableColumns(@NotNull DBDResultSetDataProvider dataProvider, @NotNull DBSEntity table)
        throws DBException {
        List<DBDAttributeBinding> columns = new ArrayList<>();
        for (DBDAttributeBinding binding : dataProvider.getVisibleAttributes()) {
            DBSEntityAttribute attribute = binding.getEntityAttribute();
            if (attribute != null && attribute.getParentObject() == table
                && !DBUtils.isPseudoAttribute(attribute) && !DBUtils.isHiddenObject(attribute)) {
                columns.add(binding);
            }
        }
        return columns;
    }

    protected abstract void generateSQL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull StringBuilder sql,
        @NotNull DBDResultSetDataProvider dataProvider
    ) throws DBException;

    @NotNull
    protected Collection<? extends DBSAttributeBase> getAllAttributes(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBDResultSetDataProvider dataProvider
    ) throws DBException {
        return dataProvider.getVisibleAttributes();
    }

    void appendKeyConditions(
        @NotNull StringBuilder sql,
        @NotNull Collection<DBDAttributeBinding> keyAttributes,
        @NotNull DBDValueRow firstRow
    ) throws DBException {
        Object[] values = firstRow.getValues();
        if (!ArrayUtils.isEmpty(values)) {
            Object firstCellValue = values[0];
            if (firstCellValue instanceof DBDDocument document) {
                Object idName = document.getDocumentProperty(DBDDocument.PROP_ID_ATTRIBUTE_NAME);
                Object documentId = document.getDocumentId();
                if (idName != null && documentId != null) {
                    sql.append(idName).append(" = ").append(
                        SQLUtils.quoteString(getDataProvider().getSingleSource(), documentId.toString())
                    );
                    return;
                }
            }
        }
        boolean hasAttr = false;
        for (DBDAttributeBinding attr : keyAttributes) {
            if (hasAttr) {
                sql.append(" AND ");
            }
            appendValueCondition(getDataProvider(), sql, attr, firstRow);
            hasAttr = true;
        }
    }

    @Override
    protected List<DBDAttributeBinding> getKeyAttributes(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBDResultSetDataProvider dataProvider
    ) throws DBException {
        final DBDRowIdentifier rowIdentifier = dataProvider.getDefaultRowIdentifier();
        if (rowIdentifier == null) {
            return Collections.emptyList();
        }
        return rowIdentifier.getAttributes();
    }

    private void appendValueCondition(
        @NotNull DBDResultSetDataProvider dataProvider,
        @NotNull StringBuilder sql,
        @NotNull DBDAttributeBinding binding,
        @NotNull DBDValueRow firstRow
    ) throws DBException {
        Object value = dataProvider.getCellValue(binding, firstRow);
        sql.append(DBUtils.getObjectFullName(binding.getAttribute(), DBPEvaluationContext.DML));
        if (DBUtils.isNullValue(value)) {
            sql.append(" IS NULL");
        } else {
            sql.append("=");
            appendAttributeValue(dataProvider, sql, binding, firstRow, true);
        }
    }

    protected void appendAttributeValue(
        @NotNull DBDResultSetDataProvider dataProvider,
        @NotNull StringBuilder sql,
        @NotNull DBDAttributeBinding binding,
        @NotNull DBDValueRow row,
        boolean isInCondition
    ) throws DBException {
        DBPDataSource dataSource = binding.getDataSource();
        Object value = dataProvider.getCellValue(binding, row);
        DBSAttributeBase attribute = binding.getAttribute();
        if (attribute.getDataKind() == DBPDataKind.DATETIME && isUseCustomDataFormat()) {
            sql.append(SQLUtils.quoteString(
                dataSource,
                SQLUtils.convertValueToSQL(
                    dataSource,
                    attribute,
                    DBUtils.findValueHandler(dataSource, attribute),
                    value,
                    DBDDisplayFormat.UI,
                    isInCondition
                )
            ));
        } else {
            sql.append(
                SQLUtils.convertValueToSQL(dataSource, attribute, value));
        }
    }
}
