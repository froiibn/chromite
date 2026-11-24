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
package com.seizia.chromite.ext.oracle.data;

import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCQueryTransformer;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.impl.sql.QueryTransformerLimit;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLUtils;

import java.util.Locale;

/**
 * Row limit of a generated SELECT on Oracle 12c and later: {@code FETCH FIRST n ROWS ONLY} after the whole statement.
 *
 * 설계: 엔진의 {@code RESULT_SET_LIMIT}가 아니라 생성한 스크립트의 자리({@code SCRIPT_ROW_LIMIT})에만 쓴다. 문장 끝에 붙이므로
 * 생성기의 줄바꿈과 WHERE(데이터 찾기)가 그대로 남는다. {@link QueryTransformerRowNum}(WHERE ROWNUM)은 JSQLParser로 문장을 다시 써
 * 한 줄로 만들고, ORDER BY가 있으면 정렬 전에 자른다. SELECT가 아니거나 LIMIT·OFFSET·FETCH·INTO가 있는 문장은 그대로 둔다
 * ({@link QueryTransformerLimit}과 같은 판단).
 *
 * 변경 이력:
 * - 2026-11-24 최초 작성: "상위 1000개 행 선택"이 Oracle에서 제한 없는 SELECT를 열었다(앱 변경 #183,
 *   docs/chromite/swing-explorer-object-actions.md "행 수 제한")
 */
public class QueryTransformerFetchFirst implements DBCQueryTransformer {

    private Number offset;
    private Number length;

    @Override
    public void setParameters(Object... parameters) {
        this.offset = (Number) parameters[0];
        this.length = (Number) parameters[1];
    }

    @Override
    public String transformQueryString(SQLQuery query) throws DBCException {
        SQLDialect dialect = SQLUtils.getDialectFromDataSource(query.getDataSource());
        String testQuery = query.getText().toUpperCase(Locale.ROOT).trim();
        if (!"SELECT".equals(SQLUtils.getFirstKeyword(dialect, testQuery))
            || QueryTransformerLimit.NON_LIMIT_QUERY_PATTERN.matcher(testQuery).find()) {
            return query.getText();
        }
        // The total of rows from the first one; the statement skips an offset (transformStatement)
        return SQLUtils.removeQueryDelimiter(dialect, query.getText())
            + "\nFETCH FIRST " + (offset.longValue() + length.longValue()) + " ROWS ONLY"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public void transformStatement(DBCStatement statement, int parameterIndex) throws DBCException {
        statement.setLimit(offset.longValue(), length.longValue());
    }
}
