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
package com.seizia.chromite.model.sql.data;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import org.jkiss.utils.CommonUtils;

import java.util.List;

/**
 * Finds the statement of a multi-statement query (a SQL Server {@code GO} batch, several selected statements run as
 * one) that produced one of its result sets, so that reading that result again runs only that statement.
 *
 * 설계: 결과를 다시 읽는 일(필터, 서버 정렬, 다음 페이지, 모두 가져오기, 행 수, 열 필터 값 목록, 저장 뒤 새로 고침, Export Data)은
 * 결과의 데이터 컨테이너({@link SQLQueryDataContainer})가 가진 쿼리를 다시 실행한다. 엔진은 배치를 쿼리 하나로 보내므로
 * ({@link SQLScriptBatches}) 컨테이너가 배치 글자 전체를 갖고 있었고, "SELECT 하나인가" 검사({@link SQLQuery#getType()})는 JSQLParser가
 * 첫 문장만 읽어 판단했다. 그래서 {@code SELECT …; UPDATE …}의 결과를 다시 읽으면 UPDATE까지 다시 실행됐다.
 * 여기서는 배치를 방언의 문장 분리기로 나누고(실행 확인 {@code SqlExecutionSafety}와 같은 방법), 결과 집합 순서를 문장 순서에 맞춘다:
 * 바꾸지 않는 SELECT는 결과 집합 하나, OUTPUT·RETURNING이 없는 INSERT·UPDATE·DELETE·MERGE, DDL, COMMIT, ROLLBACK,
 * {@code SELECT … INTO}는 결과 집합이 없다. 그 밖의 문장(EXEC·CALL, 블록, SET, DECLARE, OUTPUT·RETURNING이 있는 DML, 읽지 못한 문장)은
 * 결과 집합 수를 알 수 없으므로, 찾는 결과 앞에 있으면 문장을 정하지 않는다(null). 그때 컨테이너는 다시 읽기를 거절한다.
 * 실행 결과(행 수·결과 집합 순서)를 맞춰 보지 않고 문장 종류로 정하는 것은 엔진이 결과마다 어느 문장인지 알려 주지 않고, 수신기를
 * 만들 때는 그 쿼리의 앞 결과 집합 번호만 알기 때문이다. 정한 문장은 다시 바꾸지 않는 SELECT 하나인지 검사를 거치므로
 * 잘못 맞춰도 다른 SELECT를 읽을 뿐 데이터를 바꾸는 문장은 실행되지 않는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/sql-batch-reread.md)
 */
public final class SQLBatchResultStatement {

    private SQLBatchResultStatement() {
    }

    /**
     * The statement of the query that produced its result set number {@code resultSetNumber} (0 for the first one).
     *
     * @return the query itself when it is one statement and the result set is its first one; the statement of a
     * multi-statement query, with its own text and its offset in the script; or null when the statement can't be
     * found (a statement before it may return any number of result sets, or there are fewer statements that return
     * one), so the result can only be read again by running the whole query again
     */
    @Nullable
    public static SQLQuery find(@NotNull DBPDataSource dataSource, @NotNull SQLQuery query, int resultSetNumber) {
        if (query.representsOneStatement()) {
            // Extracted as one statement by the script parser (a script statement, a batch of one statement)
            return resultSetNumber == 0 ? query : null;
        }
        String text = query.getText();
        List<SQLScriptElement> elements = SQLScriptParser.extractScriptQueries(
            SQLScriptBatches.createParserContext(dataSource, text), 0, text.length(), true, false, false);
        if (elements.size() == 1 && elements.getFirst() instanceof SQLQuery) {
            return resultSetNumber == 0 ? query : null;
        }
        int resultSet = 0;
        for (SQLScriptElement element : elements) {
            if (!(element instanceof SQLQuery statement)) {
                // A control command can't be part of a query sent to the server
                return null;
            }
            int resultSets = getResultSetCount(statement);
            if (resultSets < 0) {
                return null;
            }
            if (resultSets == 1) {
                if (resultSet == resultSetNumber) {
                    SQLQuery result = new SQLQuery(dataSource, statement.getText(), query.getOffset() + statement.getOffset(),
                        statement.getLength());
                    // Found by the script parser: one statement (finding it again returns it as it is)
                    result.setEndsWithDelimiter(CommonUtils.toBoolean(statement.isEndsWithDelimiter()));
                    return result;
                }
                resultSet++;
            }
        }
        return null;
    }

    /**
     * How many result sets the statement returns: 1, 0, or -1 if that is not known.
     */
    private static int getResultSetCount(@NotNull SQLQuery statement) {
        Statement parsed = statement.getStatement();
        if (parsed instanceof PlainSelect plainSelect
            && (!CommonUtils.isEmpty(plainSelect.getIntoTables()) || plainSelect.getIntoTempTable() != null)) {
            // SELECT … INTO: creates a table or sets variables
            return 0;
        }
        if (parsed instanceof Select) {
            // FOR UPDATE and data-changing CTEs return rows too, but must not be run again
            return statement.isModifying() ? -1 : 1;
        }
        if (parsed instanceof Insert insert) {
            return insert.getOutputClause() == null && insert.getReturningClause() == null ? 0 : -1;
        }
        if (parsed instanceof Update update) {
            return update.getOutputClause() == null && update.getReturningClause() == null ? 0 : -1;
        }
        if (parsed instanceof Delete delete) {
            return delete.getOutputClause() == null && delete.getReturningClause() == null ? 0 : -1;
        }
        if (parsed instanceof Merge merge) {
            return merge.getOutputClause() == null ? 0 : -1;
        }
        return switch (statement.getType()) {
            case DDL, COMMIT, ROLLBACK -> 0;
            // SET may change what the later statements return (SHOWPLAN, STATISTICS XML), DECLARE and blocks are not
            // known statements, EXEC and CALL return any number of result sets
            default -> -1;
        };
    }
}
