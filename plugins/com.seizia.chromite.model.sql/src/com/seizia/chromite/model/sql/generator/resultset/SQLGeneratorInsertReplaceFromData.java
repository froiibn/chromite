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
package com.seizia.chromite.model.sql.generator.resultset;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDInsertReplaceMethod;
import com.seizia.chromite.model.data.DBDResultSetDataProvider;
import com.seizia.chromite.model.data.DBDValueRow;
import com.seizia.chromite.model.impl.sql.BaseInsertMethod;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import org.jkiss.utils.CommonUtils;

import java.util.List;

/**
 * INSERT statements of the selected rows with one of the dialect's insert replace methods: an upsert such as PostgreSQL
 * {@code ON CONFLICT … DO UPDATE SET}, MySQL/MariaDB {@code ON DUPLICATE KEY UPDATE} or {@code REPLACE INTO}, or an insert
 * that skips duplicates ({@code ON CONFLICT DO NOTHING}, {@code INSERT IGNORE}).
 *
 * 설계: 데이터 전송의 "중복 키 처리"와 같은 방언 확장({@code com.seizia.chromite.sqlInsertMethod}, 방언의 {@code insertMethods})을 쓴다.
 * 그래서 문장의 앞부분({@link DBDInsertReplaceMethod#getOpeningClause})과 뒷부분({@link DBDInsertReplaceMethod#getTrailingClause})이
 * 데이터 전송이 실행하는 문장과 같고, 방식을 등록한 데이터베이스마다 따로 코드를 두지 않는다. 방식은 테이블({@link DBSTable})의
 * 키를 읽으므로 Job 스레드에서 부른다. 열은 결과 중 테이블의 열만 쓰고, 값은 다른 결과 생성기처럼 방언의 리터럴로 쓴다.
 * 문장은 만들 뿐 실행하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 결과 행에서 SQL 만들기 (docs/chromite/swing-result-generate-sql.md)
 */
public class SQLGeneratorInsertReplaceFromData extends SQLGeneratorResultSet {

    @NotNull
    private final DBDInsertReplaceMethod method;

    public SQLGeneratorInsertReplaceFromData(@NotNull DBDInsertReplaceMethod method) {
        this.method = method;
    }

    @Override
    public boolean isInsertOption() {
        return true;
    }

    @Override
    public boolean isDMLOption() {
        return true;
    }

    @Override
    protected void generateSQL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull StringBuilder sql,
        @NotNull DBDResultSetDataProvider dataProvider
    ) throws DBException {
        DBSEntity entity = dataProvider.getSingleSource();
        if (!(entity instanceof DBSTable table)) {
            throw new DBException("The result has no single source table");
        }
        List<DBDAttributeBinding> columns = getTableColumns(dataProvider, table);
        if (columns.isEmpty()) {
            throw new DBException("The result has no column of table " + table.getName());
        }
        DBSAttributeBase[] tableColumns = new DBSAttributeBase[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            tableColumns[i] = columns.get(i).getEntityAttribute();
        }
        String opening = CommonUtils.notEmpty(method.getOpeningClause(monitor, table));
        String trailing = method.getTrailingClause(monitor, table, tableColumns);
        if (CommonUtils.isEmpty(trailing) && opening.equalsIgnoreCase(new BaseInsertMethod().getOpeningClause(monitor, table))) {
            // The method found nothing to decide duplicates by (PostgreSQL ON CONFLICT needs a primary key or a unique
            // index): the statement would be a plain INSERT, not what was asked for
            throw new DBException("Table " + table.getName() + " has no primary key or unique index for this statement");
        }
        String separator = getLineSeparator();
        for (DBDValueRow row : dataProvider.getSelectedRows()) {
            sql.append(opening).append(' ').append(getEntityName(table)).append(separator).append('(');
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(DBUtils.getQuotedIdentifier((DBSEntityAttribute) tableColumns[i]));
            }
            sql.append(')').append(separator).append("VALUES(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                appendAttributeValue(dataProvider, sql, columns.get(i), row, false);
            }
            sql.append(')');
            if (!CommonUtils.isEmpty(trailing)) {
                // The methods start the clause with a space (" ON CONFLICT …")
                sql.append(separator).append(trailing.trim());
            }
            sql.append(";\n");
        }
    }
}
